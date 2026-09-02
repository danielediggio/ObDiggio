package org.diggio.obdiggio.core.vag

import org.diggio.obdiggio.core.obd.Dtc
import org.diggio.obdiggio.core.obd.Elm327

// ── Data model ────────────────────────────────────────────────────────────────

data class VagDtcEntry(
    val code: String,
    val description: String,
    val statusByte: Int,
    val statusText: String,
    val rawHex: String          // original 3-byte hex for reference (e.g. "012F 01")
)

data class VagEcuResult(
    val ecu: VagEcu,
    val alive: Boolean,         // did the ECU respond at all?
    val dtcs: List<VagDtcEntry> = emptyList(),
    val error: String? = null
)

// ── Diagnostics class ─────────────────────────────────────────────────────────

/**
 * VAG-specific diagnostics over standard ELM327 / OBD-II port.
 *
 * Uses physical CAN addressing (ATSH) to interrogate individual ECUs with
 * UDS services instead of the generic OBD-II functional address (0x7DF).
 * This allows reading manufacturer-specific DTCs, including immobilizer
 * faults, gearbox codes, and other non-emission faults invisible to Mode 03.
 *
 * Session handling — ECUs with "additional protections" (e.g. Bosch EDC16 with
 * security coding, ZF TCU) often refuse service 0x19 in the DEFAULT session.
 * Before every DTC read we open an Extended Diagnostic Session (10 03); if the
 * ECU rejects even that we fall back gracefully and report the NRC code.
 *
 * All methods expect [pollJob] to be cancelled before being called so that
 * sendRaw() calls don't interleave. Elm327.queryPhysical() is @Synchronized
 * as an additional safeguard.
 */
class VagDiag(private val elm: Elm327) {

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Scan all known VAG ECUs. Returns one [VagEcuResult] per ECU.
     * The probe is a lightweight UDS TesterPresent (3E 00) which every
     * UDS-capable ECU must answer — much faster than a full DTC read.
     * Unreachable ECUs (NO DATA / timeout) are marked alive=false.
     */
    fun scanEcus(): List<VagEcuResult> {
        // Force ATSP6 (500kbaud CAN) before any physical-address query.
        // Auto-detect (ATSP0) may fail when the engine won't start and Mode-01
        // goes unanswered; without this the adapter could be on the wrong protocol.
        elm.setupForVagCan()
        return VagEcus.all.map { ecu ->
            runCatching {
                val resp = elm.queryPhysical(ecu.txId, ecu.rxId, "3E 00")
                val alive = isPositiveResponse(resp, 0x3E)
                VagEcuResult(ecu = ecu, alive = alive,
                    error = if (!alive) "Nessuna risposta" else null)
            }.getOrElse { ex ->
                VagEcuResult(ecu = ecu, alive = false, error = ex.message)
            }
        }
    }

    /**
     * Read DTCs from a single ECU.
     *
     * Protocol sequence:
     *   1. TesterPresent (3E 00)  — verify the ECU is alive
     *   2. Extended Diagnostic Session (10 03)  — unlock service 0x19 on
     *      "protected" ECUs (Bosch EDC16, ZF 6HP, immobilizer ECUs).
     *      If the ECU stays in default session it still answers 0x19, so
     *      this step is safe even for ECUs that don't need it.
     *   3. ReadDTCByStatusMask (19 02 FF)  — all DTCs regardless of status
     *
     * Returns [VagEcuResult] with [alive]=true on success or meaningful
     * error descriptions including UDS NRC codes on failure.
     */
    fun readDtcs(ecu: VagEcu): VagEcuResult =
        runCatching {
            // Ensure correct protocol for standalone DTC reads (in case called without scanEcus)
            elm.setupForVagCan()

            // Step 1 — probe
            val probe = elm.queryPhysical(ecu.txId, ecu.rxId, "3E 00")
            if (!isPositiveResponse(probe, 0x3E)) {
                val nrc = extractNrc(probe, 0x3E)
                return@runCatching VagEcuResult(
                    ecu = ecu, alive = false,
                    error = if (nrc != null) "ECU non risponde (NRC $nrc)" else "Nessuna risposta"
                )
            }

            // Step 2 — open extended session (needed for ECUs with additional protections)
            // We ignore the response: if the ECU doesn't support it, it returns 7F and we
            // still try the DTC read — many ECUs answer 0x19 in default session anyway.
            elm.queryPhysical(ecu.txId, ecu.rxId, "10 03")

            // Step 3 — read DTCs
            val resp = elm.queryPhysical(ecu.txId, ecu.rxId, "19 02 FF")
            if (!isPositiveResponse(resp, 0x19)) {
                val nrc = extractNrc(resp, 0x19)
                val errMsg = when (nrc) {
                    "22" -> "Accesso negato: centralina in condizioni non corrette (NRC 22) — motore acceso?"
                    "33" -> "Accesso negato: sicurezza ECU attiva (NRC 33) — protezioni aggiuntive"
                    "35" -> "Chiave di sicurezza non valida (NRC 35)"
                    "31" -> "Servizio non supportato in questa sessione (NRC 31)"
                    "11" -> "Servizio UDS 19h non supportato da questa ECU (NRC 11)"
                    null -> "Nessuna risposta UDS dalla ECU"
                    else -> "Risposta negativa ECU (NRC $nrc)"
                }
                return@runCatching VagEcuResult(ecu = ecu, alive = true, error = errMsg)
            }

            val bytes = Elm327.parseHexBytes(resp)
            val dtcs  = parseUdsDtcs(bytes)
            VagEcuResult(ecu = ecu, alive = true, dtcs = dtcs)
        }.getOrElse { ex ->
            VagEcuResult(ecu = ecu, alive = false, error = ex.message)
        }

    /** Read DTCs from every ECU in [ecus] in sequence. */
    fun readDtcsAll(ecus: List<VagEcu>): List<VagEcuResult> = ecus.map { readDtcs(it) }

    /**
     * Clear DTCs on a single ECU using UDS service 0x14 (ClearDiagnosticInformation).
     * Opens an extended session first (same reason as [readDtcs]).
     * Returns true if the ECU answered with a positive response (0x54).
     */
    fun clearDtcs(ecu: VagEcu): Boolean =
        runCatching {
            elm.queryPhysical(ecu.txId, ecu.rxId, "10 03")   // extended session
            val resp = elm.queryPhysical(ecu.txId, ecu.rxId, "14 FF FF FF")
            isPositiveResponse(resp, 0x14)
        }.getOrElse { false }

    /**
     * Must be called after all physical-address queries to restore the
     * ELM327 to functional addressing (0x7DF) so normal OBD-II polling works.
     */
    fun restoreFunctionalAddress() = elm.resetToFunctional()

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Check that [response] contains a positive-response byte for [service]
     * (positive = serviceId + 0x40). Filters out NO DATA, ERROR, etc.
     */
    private fun isPositiveResponse(response: String, service: Int): Boolean {
        if (response.isBlank()) return false
        val upper = response.uppercase().replace(" ", "")
        val noResponse = listOf("NODATA", "ERROR", "UNABLE", "STOPPED", "CANERROR", "BUSERROR", "BUSBUSY")
        if (noResponse.any { it in upper }) return false
        val positiveHex = "%02X".format(service + 0x40)
        return upper.contains(positiveHex)
    }

    /**
     * If [response] is a UDS negative response (7F serviceId NRC), return the
     * two-character hex NRC string (e.g. "33" for securityAccessDenied).
     * Returns null if the response is not a recognisable negative response.
     *
     * UDS negative response format: 7F <echo of service byte> <NRC byte>
     */
    private fun extractNrc(response: String, service: Int): String? {
        val bytes = Elm327.parseHexBytes(response)
        // Find the 0x7F marker
        val idx = bytes.indexOf(0x7F)
        if (idx == -1 || idx + 2 >= bytes.size) return null
        if (bytes[idx + 1] != service) return null
        return "%02X".format(bytes[idx + 2])
    }

    /**
     * Parse raw UDS response bytes for service 0x19 subfunction 0x02.
     *
     * Response layout:
     *   59 02 <statusAvailabilityMask> [dtcHighByte dtcMidByte dtcLowByte statusByte] ...
     *
     * Each DTC is 3 data bytes + 1 status byte = 4 bytes per entry.
     * The 3-byte DTC code encodes the standard OBD-II fault in the first
     * two bytes (same bit layout as Mode 03); the third byte is an
     * implementation-specific sub-code (0x00 for generic codes).
     */
    private fun parseUdsDtcs(bytes: IntArray): List<VagDtcEntry> {
        val startIdx = bytes.indexOf(0x59)
        if (startIdx == -1 || startIdx + 3 > bytes.size) return emptyList()

        // Skip: 0x59 (service echo) + 0x02 (subfunc echo) + mask byte = 3 bytes
        var i = startIdx + 3
        val result = mutableListOf<VagDtcEntry>()

        while (i + 3 < bytes.size) {
            val dtcH   = bytes[i]
            val dtcM   = bytes[i + 1]
            val dtcL   = bytes[i + 2]   // sub-code / 0x00 for standard codes
            val status = bytes[i + 3]
            i += 4

            if (dtcH == 0 && dtcM == 0 && dtcL == 0) continue

            val decoded = Dtc.decode(dtcH, dtcM)
            val code    = decoded?.code ?: "RAW:%02X%02X%02X".format(dtcH, dtcM, dtcL)
            val desc    = decoded?.description ?: Dtc.describe(code)
            val rawHex  = if (dtcL != 0) "%02X%02X%02X/%02X".format(dtcH, dtcM, dtcL, status)
                          else            "%02X%02X/%02X".format(dtcH, dtcM, status)

            result.add(VagDtcEntry(code, desc, status, decodeStatusByte(status), rawHex))
        }
        return result
    }

    /**
     * Decode the UDS DTC status byte (ISO 14229-1 Table D.1).
     *
     * Bit 7: warningIndicatorRequested  → MIL accesa
     * Bit 5: testFailedSinceLastClear   → presente dopo reset
     * Bit 3: confirmedDTC               → confermato
     * Bit 2: pendingDTC                 → in sospeso
     * Bit 0: testFailed                 → attivo ora
     */
    private fun decodeStatusByte(status: Int): String {
        val parts = mutableListOf<String>()
        if (status and 0x01 != 0) parts.add("attivo")
        if (status and 0x04 != 0) parts.add("in sospeso")
        if (status and 0x08 != 0) parts.add("confermato")
        if (status and 0x20 != 0) parts.add("dopo reset")
        if (status and 0x80 != 0) parts.add("MIL accesa")
        return if (parts.isEmpty()) "storico" else parts.joinToString(" · ")
    }
}
