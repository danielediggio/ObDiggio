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
import org.diggio.obdiggio.ble.BleDevice
import org.diggio.obdiggio.ble.AdapterConnection
import org.diggio.obdiggio.ble.ClassicBluetoothTransport
import org.diggio.obdiggio.core.obd.AdapterProfile
import org.diggio.obdiggio.core.obd.DiagnosticBundle
import org.diggio.obdiggio.core.obd.Dtc
import org.diggio.obdiggio.core.obd.Elm327
import org.diggio.obdiggio.core.obd.Pid
import org.diggio.obdiggio.core.obd.PidResult
import org.diggio.obdiggio.core.obd.MockTransport
import org.diggio.obdiggio.core.obd.Pids
import org.diggio.obdiggio.core.obd.VehicleSnapshot
import org.diggio.obdiggio.core.vag.VagDiag
import org.diggio.obdiggio.core.vag.VagEcuResult
import org.diggio.obdiggio.core.vag.VagEcus

private val DEFAULT_CODES    = listOf(12, 13, 5, 11, 4, 17, 15, 16, 66)
private val CANDIDATE_CODES  = listOf(12, 13, 11, 51, 5, 92, 15, 16, 4, 17, 73, 44, 45, 35, 47, 66, 70, 31, 33, 94)
private val FREEZE_CODES     = listOf(12, 13, 4, 5, 15, 16, 17, 11, 14, 51, 31)

data class UiState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val usingMock: Boolean = false,
    val status: String = "Non connesso",
    val deviceScanBusy: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val adapter: AdapterProfile? = null,
    val vehicle: VehicleSnapshot? = null,
    val resetBackupAvailable: Boolean = false,
    val values: Map<String, PidResult> = emptyMap(),
    val boostKpa: Double? = null,
    // ── Standard OBD-II DTC ───────────────────────────────────────────────────
    val dtcGroups: List<DtcGroup>? = null,
    val dtcBusy: Boolean = false,
    // ── Freeze frame ──────────────────────────────────────────────────────────
    val freeze: FreezeFrame? = null,
    val freezeBusy: Boolean = false,
    // ── VAG multi-ECU scan ────────────────────────────────────────────────────
    val vagResults: List<VagEcuResult>? = null,  // null = not yet scanned
    val vagBusy: Boolean = false,
    val vagProgress: String = "",                // e.g. "Lettura Motore…"
    // ── Generic message / error ───────────────────────────────────────────────
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
    private var bleTransport: BleTransport? = null
    private var pollJob: Job? = null
    private var lastPreResetBundle: String? = null

    // ── Connection ────────────────────────────────────────────────────────────

    fun scanDevices() {
        if (_state.value.deviceScanBusy || _state.value.connecting || _state.value.connected) return
        _state.update { it.copy(deviceScanBusy = true, devices = emptyList(), message = null, status = "Scansione BLE + Bluetooth associati…") }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ble = BleTransport(getApplication())
                val devices = (ble.scan() + ClassicBluetoothTransport.bondedDevices(getApplication()))
                    .distinctBy { it.address }
                bleTransport = ble
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(deviceScanBusy = false, devices = devices, status = "${devices.size} adattatori trovati") }
                }
            }.onFailure { exception ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(deviceScanBusy = false, status = "Errore ricerca adattatori: ${exception.message}") }
                }
            }
        }
    }

    fun connect(device: BleDevice? = null, useMock: Boolean = false) {
        if (_state.value.connecting || _state.value.connected) return
        _state.update {
            it.copy(
                connecting = true, connected = false, usingMock = useMock,
                status = "Connessione…", values = emptyMap(), boostKpa = null,
                dtcGroups = null, dtcBusy = false, freeze = null, freezeBusy = false,
                vagResults = null, vagBusy = false, message = null, adapter = null, vehicle = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val t = if (useMock) MockTransport(hasDtcs = false) else {
                    val selected = device ?: error("Seleziona un adattatore BLE")
                    setStatus("Connessione a ${selected.name}…")
                    when (selected.connection) {
                        AdapterConnection.BLE -> {
                            val ble = bleTransport ?: error("Prima cerca un adattatore BLE")
                            ble.select(selected)
                            ble
                        }
                        AdapterConnection.CLASSIC -> ClassicBluetoothTransport(getApplication(), selected.address)
                    }
                }
                val e = Elm327(t)
                elm = e
                e.connect()
                val adapter = runCatching { e.readAdapterProfile() }.getOrNull()
                val vehicle = runCatching { e.readVehicleSnapshot() }.getOrNull()
                val supported = runCatching { e.supportedPids() }.getOrElse { emptySet() }
                val pids = if (supported.isEmpty()) {
                    DEFAULT_CODES.mapNotNull { Pids[it] }
                } else {
                    val candidates = CANDIDATE_CODES.filter { it in supported }
                    (if (candidates.isEmpty()) DEFAULT_CODES else candidates).mapNotNull { Pids[it] }
                }
                dashboardPids = pids
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(connecting = false, connected = true, status = "Connesso", adapter = adapter, vehicle = vehicle) }
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
            bleTransport = null
            withContext(Dispatchers.Main) { _state.update { UiState() } }
        }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            val e = elm ?: return@launch
            while (true) {
                val results = mutableMapOf<String, PidResult>()
                for (pid in dashboardPids) {
                    // sendRaw is @Synchronized — if a DTC / VAG read is in flight
                    // this call will simply block until that read finishes.
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
        val map  = values[Pids[11]?.key]?.value ?: return null
        val baro = values[Pids[51]?.key]?.value ?: 101.3
        return maxOf(map - baro, 0.0)
    }

    // ── Standard OBD-II DTC operations ───────────────────────────────────────
    // Pattern: cancel poll → wait → operate → restart poll (even on failure).

    fun readDtcs() {
        val e = elm ?: return
        pollJob?.cancel(); pollJob = null
        _state.update { it.copy(dtcBusy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
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
        pollJob?.cancel(); pollJob = null
        _state.update { it.copy(dtcBusy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            runCatching {
                e.clearDtcs()
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(dtcGroups = emptyList(), dtcBusy = false, message = "DTC OBD-II cancellati") }
                }
            }.onFailure { ex ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(dtcBusy = false, message = "Errore cancellazione: ${ex.message}") }
                }
            }
            startPolling()
        }
    }

    fun readFreezeFrame() {
        val e = elm ?: return
        pollJob?.cancel(); pollJob = null
        _state.update { it.copy(freezeBusy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            runCatching {
                val dtc    = e.readFreezeFrameDtc()
                val pids   = FREEZE_CODES.mapNotNull { Pids[it] }
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
        val errors = mutableListOf<String>()
        fun safe(label: String, fn: () -> List<Dtc>): List<Dtc> =
            runCatching(fn).getOrElse { ex -> errors.add("$label: ${ex.message}"); emptyList() }

        val groups = listOf(
            DtcGroup("Memorizzati",  safe("Mode 03") { e.readDtcs() }),
            DtcGroup("In sospeso",   safe("Mode 07") { e.readPendingDtcs() }),
            DtcGroup("Permanenti",   safe("Mode 0A") { e.readPermanentDtcs() })
        ).filter { it.codes.isNotEmpty() }

        if (errors.isNotEmpty()) {
            val msg = errors.joinToString(" | ")
            viewModelScope.launch(Dispatchers.Main) { _state.update { it.copy(message = msg) } }
        }
        return groups
    }

    // ── VAG multi-ECU operations ──────────────────────────────────────────────

    /**
     * Scan known C6 logical addresses, open a TP2.0 channel and read ECU identity.
     *
     * Flow:
     *   1. Cancel poll job (exclusive transport access)
     *   2. For each ECU: open the TP2.0/KWP2000 channel and read its identity
     *   3. Update state incrementally after each ECU so the UI shows progress
     *   4. Restore functional addressing (ATSH 7DF)
     *   5. Restart poll job
     */
    fun scanVag() {
        val e = elm ?: return
        pollJob?.cancel(); pollJob = null
        _state.update { it.copy(vagBusy = true, vagResults = emptyList(), vagProgress = "Avvio scan…", message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            delay(250) // let in-flight poll drain
            val diag = VagDiag(e)
            val collected = mutableListOf<VagEcuResult>()
            for (ecu in VagEcus.all) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(vagProgress = "Lettura ${ecu.name}…") }
                }
                val result = diag.readDtcs(ecu)
                collected.add(result)
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(vagResults = collected.toList()) }
                }
            }
            runCatching { diag.restoreFunctionalAddress() }
            val online = collected.count { it.alive }
            val msg = "Inventario C6 completato — $online/${collected.size} ECU identificate"
            withContext(Dispatchers.Main) {
                _state.update { it.copy(vagBusy = false, vagProgress = "", message = msg) }
            }
            startPolling()
        }
    }

    /**
     * Clears DTC memory only, after saving an in-app diagnostic snapshot.
     */
    fun clearVagDtcs() {
        val e = elm ?: return
        val onlineEcus = _state.value.vagResults.orEmpty().filter { it.alive }
        if (onlineEcus.isEmpty()) {
            _state.update { it.copy(message = "Prima esegui C6 INVENTORY: servono ECU online da resettare") }
            return
        }
        lastPreResetBundle = diagnosticBundle()
        pollJob?.cancel(); pollJob = null
        _state.update { it.copy(vagBusy = true, vagProgress = "Backup salvato - reset DTC in corso…", message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            delay(250)
            val obdCleared = runCatching { e.clearDtcs() }.getOrDefault(false)
            val diag = VagDiag(e)
            var cleared = 0
            for (result in onlineEcus) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(vagProgress = "Reset DTC ${result.ecu.name}…") }
                }
                if (diag.clearDtcs(result.ecu)) cleared++
            }
            runCatching { diag.restoreFunctionalAddress() }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        vagBusy = false,
                        vagProgress = "",
                        dtcGroups = null,
                        vagResults = null,
                        resetBackupAvailable = true,
                        message = "Reset completato: OBD ${if (obdCleared) "ok" else "nessuna risposta"}, VAG $cleared/${onlineEcus.size}. Riesegui lo scan."
                    )
                }
            }
            startPolling()
        }
    }

    fun diagnosticBundle(): String {
        val current = _state.value
        return DiagnosticBundle(
            createdAt = java.time.Instant.now().toString(),
            adapter = current.adapter,
            vehicle = current.vehicle,
            obdDtcs = current.dtcGroups.orEmpty().flatMap { it.codes }.distinctBy { it.code },
            vagResults = current.vagResults.orEmpty().map { result ->
                "${result.ecu.name}: ${if (result.alive) "online" else "offline"}; identity=${result.identity ?: "n/a"}; error=${result.error ?: "none"}"
            },
            liveValues = current.values,
            trace = elm?.traceSnapshot().orEmpty()
        ).asText()
    }

    fun preResetBundle(): String? = lastPreResetBundle

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun setStatus(s: String) = withContext(Dispatchers.Main) {
        _state.update { it.copy(status = s) }
    }

    override fun onCleared() {
        pollJob?.cancel()
        runCatching { elm?.close() }
    }
}
