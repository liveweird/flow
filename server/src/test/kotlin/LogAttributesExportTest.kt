package ch.nokillswit

import ch.nokillswit.audit.audit
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.status.Status
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `logback.xml`'s OTEL appender must keep exporting an audit line's key/values and the MDC as attributes — the
 * audit trail's fields only reach a collector that way (`audit/Audit.kt`). OpenTelemetry 2.33 deprecated the
 * capture settings the file used, with removal "in the next minor release"; a removed setting would drop the
 * fields silently, so this pins the attributes and fails on any WARN the appender raises while configuring.
 */
class LogAttributesExportTest {

    @Test
    fun `an audit line exports its key values, the MDC and the AUDIT marker as attributes`() {
        val records = CopyOnWriteArrayList<ReadWriteLogRecord>()
        val sdk = OpenTelemetrySdk.builder()
            .setLoggerProvider(
                SdkLoggerProvider.builder().addLogRecordProcessor(
                    object : LogRecordProcessor {
                        override fun onEmit(context: Context, logRecord: ReadWriteLogRecord) {
                            records.add(logRecord)
                        }
                    },
                ).build(),
            )
            .build()
        // Global, like configureOpenTelemetry's own call; the next application start installs its SDK again.
        OpenTelemetryAppender.install(sdk)
        try {
            MDC.put(MDC_KEY, "mdc-value")
            try {
                audit(EVENT, "email" to "attrs@flow.local")
            } finally {
                MDC.remove(MDC_KEY)
            }
            val attributes = records.single { it.toLogRecordData().bodyValue?.asString() == EVENT }
                .toLogRecordData().attributes
            assertEquals("attrs@flow.local", attributes.get(AttributeKey.stringKey("email")))
            assertEquals("mdc-value", attributes.get(AttributeKey.stringKey(MDC_KEY)))
            assertEquals(listOf("AUDIT"), attributes.get(AttributeKey.stringArrayKey("logback.marker")))
        } finally {
            sdk.close()
        }
    }

    @Test
    fun `the OTEL appender configures without warnings`() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val warnings = context.statusManager.copyOfStatusList
            .filter { it.level >= Status.WARN && it.origin.toString().contains("OpenTelemetryAppender") }
        assertTrue(warnings.isEmpty(), "logback.xml's OTEL appender warned: ${warnings.map { it.message }}")
    }

    private companion object {
        const val EVENT = "verify.log_attributes"
        const val MDC_KEY = "verifyMdcKey"
    }
}
