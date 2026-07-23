package com.example.domain.model

sealed interface BackendConnection {
    val message: String

    data object Discovering : BackendConnection {
        override val message = "Looking for a Kipotify server on this network…"
    }

    data class Connected(val endpoint: String) : BackendConnection {
        override val message = "Connected to local server $endpoint"
    }

    data class Reconnecting(val endpoint: String?) : BackendConnection {
        override val message = endpoint?.let { "Reconnecting to local server $it…" }
            ?: "Reconnecting to a local server…"
    }

    data class Fallback(val reason: String) : BackendConnection {
        override val message = reason
    }

    data class Unavailable(val reason: String) : BackendConnection {
        override val message = reason
    }
}

data class BackendConnectionNotice(
    val id: Long,
    val connection: BackendConnection,
)
