package org.diggio.obdiggio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.diggio.obdiggio.ble.BleTransport
import org.diggio.obdiggio.core.obd.Dtc
import org.diggio.obdiggio.core.obd.Elm327
import org.diggio.obdiggio.core.obd.Pid
import org.diggio.obdiggio.core.obd.PidResult
import org.diggio.obdiggio.core.obd.MockTransport
import org.diggio.obdiggio.core.obd.Pids

private val DEFAULT_CODES    = listOf(12, 13, 5, 11, 4, 17, 15, 16, 66)
private val CANDIDATE_CODES  = listOf(12, 13, 11, 51, 5, 92, 15, 16, 4, 17, 73, 44, 45, 35, 47, 66, 70, 31, 33, 94)
private val FREEZE_CODES     = listOf(12, 13, 4, 5, 15, 16, 17, 11, 14, 51, 31)

data class UiState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val usingMock: Boolean = false,
    val status: String = "Non connesso",
    val values: Map<String, PidResult> = emptyMap(),
    val boostKpa: Double? = null,
    val dtcGroups: List<DtcGroup>? = null,
    val dtcBusy: Boolean = false,
    val freeze: FreezeFrame? = null,
    val freezeBusy: Boolean = false,
    val message: String? = null
)

data class DtcGroup(val label: String, val codes: List<Dtc>)

data class FreezeFrame(val dtc: Dtc?, val values: List<PidResult>)

class ObdViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    var dashboardPids: List<Pid> = DEFAULT_CODES.mapNotNull { Pids[it] }
        private set

    private var elm: Elm327? = null
    private var transport: BleTransport? = null
    private var pollJob: Job? = null

    // ---- Connection -----------------------------------------------------------

    fun connect(useMock: Boolean) {
        if (_state.value.connecting || _state.value.connected) return
        _state.update {
            it.copy(
                connecting = true, connected = false, usingMock = useMock,
                status = "Connessione…", values = emptyMap(), boostKpa = null,
                dtcGroups = null, dtcBusy = false, freeze = null, freezeBusy = false, message = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val t = if (useMock) MockTransport(hasDtcs = false) else {
                    BleTransport(getApplication()).also { ble ->
                        transport = ble
                        setStatus("Scansione BLE…")
                        ble.scan()
                        val dev = ble.devices.firstOrNull { it.looksLikeObd() }
                            ?: ble.devices.firstOrNull()
                            ?: error("Nessun dispositivo OBD trovato")
                        setStatus("Connessione a ${dev.name}…")
                        ble.select(dev)
                    }
                }
                val e = Elm327(t)
                elm = e
                e.connect()
                val supported = runCatching { e.supportedPids() }.getOrElse { emptySet() }
                val pids = if (supported.isEmpty()) {
                    DEFAULT_CODES.mapNotNull { Pids[it] }
                } else {
                    val candidates = CANDIDATE_CODES.filter { it in supported }
                    (if (candidates.isEmpty()) DEFAULT_CODES else candidates).mapNotNull { Pids[it] }
                }
                dashboardPids = pids
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(connecting = false, connected = true, status = "Connesso") }
                }
                startPolling()
            }.onFailure { ex ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(connecting = false, connected = false, status = "Errore: ${ex.message}") }
                }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { elm?.close() }
            elm = null
            withContext(Dispatchers.Main) {
                _state.update { UiState() }
            }
        }
    }

    // ---- Polling --------------------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            val e = elm ?: return@launch
            while (true) {
                val results = mutableMapOf<String, PidResult>()
                for (pid in dashboardPids) {
                    // sendRaw is @Synchronized — if a DTC read is in flight this
                    // call will simply block until that read finishes, so there is
                    // no interleaving. No explicit locking needed here.
                    runCatching { e.readPid(pid) }.getOrNull()?.let { results[pid.key] = it }
                }
                val boost = computeBoost(results)
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(values = results, boostKpa = boost) }
                }
                delay(500)
            }
        }
    }

    private fun computeBoost(values: Map<String, PidResult>): Double? {
        val map = values[Pids[11]?.key]?.value ?: return null
        val baro = values[Pids[51]?.key]?.value ?: 101.3
        return maxOf(map - baro, 0.0)
    }

    // ---- DTC operations -------------------------------------------------------
    // Pattern for every DTC / freeze operation:
    //   1. Cancel pollJob — avoids concurrent commands on the same transport.
    //      (sendRaw is also @Synchronized as a belt-and-suspenders guard.)
    //   2. Perform the operation.
    //   3. Restart pollJob when done (even on failure).
    // This makes DTC reads reliable regardless of where the poll loop was.

    fun readDtcs() {
        val e = elm ?: return
        pollJob?.cancel()
        pollJob = null
        _state.update { it.copy(dtcBusy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            // Give any in-flight poll command a moment to drain before we start.
            // (sendRaw times out after 5 s max, but normally finishes in <300 ms.)
            delay(250)
            runCatching {
                val groups = readAllDtcGroups(e)
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(dtcGroups = groups, dtcBusy = false) }
                }
            }.onFailure { ex ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(dtcBusy = false, message = "Errore lettura DTC: ${ex.message}") }
                }
            }
            startPolling()
        }
    }

    fun clearDtcs() {
        val e = elm ?: return
        pollJob?.cancel()
        pollJob = null
        _state.update { it.copy(dtcBusy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            runCatching {
                e.clearDtcs()
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(dtcGroups = emptyList(), dtcBusy = false, message = "DTC cancellati") }
                }
            }.onFailure { ex ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(dtcBusy = false, message = "Errore cancellazione DTC: ${ex.message}") }
                }
            }
            startPolling()
        }
    }

    fun readFreezeFrame() {
        val e = elm ?: return
        pollJob?.cancel()
        pollJob = null
        _state.update { it.copy(freezeBusy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            runCatching {
                val dtc = e.readFreezeFrameDtc()
                val pids = FREEZE_CODES.mapNotNull { Pids[it] }
                val values = pids.mapNotNull { runCatching { e.readFreezeFramePid(it) }.getOrNull() }
                FreezeFrame(dtc, values)
            }.fold(
                onSuccess = { frame ->
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(freeze = frame, freezeBusy = false) }
                    }
                },
                onFailure = { ex ->
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(freezeBusy = false, message = "Errore freeze frame: ${ex.message}") }
                    }
                }
            )
            startPolling()
        }
    }

    private fun readAllDtcGroups(e: Elm327): List<DtcGroup> {
        // Each mode is read separately. Failures are surfaced via state.message rather
        // than silently swallowed, while still returning whatever groups succeeded.
        val errors = mutableListOf<String>()
        fun safe(label: String, fn: () -> List<Dtc>): List<Dtc> =
            runCatching(fn).getOrElse { ex ->
                errors.add("$label: ${ex.message}")
                emptyList()
            }

        val groups = listOf(
            DtcGroup("Memorizzati",  safe("Mode 03") { e.readDtcs() }),
            DtcGroup("In sospeso",   safe("Mode 07") { e.readPendingDtcs() }),
            DtcGroup("Permanenti",   safe("Mode 0A") { e.readPermanentDtcs() })
        ).filter { it.codes.isNotEmpty() }

        if (errors.isNotEmpty()) {
            // Report in state so the user can see what went wrong
            val msg = errors.joinToString(" | ")
            viewModelScope.launch(Dispatchers.Main) {
                _state.update { it.copy(message = msg) }
            }
        }
        return groups
    }

    // ---- Helpers --------------------------------------------------------------

    private suspend fun setStatus(s: String) = withContext(Dispatchers.Main) {
        _state.update { it.copy(status = s) }
    }

    override fun onCleared() {
        pollJob?.cancel()
        runCatching { elm?.close() }
    }
}
