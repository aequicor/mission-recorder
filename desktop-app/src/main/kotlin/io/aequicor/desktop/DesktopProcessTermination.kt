package io.aequicor.desktop

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

internal class DesktopFatalErrorHandler(
    private val report: (Thread, Throwable) -> Unit,
    private val terminate: (Int) -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val terminating = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, failure: Throwable) {
        if (!terminating.compareAndSet(false, true)) {
            return
        }
        runCatching { report(thread, failure) }
        terminate(FATAL_EXIT_CODE)
    }
}

internal fun installDesktopFatalErrorHandler() {
    Thread.setDefaultUncaughtExceptionHandler(
        DesktopFatalErrorHandler(
            report = { thread, failure ->
                System.err.println("Fatal desktop error on thread ${thread.name}: ${failure.message}")
                failure.printStackTrace(System.err)
            },
            terminate = ::exitProcess,
        ),
    )
}

internal fun scheduleForcedDesktopExit(
    delaySeconds: Long = FORCED_EXIT_DELAY_SECONDS,
    terminate: (Int) -> Unit = ::exitProcess,
) {
    val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mission-recorder-exit-watchdog").apply { isDaemon = true }
    }
    scheduler.schedule(
        {
            try {
                terminate(NORMAL_EXIT_CODE)
            } finally {
                scheduler.shutdown()
            }
        },
        delaySeconds,
        TimeUnit.SECONDS,
    )
    scheduler.shutdown()
}

private const val NORMAL_EXIT_CODE = 0
private const val FATAL_EXIT_CODE = 1
private const val FORCED_EXIT_DELAY_SECONDS = 5L
