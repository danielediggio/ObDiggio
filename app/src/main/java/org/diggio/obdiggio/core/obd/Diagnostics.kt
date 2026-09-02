package org.diggio.obdiggio.core.obd

import java.time.Instant

data class AdapterProfile(
    val identifier: String,
    val description: String,
    val protocol: String,
    val protocolNumber: String,
    val voltage: Double?
)

data class VehicleSnapshot(
    val vin: String?,
    val calibrationId: String?,
    val supportedInfoPids: Set<Int>,
    val readinessRaw: String
)

data class DiagnosticTrace(
    val at: String = Instant.now().toString(),
    val command: String,
    val response: String
)

data class DiagnosticBundle(
    val createdAt: String,
    val adapter: AdapterProfile?,
    val vehicle: VehicleSnapshot?,
    val obdDtcs: List<Dtc>,
    val vagResults: List<String>,
    val liveValues: Map<String, PidResult>,
    val trace: List<DiagnosticTrace>
) {
    fun asText(): String = buildString {
        appendLine("OBDIGGIO DIAGNOSTIC BUNDLE")
        appendLine("created_at=$createdAt")
        appendLine()
        appendLine("[ADAPTER]")
        appendLine(adapter?.let {
            "id=${it.identifier}\ndescription=${it.description}\nprotocol=${it.protocol}\nprotocol_number=${it.protocolNumber}\nvoltage=${it.voltage ?: "n/a"}"
        } ?: "not_read")
        appendLine()
        appendLine("[VEHICLE]")
        appendLine(vehicle?.let {
            "vin=${it.vin ?: "not_available"}\ncalibration_id=${it.calibrationId ?: "not_available"}\nmode09_supported=${it.supportedInfoPids.joinToString { pid -> "%02X".format(pid) }}\nreadiness_raw=${it.readinessRaw}"
        } ?: "not_read")
        appendLine()
        appendLine("[OBD_DTC]")
        appendLine(obdDtcs.joinToString("\n") { "${it.code}: ${it.description}" }.ifBlank { "none" })
        appendLine()
        appendLine("[VAG_INVENTORY]")
        appendLine(vagResults.joinToString("\n").ifBlank { "not_scanned" })
        appendLine()
        appendLine("[LIVE_VALUES]")
        liveValues.values.forEach { value ->
            appendLine("${value.name}=${value.value ?: "n/a"} ${value.unit}")
        }
        appendLine()
        appendLine("[RAW_TRACE]")
        trace.forEach { event -> appendLine("${event.at} | ${event.command} | ${event.response}") }
    }
}
