package io.aequicor.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopProcessTerminationTest {
    @Test
    fun fatalHandlerReportsAndTerminatesOnlyOnce() {
        val reports = mutableListOf<String>()
        val exitCodes = mutableListOf<Int>()
        val handler = DesktopFatalErrorHandler(
            report = { thread, failure -> reports += "${thread.name}:${failure.message}" },
            terminate = exitCodes::add,
        )
        val failingThread = Thread("fatal-test")

        handler.uncaughtException(failingThread, IllegalStateException("first"))
        handler.uncaughtException(failingThread, IllegalStateException("second"))

        assertEquals(listOf("fatal-test:first"), reports)
        assertEquals(listOf(1), exitCodes)
    }

    @Test
    fun forcedExitWatchdogTerminatesWithoutBlockingCaller() {
        val terminated = CountDownLatch(1)
        val exitCodes = mutableListOf<Int>()

        scheduleForcedDesktopExit(delaySeconds = 0) { code ->
            exitCodes += code
            terminated.countDown()
        }

        assertTrue(terminated.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(0), exitCodes)
    }
}
