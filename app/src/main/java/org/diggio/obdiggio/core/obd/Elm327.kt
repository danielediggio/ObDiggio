package org.diggio.obdiggio.core.obd

class Elm327(val transport: Transport, private val timeoutMs: Long = 5000) {

    private var initialized = false

    val isConnected: Boolean get() = initialized && transport.isConnected

    companion object {
        private val INIT_COMMANDS = listOf(
            "ATZ" to 1000L,
            "ATE0" to 300L,
            "ATL0" to 300L,
            "ATS1" to 300L,
            "ATH0" to 300L,
            "ATSP0" to 300L
        )
        private val HEX = "0123456789ABCDEF".toSet()

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

    fun connect() {
        if (!transport.isConnected) transport.open()
        initialize()
        initialized = true
        runCatching { query("0100") }
    }

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

    fun close() {
        initialized = false
        transport.close()
    }

    fun initialize() {
        transport.clear()
        for ((cmd, settle) in INIT_COMMANDS) {
            sendRaw(cmd)
            Thread.sleep(settle)
        }
    }

    private fun sendRaw(command: String): String {
        transport.write(("$command\r").toByteArray(Charsets.US_ASCII))
        val raw = transport.readUntil('>', timeoutMs)
        return String(raw, Charsets.US_ASCII)
    }

    fun query(command: String): String = clean(sendRaw(command))

    fun readPid(pid: Pid): PidResult {
        val bytes = parseHexBytes(query(pid.command()))
        val data = stripModeHeader(bytes, 1, pid.code) ?: return PidResult(pid, null, pid.unit)
        return pid.decode(data)
    }

    fun readDtcs(): List<Dtc> = readDtcsForMode("03", 3)
    fun readPendingDtcs(): List<Dtc> = readDtcsForMode("07", 7)
    fun readPermanentDtcs(): List<Dtc> = readDtcsForMode("0A", 10)

    private fun readDtcsForMode(command: String, responseMode: Int): List<Dtc> {
        val bytes = parseHexBytes(query(command))
        val data = stripModeHeader(bytes, responseMode) ?: return emptyList()
        val payload = if (data.size % 2 == 1) data.copyOfRange(1, data.size) else data
        return Dtc.decodeBytes(payload)
    }

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

    fun clearDtcs(): Boolean = parseHexBytes(query("04")).contains(0x44)

    fun voltage(): Double? {
        val digits = query("ATRV").filter { it.isDigit() || it == '.' }
        return digits.toDoubleOrNull()
    }
}
