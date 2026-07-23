package com.example.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDismissNotificationControllerTest {

    @Test
    fun notificationIsRemovedWhenItsTimerExpires() {
        val timer = FakeNotificationTimer()
        val displayed = mutableListOf<String?>()
        val controller = controller(timer, displayed)

        controller.show("first")

        assertEquals("first", displayed.last())
        assertEquals(4_500L, timer.tasks.single().delayMillis)

        timer.tasks.single().elapse()

        assertNull(displayed.last())
    }

    @Test
    fun showingSameNotificationAgainReplacesPreviousTimer() {
        val timer = FakeNotificationTimer()
        val displayed = mutableListOf<String?>()
        val controller = controller(timer, displayed)

        controller.show("same")
        val firstTimer = timer.tasks.single()
        controller.show("same")
        val replacementTimer = timer.tasks.last()

        assertTrue(firstTimer.cancelled)
        firstTimer.elapse()
        assertEquals("same", displayed.last())

        replacementTimer.elapse()
        assertNull(displayed.last())
    }

    @Test
    fun newerNotificationCannotBeDismissedByPreviousTimer() {
        val timer = FakeNotificationTimer()
        val displayed = mutableListOf<String?>()
        val controller = controller(timer, displayed)

        controller.show("first")
        val firstTimer = timer.tasks.single()
        controller.show("second")
        val secondTimer = timer.tasks.last()

        firstTimer.elapse()
        assertEquals("second", displayed.last())

        secondTimer.elapse()
        assertNull(displayed.last())
    }

    @Test
    fun manualDismissCancelsTimerAndRemovesNotification() {
        val timer = FakeNotificationTimer()
        val displayed = mutableListOf<String?>()
        val controller = controller(timer, displayed)

        controller.show("first")
        val pendingTimer = timer.tasks.single()
        controller.dismiss()

        assertTrue(pendingTimer.cancelled)
        assertNull(displayed.last())

        pendingTimer.elapse()
        assertNull(displayed.last())
    }

    @Test
    fun disposeCancelsTimerWithoutUpdatingUiAgain() {
        val timer = FakeNotificationTimer()
        val displayed = mutableListOf<String?>()
        val controller = controller(timer, displayed)

        controller.show("first")
        val pendingTimer = timer.tasks.single()
        val updatesBeforeDispose = displayed.size
        controller.dispose()

        assertTrue(pendingTimer.cancelled)
        pendingTimer.elapse()
        assertEquals(updatesBeforeDispose, displayed.size)
    }

    private fun controller(
        timer: FakeNotificationTimer,
        displayed: MutableList<String?>,
    ) = AutoDismissNotificationController(
        durationMillis = 4_500L,
        scheduleDismiss = timer::schedule,
        onNotificationChanged = displayed::add,
    )

    private class FakeNotificationTimer {
        val tasks = mutableListOf<Task>()

        fun schedule(delayMillis: Long, onElapsed: () -> Unit): NotificationTimerHandle {
            val task = Task(delayMillis, onElapsed)
            tasks += task
            return NotificationTimerHandle { task.cancelled = true }
        }

        class Task(
            val delayMillis: Long,
            private val onElapsed: () -> Unit,
        ) {
            var cancelled = false

            // Intentionally invokes even after cancellation to exercise the controller's
            // stale-callback protection for timers that race with cancellation.
            fun elapse() = onElapsed()
        }
    }
}
