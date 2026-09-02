package org.diggio.obdiggio.core.vag

import org.diggio.obdiggio.core.obd.CanFrame
import org.diggio.obdiggio.core.obd.Elm327

data class VagDtcEntry(
    val code: String,
    val description: String,
    val statusByte: Int,
    val statusText: String,
    val rawHex: String
)

data class VagEcuResult(
    val ecu: VagEcu,
    val alive: Boolean,
    val dtcs: List<VagDtcEntry> = emptyList(),
    val identity: String? = null,
    val protocol: String = "TP2.0/KWP2000",
    val error: String? = null
)

/** Read-only C6 inventory over TP2.0 carrying KWP2000 messages. */
class VagDiag(private val elm: Elm327) {

    fun readDtcs(ecu: VagEcu): VagEcuResult = runCatching {
        elm.setupForRawCan()
        Tp20Channel.open(elm, ecu)?.use { channel ->
            channel.enterDiagnostics()
            VagEcuResult(ecu = ecu, alive = true, identity = channel.readIdentity())
        } ?: VagEcuResult(ecu = ecu, alive = false, error = "Nessuna risposta TP2.0")
    }.getOrElse { exception ->
        VagEcuResult(ecu = ecu, alive = false, error = exception.message ?: "Errore TP2.0")
    }

    fun readDtcsAll(ecus: List<VagEcu>): List<VagEcuResult> = ecus.map(::readDtcs)

    /** Clear diagnostic faults only. This never changes coding, adaptation or immobilizer data. */
    fun clearDtcs(ecu: VagEcu): Boolean = runCatching {
        elm.setupForRawCan()
        Tp20Channel.open(elm, ecu)?.use { channel ->
            channel.enterDiagnostics()
            channel.clearDiagnosticInformation()
        } ?: false
    }.getOrDefault(false)

    fun restoreFunctionalAddress() = elm.resetToFunctional()
}

private class Tp20Channel private constructor(
    private val elm: Elm327,
    private val requestId: Int,
    private val responseId: Int
) : AutoCloseable {
    private var txSequence = 0

    companion object {
        private const val SETUP_ID = 0x200

        fun open(elm: Elm327, ecu: VagEcu): Tp20Channel? {
            val setup = elm.queryCanFrame(
                txId = SETUP_ID,
                rxId = SETUP_ID + ecu.logicalAddress,
                payload = intArrayOf(ecu.logicalAddress, 0xC0, 0x00, 0x10, 0x00, 0x03, 0x01)
            ).firstOrNull()?.payload ?: return null
            if (setup.size < 7 || setup[1] != 0xD0) return null

            val requestId = setup[4] or ((setup[5] and 0x0F) shl 8)
            val responseId = setup[2] or ((setup[3] and 0x0F) shl 8)
            return Tp20Channel(elm, requestId, responseId).also { it.negotiateTiming() }
        }
    }

    private fun negotiateTiming() {
        val response = elm.queryCanFrame(requestId, responseId, intArrayOf(0xA0, 0x0F, 0x8A, 0xFF, 0x0A, 0xFF))
        check(response.any { it.payload.firstOrNull() == 0xA1 }) { "Parametri TP2.0 non accettati" }
    }

    fun enterDiagnostics() {
        val response = request(intArrayOf(0x10, 0x89))
        check(response.size >= 2 && response[0] == 0x50 && response[1] == 0x89) { "Sessione KWP2000 non disponibile" }
    }

    fun readIdentity(): String? {
        val response = request(intArrayOf(0x1A, 0x9B))
        if (response.size < 3 || response[0] != 0x5A || response[1] != 0x9B) return null
        return response.drop(2)
            .filter { it in 0x20..0x7E }
            .map { it.toChar() }
            .joinToString("")
            .trim()
            .ifBlank { null }
    }

    fun clearDiagnosticInformation(): Boolean {
        val response = request(intArrayOf(0x14, 0xFF, 0xFF, 0xFF))
        return response.firstOrNull() == 0x54
    }

    private fun request(payload: IntArray): IntArray {
        require(payload.size <= 5) { "La richiesta KWP2000 TP2.0 supera un frame" }
        val first = 0x10 or (txSequence and 0x0F)
        txSequence = (txSequence + 1) and 0x0F
        val frames = elm.queryCanFrame(
            txId = requestId,
            rxId = responseId,
            payload = intArrayOf(first, 0x00, payload.size) + payload,
            responseTimeoutMs = 2_000
        )
        return decodeResponse(frames)
    }

    private fun decodeResponse(frames: List<CanFrame>): IntArray {
        val dataFrames = frames.filter { frame ->
            val opcode = frame.payload.firstOrNull()?.shr(4) ?: -1
            opcode in 0..3
        }
        val first = dataFrames.firstOrNull() ?: return intArrayOf()
        if (first.payload.size < 4) return intArrayOf()
        val expectedLength = (first.payload[1] shl 8) or first.payload[2]
        val result = dataFrames.flatMapIndexed { index, frame ->
            if (index == 0) frame.payload.drop(3) else frame.payload.drop(1)
        }.take(expectedLength)
        val lastSequence = dataFrames.last().payload.first() and 0x0F
        elm.queryCanFrame(requestId, responseId, intArrayOf(0xB0 or ((lastSequence + 1) and 0x0F)), 300)
        return result.toIntArray()
    }

    override fun close() {
        runCatching { elm.queryCanFrame(requestId, responseId, intArrayOf(0xA8), 300) }
    }
}
