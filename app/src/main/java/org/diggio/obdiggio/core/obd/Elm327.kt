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
     * Force ISO 15765-4 CAN 500kbaud (ATSP6) and tune the adapter for VAG
     * physical-address UDS queries. Must be called once before any [queryPhysical]
     * session so the adapter is on the right bus even when auto-detect (ATSP0) failed.
     *
     * Settings applied:
     *   ATSP6  — ISO 15765-4 CAN 11-bit 500kbaud (VAG high-speed CAN bus)
     *   ATCAF1 — CAN auto-formatting ON (ELM327 reassembles multi-frame responses)
     *   ATAT2  — adaptive timing mode 2 (less aggressive wait, more reliable)
     *   ATST19 — byte timeout = 25 × 4ms = 100ms per CAN frame (suits CAN ECUs)
     */
    @Synchronized
    fun setupForVagCan() {
        sendRaw("ATSP6")        // force ISO 15765-4 CAN 11-bit 500kbaud
        Thread.sleep(100)
        sendRaw("ATCAF1")       // auto-format: ELM327 handles ISO 15765-4 transport
        sendRaw("ATAT2")        // adaptive timing aggressive
        sendRaw("ATST19")       // per-frame timeout ~100ms
    }

    /**
     * Send [command] to a specific ECU identified by [txId] (physical CAN address).
     * [rxId] is the expected response address (normally txId + 8); passing it enables
     * explicit CAN receive filtering via ATCRA, which is required for many cheap
     * ELM327 clones that do not implement the automatic txId+8 filter.
     *
     * This is done atomically under the same lock as [sendRaw]:
     *   1. ATSH [txId]   — set outgoing CAN header (target ECU address)
     *   2. ATCRA [rxId]  — filter incoming frames to the ECU's response address
     *   3. [command]\r   — send the diagnostic request
     *   4. read until '>' — collect ECU response
     *
     * ⚠ This leaves the adapter in physical-address mode. Call [resetToFunctional]
     * when you are done with ECU-specific queries so normal OBD-II polling works.
     */
    @Synchronized
    fun queryPhysical(txId: Int, rxId: Int, command: String): String {
        transport.write("ATSH %03X\r".format(txId).toByteArray(Charsets.US_ASCII))
        transport.readUntil('>'.code.toByte(), 500)
        transport.write("ATCRA %03X\r".format(rxId).toByteArray(Charsets.US_ASCII))
        transport.readUntil('>'.code.toByte(), 500)
        transport.write(("$command\r").toByteArray(Charsets.US_ASCII))
        val raw = transport.readUntil('>'.code.toByte(), timeoutMs)
        return clean(String(raw, Charsets.US_ASCII))
    }

    /** Backwards-compatible overload: derives rxId as txId + 8. */
    @Synchronized
    fun queryPhysical(txId: Int, command: String): String =
        queryPhysical(txId, txId + 8, command)

    /**
     * Restore functional addressing (ATSH 7DF) and clear the CAN receive filter
     * (ATCRA) so Mode 01/03/07 queries reach all ECUs again.
     * Must be called after a physical-address session.
     */
    @Synchronized
    fun resetToFunctional() {
        transport.write("ATCRA\r".toByteArray(Charsets.US_ASCII))  // clear ATCRA filter
        transport.readUntil('>'.code.toByte(), 500)
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
