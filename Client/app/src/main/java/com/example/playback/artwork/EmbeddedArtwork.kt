package com.example.playback.artwork

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import androidx.core.net.toUri
import com.example.domain.model.Track
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * A Coil model for cover art stored inside an audio file.
 *
 * Keeping this as a URI instead of resolving it to a path is important for Storage Access
 * Framework and MediaStore items, whose backing file is intentionally not exposed to apps.
 */
data class EmbeddedArtwork(val audioUri: Uri)

class EmbeddedArtworkLoader(private val context: Context) {
    private val byteCache = object : LruCache<String, ByteArray>(MAX_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val missingArtwork = LruCache<String, Boolean>(MAX_MISSING_CACHE_ENTRIES)
    private val extractionLocks = ConcurrentHashMap<String, Mutex>()
    private val extractionPermits = Semaphore(MAX_CONCURRENT_EXTRACTIONS)

    fun cached(audioUri: Uri): ByteArray? = synchronized(this) {
        byteCache.get(audioUri.toString())
    }

    suspend fun load(audioUri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        val cacheKey = audioUri.toString()
        when (val cached = cacheLookup(cacheKey)) {
            is CacheLookup.Hit -> return@withContext cached.bytes
            CacheLookup.Missing -> return@withContext null
            null -> Unit
        }

        val lock = extractionLocks.getOrPut(cacheKey, ::Mutex)
        try {
            lock.withLock {
                when (val cached = cacheLookup(cacheKey)) {
                    is CacheLookup.Hit -> return@withLock cached.bytes
                    CacheLookup.Missing -> return@withLock null
                    null -> Unit
                }
                val artwork = extractionPermits.withPermit {
                    extract(audioUri)?.takeIf {
                        it.isNotEmpty() && it.size <= MAX_EMBEDDED_ARTWORK_BYTES
                    }
                }
                synchronized(this@EmbeddedArtworkLoader) {
                    if (artwork == null) {
                        missingArtwork.put(cacheKey, true)
                    } else {
                        byteCache.put(cacheKey, artwork)
                    }
                }
                artwork
            }
        } finally {
            extractionLocks.remove(cacheKey, lock)
        }
    }

    private fun cacheLookup(cacheKey: String): CacheLookup? = synchronized(this) {
        byteCache.get(cacheKey)?.let(CacheLookup::Hit)
            ?: if (missingArtwork.get(cacheKey) == true) CacheLookup.Missing else null
    }

    private fun extract(audioUri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            when (audioUri.scheme?.lowercase()) {
                "content", "android.resource" -> {
                    // Use the provider's descriptor instead of trying to derive an inaccessible
                    // filesystem path from a MediaStore or Storage Access Framework URI.
                    context.contentResolver.openAssetFileDescriptor(audioUri, "r")?.use { asset ->
                        if (asset.declaredLength >= 0) {
                            retriever.setDataSource(
                                asset.fileDescriptor,
                                asset.startOffset,
                                asset.declaredLength
                            )
                        } else {
                            retriever.setDataSource(asset.fileDescriptor)
                        }
                    } ?: return null
                }
                "http", "https" -> retriever.setDataSource(audioUri.toString(), emptyMap())
                "file" -> retriever.setDataSource(audioUri.path)
                else -> retriever.setDataSource(audioUri.toString())
            }
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private sealed interface CacheLookup {
        data class Hit(val bytes: ByteArray) : CacheLookup
        data object Missing : CacheLookup
    }

    companion object {
        private const val MAX_MEMORY_CACHE_BYTES = 12 * 1024 * 1024
        private const val MAX_EMBEDDED_ARTWORK_BYTES = 20 * 1024 * 1024
        private const val MAX_MISSING_CACHE_ENTRIES = 2_048
        private const val MAX_CONCURRENT_EXTRACTIONS = 2
    }
}

class EmbeddedArtworkFetcher(
    private val data: EmbeddedArtwork,
    private val options: Options,
    private val loader: EmbeddedArtworkLoader
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bytes = loader.load(data.audioUri) ?: return null
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = embeddedArtworkMimeType(bytes),
            dataSource = DataSource.MEMORY
        )
    }

    class Factory(private val loader: EmbeddedArtworkLoader) : Fetcher.Factory<EmbeddedArtwork> {
        override fun create(
            data: EmbeddedArtwork,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = EmbeddedArtworkFetcher(data, options, loader)
    }
}

class EmbeddedArtworkKeyer : Keyer<EmbeddedArtwork> {
    override fun key(data: EmbeddedArtwork, options: Options): String {
        return "embedded-artwork:${data.audioUri}"
    }
}

fun Track.embeddedArtwork(): EmbeddedArtwork? {
    val source = localFilePath?.takeIf(String::isNotBlank) ?: audioUrl
    if (!shouldLoadEmbeddedArtwork(
            artworkSource = artworkSource,
            localFilePath = localFilePath,
            audioUrl = audioUrl,
            coverImageUrl = coverImageUrl
        )
    ) {
        return null
    }

    val parsed = source.toUri()
    val uri = if (parsed.scheme == null && source.startsWith(File.separator)) {
        Uri.fromFile(File(source))
    } else {
        parsed
    }
    return uri.takeIf { it.toString().isNotBlank() }?.let(::EmbeddedArtwork)
}

internal fun shouldLoadEmbeddedArtwork(
    artworkSource: String,
    localFilePath: String?,
    audioUrl: String,
    coverImageUrl: String = ""
): Boolean {
    val source = localFilePath?.takeIf(String::isNotBlank) ?: audioUrl
    if (source.isBlank()) return false
    if (localFilePath.isNullOrBlank() &&
        (source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true)) &&
        coverImageUrl.contains("/media/artwork/")
    ) {
        return false
    }
    return artworkSource.equals("embedded_audio", ignoreCase = true) ||
        !localFilePath.isNullOrBlank() ||
        source.startsWith("content://", ignoreCase = true) ||
        source.startsWith("file://", ignoreCase = true) ||
        source.startsWith(File.separator)
}

internal fun embeddedArtworkMimeType(bytes: ByteArray): String? {
    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()
    ) {
        return "image/jpeg"
    }
    if (bytes.size >= 8 &&
        bytes.sliceArray(0..7).contentEquals(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A
            )
        )
    ) {
        return "image/png"
    }
    if (bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
        bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
    ) {
        return "image/webp"
    }
    return null
}
