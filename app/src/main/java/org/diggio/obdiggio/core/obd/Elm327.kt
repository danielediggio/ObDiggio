package org.diggio.obdiggio.core.obd

class Elm327(val transport: Transport, private val timeoutMs: Long = 5000) {

    private var initialized = false

    val isConnected: Boolean get() = initialized && transport.isConnected

    companion object {
        private val INIT_COMMANDS = listOf(
            "ATZ"   to 1500L,   // hardware reset — longer settle for reliable reinit
            "ATE0"  to 300L,    // echo off
            "ATL0"  to 300L,    // linefeeds off
            "ATS1"  to 300L,    // spaces on (easier parsing)
            "ATH0"  to 300L,    // headers off
            "ATSP0" to 500L     // auto-detect protocol
        )
        private val HEX = "0123456789ABCDEF".toSet()
        // Strings that indicate the ELM327 could not talk to the ECU
        private val NO_RESPONSE_TOKENS = setOf(
            "UNABLE", "ERROR", "NODATA", "BUSBUSY", "BUSERROR", "CANERROR", "STOPPED", "?"
        )

        fun clean(raw: String): String {
            return raw.replace(">", " ")
                .replace(Regex("[\\r\\n\\t]"), " ")
                .split(" ")
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .trim()
        }

        fun parseHexBytes(response: String): IntArray {
            val out = mutableListOf<Int>()
            val cleaned = response.uppercase().replace(":", " ")
            for (tok in cleaned.split(Regex("\\s+"))) {
                if (tok.isEmpty()) continue
                if (tok.all { it in HEX } && tok.length % 2 == 0) {
                    for (i in tok.indices step 2) {
                        out.add(tok.substring(i, i + 2).toInt(16))
                    }
                }
            }
            return out.toIntArray()
        }

        fun stripModeHeader(data: IntArray, mode: Int, pid: Int? = null): IntArray? {
            val responseMode = mode + 0x40
            for (i in data.indices) {
                if (data[i] == responseMode) {
                    val start = i + 1
                    return if (pid != null) {
                        if (start < data.size && data[start] == pid) data.copyOfRange(start + 1, data.size)
                        else continue
                    } else {
                        data.copyOfRange(start, data.size)
                    }
                }
            }
            return null
        }
    }

    // ---- Synchronised transport access ----------------------------------------
    // All OBD communication must go through sendRaw. The @Synchronized annotation
    // makes every call mutually exclusive on this Elm327 instance, so the polling
    // loop and a DTC read launched from a different coroutine can never interleave
    // their writes/reads on the shared BLE transport.

    @Synchronized
    private fun sendRaw(command: String): String {
        transport.write(("$command\r").toByteArray(Charsets.US_ASCII))
        val raw = transport.readUntil('>'.code.toByte(), timeoutMs)
        return String(raw, Charsets.US_ASCII)
    }

    fun query(command: String): String = clean(sendRaw(command))

    /**
     * Send [command] to a specific ECU identified by [txId] (physical CAN address).
     *
     * This is done atomically under the same lock as [sendRaw]:
     *   1. ATSH [txId]   — set outgoing CAN header (target ECU address)
     *   2. [command]\r   — send the diagnostic request
     *   3. read until '>' — collect ECU response
     *
     * The ELM327 automatically filters incoming frames to [txId + 8], which is
     * the standard physical response address (e.g. TX=0x7E0 → RX=0x7E8).
     *
     * ⚠ This leaves the adapter in physical-address mode. Call [resetToFunctional]
     * when you are done with ECU-specific queries so normal OBD-II polling works.
     */
    @Synchronized
    fun queryPhysical(txId: Int, command: String): String {
        transport.write("ATSH %03X\r".format(txId).toByteArray(Charsets.US_ASCII))
        transport.readUntil('>'.code.toByte(), 1000)          // wait for "OK\r\n>"
        transport.write(("$command\r").toByteArray(Charsets.US_ASCII))
        val raw = transport.readUntil('>'.code.toByte(), timeoutMs)
        return clean(String(raw, Charsets.US_ASCII))
    }

    /**
     * Restore functional addressing (ATSH 7DF) so Mode 01/03/07 queries
     * reach all ECUs again. Must be called after a physical-address session.
     */
    @Synchronized
    fun resetToFunctional() {
        transport.write("ATSH 7DF\r".toByteArray(Charsets.US_ASCII))
        transport.readUntil('>'.code.toByte(), 500)
    }

    // ---- Connection -----------------------------------------------------------

    fun connect() {
        if (!transport.isConnected) transport.open()
        initialize()
        initialized = true
        // Probe the OBD bus. If auto-detect (ATSP0) did not find a working protocol,
        // try the protocols most common on VAG / Audi (and most modern CAN cars):
        //   ATSP6 = ISO 15765-4 CAN 11-bit 500 kbaud  (Golf, A4, most petrol/diesel)
        //   ATSP7 = ISO 15765-4 CAN 29-bit 500 kbaud  (some VAG variants)
        //   ATSP5 = ISO 15765-4 CAN 11-bit 250 kbaud  (older / body-bus ECUs)
        val probe = runCatching { query("0100") }.getOrElse { "" }
        if (isNoResponse(probe)) {
            if (!tryForceProtocol("ATSP6") && !tryForceProtocol("ATSP7")) {
                tryForceProtocol("ATSP5")
            }
        }
    }

    private fun isNoResponse(response: String): Boolean {
        if (response.isBlank()) return true
        val upper = response.uppercase().replace(" ", "")
        return NO_RESPONSE_TOKENS.any { it in upper }
    }

    /** Send an ATSPx command and probe with 0100. Returns true if the ECU answered. */
    private fun tryForceProtocol(atCmd: String): Boolean {
        runCatching {
            sendRaw(atCmd)
            Thread.sleep(500)
        }
        val probe = runCatching { query("0100") }.getOrElse { "" }
        return !isNoResponse(probe)
    }

    fun initialize() {
        transport.clear()
        for ((cmd, settle) in INIT_COMMANDS) {
            sendRaw(cmd)
            Thread.sleep(settle)
        }
    }

    fun close() {
        initialized = false
        transport.close()
    }

    // ---- PID support ----------------------------------------------------------

    fun probeSupportedPids(): String = query("0100")

    fun supportedPids(): Set<Int> {
        val supported = mutableSetOf<Int>()
        var base = 0
        while (base <= 0xC0) {
            val data = stripModeHeader(parseHexBytes(query("01%02X".format(base))), 1, base)
            if (data == null || data.size < 4) break
            val bits = (data[0].toLong() shl 24) or (data[1].toLong() shl 16) or (data[2].toLong() shl 8) or data[3].toLong()
            for (i in 0 until 32) {
                if ((bits shr (31 - i)) and 1L == 1L) supported.add(base + i + 1)
            }
            val next = base + 32
            if (next !in supported) break
            base = next
        }
        return supported
    }

    fun readPid(pid: Pid): PidResult {
        val bytes = parseHexBytes(query(pid.command()))
        val data = stripModeHeader(bytes, 1, pid.code) ?: return PidResult(pid, null, pid.unit)
        return pid.decode(data)
    }

    // ---- DTC reading ----------------------------------------------------------

    fun readDtcs(): List<Dtc> = readDtcsForMode("03", 3)
    fun readPendingDtcs(): List<Dtc> = readDtcsForMode("07", 7)
    fun readPermanentDtcs(): List<Dtc> = readDtcsForMode("0A", 10)

    private fun readDtcsForMode(command: String, responseMode: Int): List<Dtc> {
        val raw = query(command)
        // Surface "NO DATA" / "UNABLE" as an empty list (no stored DTCs).
        // This is normal when the car has no faults.
        if (isNoResponse(raw)) return emptyList()
        val bytes = parseHexBytes(raw)
        val data = stripModeHeader(bytes, responseMode) ?: return emptyList()
        // After stripping the 0x43/0x47/0x4A response byte, some ECUs (especially
        // on K-line) prepend a DTC count byte. Since each DTC is exactly 2 bytes, an
        // odd-length payload means a leading count byte is present — strip it.
        val payload = if (data.size % 2 == 1) data.copyOfRange(1, data.size) else data
        return Dtc.decodeBytes(payload)
    }

    fun clearDtcs(): Boolean = parseHexBytes(query("04")).contains(0x44)

    // ---- Freeze frame ---------------------------------------------------------

    fun readFreezeFramePid(pid: Pid): PidResult {
        val bytes = parseHexBytes(query("02%02X00".format(pid.code)))
        val idx = bytes.indexOf(0x42)
        if (idx == -1 || idx + 2 >= bytes.size || bytes[idx + 1] != pid.code) {
            return PidResult(pid, null, pid.unit)
        }
        return pid.decode(bytes.copyOfRange(idx + 3, bytes.size))
    }

    fun readFreezeFrameDtc(): Dtc? {
        val bytes = parseHexBytes(query("020200"))
        val idx = bytes.indexOf(0x42)
        if (idx == -1 || idx + 4 >= bytes.size || bytes[idx + 1] != 2) return null
        return Dtc.decode(bytes[idx + 3], bytes[idx + 4])
    }

    // ---- Misc -----------------------------------------------------------------

    fun voltage(): Double? {
        val digits = query("ATRV").filter { it.isDigit() || it == '.' }
        return digits.toDoubleOrNull()
    }
}
