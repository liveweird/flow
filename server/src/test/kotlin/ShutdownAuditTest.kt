package ch.nokillswit

import ch.nokillswit.plugins.TelemetryShutdown
import ch.nokillswit.plugins.subscribeTelemetryShutdown
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.testing.testApplication
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A graceful stop must not lose the audit lines emitted while Ktor is stopping (BACKLOG "Shutdown
 * audit lines are lost": `sync_job.released` vanished on graceful restarts). The cause lives between
 * JVM shutdown hooks — the OpenTelemetry SDK's own hook (closing the logs pipeline) used to run
 * concurrently with Ktor's stop hook (which fires `ApplicationStopping`), so a line emitted after
 * the SDK closed was silently dropped — which only a real JVM exit reproduces, hence a forked child
 * ([ShutdownAuditProbe]) that is sent SIGTERM and whose stdout (the console log exporter) is read.
 */
class ShutdownAuditTest {

    @Test
    fun `an audit line emitted during ApplicationStopping reaches the log on SIGTERM`() {
        val javaBin = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            javaBin, "-cp", System.getProperty("java.class.path"), ShutdownAuditProbe::class.java.name,
        ).redirectErrorStream(true).start()
        val output = StringBuffer()
        val engineUp = CountDownLatch(1)
        val reader = thread(isDaemon = true) {
            process.inputStream.bufferedReader().forEachLine { line ->
                output.appendLine(line)
                if (line.contains(ENGINE_UP)) engineUp.countDown()
            }
        }
        try {
            assertTrue(engineUp.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS), "probe never started:\n$output")
            // `kill -TERM`, NOT `Process.destroy()`: destroy() closes the parent's ends of the child's pipes first, so
            // the child's stop-time output has nowhere to go and the assertion would measure that instead.
            ProcessBuilder("kill", "-TERM", process.pid().toString()).inheritIO().start().waitFor()
            assertTrue(process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "probe did not exit:\n$output")
            reader.join(TimeUnit.SECONDS.toMillis(EXIT_TIMEOUT_SECONDS))
        } finally {
            process.destroyForcibly()
        }
        // The console exporter prints the record's body (the event name) — one line per record.
        val lines = output.lines().filter { it.contains(ShutdownAuditProbe.EVENT) }
        assertEquals(1, lines.size, "the stop-time audit line is missing from the log:\n$output")
    }

    @Test
    fun `the SDK close waits for ApplicationStopped once the application has started`() {
        val recorder = ShutdownRecorder()
        val shutdown = TelemetryShutdown(sdkWith(recorder))
        shutdown.markStarted()
        val hook = thread { shutdown.closeAfterStop() }
        awaitParked(hook)
        assertFalse(recorder.closed.get(), "the hook closed the SDK while Ktor was still stopping")
        shutdown.markStopped()
        hook.join(TimeUnit.SECONDS.toMillis(EXIT_TIMEOUT_SECONDS))
        assertFalse(hook.isAlive)
        assertTrue(recorder.closed.get())
    }

    @Test
    fun `the SDK outlives an ApplicationStopped handler registered after configureOpenTelemetry`() {
        val recorder = ShutdownRecorder()
        val shutdown = TelemetryShutdown(sdkWith(recorder))
        var closedWhenLaterHandlerFinished: Boolean? = null
        var hook: Thread? = null
        testApplication {
            application {
                subscribeTelemetryShutdown(shutdown)
                // A later module's stop work (Database's pool.dispose, Jira's client close — both log). Ktor raises
                // handlers in subscription order; the pause gives a hook released too early time to close the SDK.
                monitor.subscribe(ApplicationStopped) {
                    Thread.sleep(LATER_HANDLER_MS)
                    closedWhenLaterHandlerFinished = recorder.closed.get()
                }
            }
            startApplication()
            hook = thread { shutdown.closeAfterStop() }
            awaitParked(checkNotNull(hook))
        } // the block ends -> the application stops -> ApplicationStopped handlers run
        checkNotNull(hook).join(TimeUnit.SECONDS.toMillis(EXIT_TIMEOUT_SECONDS))
        assertEquals(false, closedWhenLaterHandlerFinished, "the SDK closed before a later ApplicationStopped handler ran")
        assertTrue(recorder.closed.get(), "the SDK was never closed after the application stopped")
    }

    @Test
    fun `a boot that never started closes the SDK at once - the failure's own log lines must still flush`() {
        val recorder = ShutdownRecorder()
        TelemetryShutdown(sdkWith(recorder)).closeAfterStop()
        assertTrue(recorder.closed.get())
    }

    /** A logs processor that only records that the SDK shut its pipeline down. */
    private class ShutdownRecorder : LogRecordProcessor {
        val closed = AtomicBoolean(false)

        override fun onEmit(context: Context, logRecord: ReadWriteLogRecord) = Unit

        override fun shutdown(): CompletableResultCode {
            closed.set(true)
            return CompletableResultCode.ofSuccess()
        }
    }

    /** Waits (bounded) until [hook] is blocked in the stop latch's `await` — deterministic, no sleep-and-hope. */
    private fun awaitParked(hook: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EXIT_TIMEOUT_SECONDS)
        while (hook.state != Thread.State.TIMED_WAITING && hook.state != Thread.State.WAITING) {
            assertTrue(System.nanoTime() < deadline && hook.isAlive, "the hook never parked on the stop latch")
            Thread.sleep(POLL_MS)
        }
    }

    private fun sdkWith(recorder: ShutdownRecorder): OpenTelemetrySdk = OpenTelemetrySdk.builder()
        .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(recorder).build())
        .build()

    private companion object {
        const val LATER_HANDLER_MS = 300L
        const val POLL_MS = 5L
        // Netty's "Responding at http://…" line: the engine is serving, so the SIGTERM is a real graceful stop.
        const val ENGINE_UP = "Responding at"
        const val STARTUP_TIMEOUT_SECONDS = 60L
        const val EXIT_TIMEOUT_SECONDS = 30L
    }
}
