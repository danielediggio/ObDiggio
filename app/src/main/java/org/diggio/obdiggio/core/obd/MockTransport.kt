package org.diggio.obdiggio.core.obd

import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class MockTransport(private val hasDtcs: Boolean = false) : Transport() {

    private var opened = false
    private val t0 = System.nanoTime()

    override val isConnected: Boolean get() = opened

    override fun open() { opened = true }

    override fun close() { opened = false }

    override fun write(data: ByteArray) {
        check(opened) { "Trasporto simulato non aperto" }
        val command = String(data, Charsets.US_ASCII).trim().uppercase()
        feed((respond(command) + "\r\r>").toByteArray(Charsets.US_ASCII))
    }

    private fun elapsed() = (System.nanoTime() - t0) / 1e9

    private fun respond(command: String): String = when {
        command.startsWith("AT") -> respondAt(command)
        command.startsWith("02") -> respondMode02(command)
        command.startsWith("01") -> respondMode01(command)
        command == "03" -> if (hasDtcs) "43 02 01 33 04 20" else "43 00 00 00 00 00 00"
        command == "04" -> "44"
        else -> "NO DATA"
    }

    private fun respondAt(command: String): String = when (command) {
        "ATZ" -> "ELM327 v1.5"
        "ATRV" -> "%.1fV".format(Random.nextDouble(-0.2, 0.4) + 12.2)
        else -> "OK"
    }

    private fun frame02(pid: Int, vararg d: Int): String {
        val header = listOf("42", "%02X".format(pid), "00")
        return (header + d.map { "%02X".format(it) }).joinToString(" ")
    }

    private fun respondMode02(command: String): String {
        val pid = command.substring(2, 4).toIntOrNull(16) ?: return "NO DATA"
        return when (pid) {
            2  -> "42 02 00 01 04"
            4  -> frame02(pid, 198)
            5  -> frame02(pid, 132)
            11 -> frame02(pid, 158)
            12 -> frame02(pid, (7400 shr 8) and 0xFF, 7400 and 0xFF)
            13 -> frame02(pid, 62)
            14 -> frame02(pid, 128)
            15 -> frame02(pid, 75)
            16 -> frame02(pid, (1500 shr 8) and 0xFF, 1500 and 0xFF)
            17 -> frame02(pid, 107)
            31 -> frame02(pid, (320 shr 8) and 0xFF, 320 and 0xFF)
            51 -> frame02(pid, 101)
            else -> "NO DATA"
        }
    }

    private fun frame01(pid: Int, vararg d: Int): String {
        val header = listOf("41", "%02X".format(pid))
        return (header + d.map { "%02X".format(it) }).joinToString(" ")
    }

    private fun respondMode01(command: String): String {
        val pid = command.substring(2, 4).toIntOrNull(16) ?: return "NO DATA"
        val t = elapsed()
        return when (pid) {
            0  -> "41 00 18 3B 80 01"
            4  -> frame01(pid, (30 + (20 * (sin(t) * 0.5 + 0.5))).toInt())
            5  -> frame01(pid, min(215, (3 * sin(t / 5)).toInt() + 130))
            11 -> frame01(pid, (100 + (18 * (sin(t) * 0.5 + 0.5))).toInt())
            12 -> {
                val raw = ((800 + (1000 * (sin(t / 2) * 0.5 + 0.5))) * 4).toInt()
                frame01(pid, (raw shr 8) and 0xFF, raw and 0xFF)
            }
            13 -> frame01(pid, 0)
            15 -> frame01(pid, 70)
            16 -> {
                val raw = ((sin(t) * 0.5 + 2.0) * 100).toInt()
                frame01(pid, (raw shr 8) and 0xFF, raw and 0xFF)
            }
            17 -> frame01(pid, 38)
            32 -> "41 20 00 02 20 01"
            47 -> frame01(pid, 158)
            51 -> frame01(pid, 101)
            64 -> "41 40 44 80 00 00"
            66 -> {
                val raw = (12300 + Random.nextDouble(-100.0, 200.0)).toInt()
                frame01(pid, (raw shr 8) and 0xFF, raw and 0xFF)
            }
            70 -> frame01(pid, 62)
            73 -> frame01(pid, 30)
            else -> "NO DATA"
        }
    }
}
