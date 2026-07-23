package com.example.ui.notification

internal fun interface NotificationTimerHandle {
    fun cancel()
}

/**
 * Owns the lifetime of one transient notification.
 *
 * Showing another notification (including an equal one) replaces the pending timeout. The
 * generation check also prevents a cancelled timer that is already completing from dismissing a
 * newer notification.
 */
internal class AutoDismissNotificationController<T>(
    private val durationMillis: Long,
    private val scheduleDismiss: (delayMillis: Long, onElapsed: () -> Unit) -> NotificationTimerHandle,
    private val onNotificationChanged: (T?) -> Unit,
) {
    private var timer: NotificationTimerHandle? = null
    private var generation = 0L
    private var isDisposed = false

    fun show(notification: T) {
        if (isDisposed) return

        val notificationGeneration = ++generation
        timer?.cancel()
        onNotificationChanged(notification)
        timer = scheduleDismiss(durationMillis) {
            if (!isDisposed && generation == notificationGeneration) {
                timer = null
                onNotificationChanged(null)
            }
        }
    }

    fun dismiss() {
        if (isDisposed) return

        generation++
        timer?.cancel()
        timer = null
        onNotificationChanged(null)
    }

    fun dispose() {
        if (isDisposed) return

        isDisposed = true
        generation++
        timer?.cancel()
        timer = null
    }
}
