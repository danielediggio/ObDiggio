package org.diggio.obdiggio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.diggio.obdiggio.core.vag.VagEcuResult
import org.diggio.obdiggio.ui.NeonCyan
import org.diggio.obdiggio.ui.NeonGreen
import org.diggio.obdiggio.ui.NeonPink
import org.diggio.obdiggio.ui.ObdiggioTheme
import org.diggio.obdiggio.ui.PanelBlack
import org.diggio.obdiggio.ui.PanelDark
import org.diggio.obdiggio.ui.Steel
import kotlin.math.cos
import kotlin.math.sin

private val NeonOrange = Color(0xFFFF8C00)

private enum class Screen(val title: String, val symbol: String) {
    DASHBOARD("CRUSCOTTO", "◴"),
    DTC("ERRORI",          "▣"),
    FREEZE("FREEZE",       "❄"),
    CONNECT("CONNESSIONE", "⛓")
}

class MainActivity : ComponentActivity() {
    private val vm: ObdViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ObdiggioTheme {
                ObdiggioApp(vm)
            }
        }
    }
}

@Composable
private fun ObdiggioApp(vm: ObdViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var currentScreen by remember { mutableStateOf(Screen.CONNECT) }

    LaunchedEffect(state.connected) {
        if (state.connected) currentScreen = Screen.DASHBOARD
    }

    Surface(modifier = Modifier.fillMaxSize(), color = PanelBlack) {
        Column(modifier = Modifier.fillMaxSize()) {
            RacingHeader(state.status, accent(currentScreen))
            // Global message / error banner
            state.message?.let { msg ->
                Surface(color = PanelDark, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = msg,
                        color = NeonOrange,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                when (currentScreen) {
                    Screen.DASHBOARD -> Dashboard(state, vm.dashboardPids)
                    Screen.DTC       -> DtcScreen(
                        state     = state,
                        onRead    = { vm.readDtcs() },
                        onClear   = { vm.clearDtcs() },
                        onVagScan = { vm.scanVag() },
                        onVagClear= { vm.clearVagDtcs() }
                    )
                    Screen.FREEZE    -> FreezeScreen(state, onRead = { vm.readFreezeFrame() })
                    Screen.CONNECT   -> ConnectScreen(
                        state        = state,
                        onConnect    = { vm.connect(false) },
                        onMock       = { vm.connect(true) },
                        onDisconnect = { vm.disconnect() }
                    )
                }
            }
            NeonNavigation(currentScreen, accent(currentScreen)) { currentScreen = it }
        }
    }
}

private fun accent(screen: Screen): Color = when (screen) {
    Screen.DASHBOARD -> NeonGreen
    Screen.DTC       -> NeonPink
    Screen.FREEZE    -> NeonCyan
    Screen.CONNECT   -> NeonGreen
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun RacingHeader(status: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelDark)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("OBDIGGIO", color = accent, fontSize = 20.sp,
            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
        Text(status, color = Steel, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

// ── Dashboard ─────────────────────────────────────────────────────────────────

@Composable
private fun Dashboard(state: UiState, pids: List<org.diggio.obdiggio.core.obd.Pid>) {
    val rpm   = state.values[pids.firstOrNull { it.code == 12 }?.key]?.value ?: 0.0
    val speed = state.values[pids.firstOrNull { it.code == 13 }?.key]?.value ?: 0.0

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tachometer(rpm = rpm.toFloat(), modifier = Modifier.size(160.dp))
            SpeedDisplay(speed = speed.toFloat())
        }
        Spacer(modifier = Modifier.height(12.dp))
        val remaining = pids.filter { it.code != 12 && it.code != 13 }
        remaining.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { pid ->
                    MetricTile(label = pid.name, value = state.values[pid.key], modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        state.boostKpa?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("BOOST", color = NeonCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("%.1f kPa".format(it), color = NeonCyan, fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

// ── DTC Screen — tabbed: OBD-II | VAG Multi-ECU ───────────────────────────────

private enum class DtcTab { OBD, VAG }

@Composable
private fun DtcScreen(
    state: UiState,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onVagScan: () -> Unit,
    onVagClear: () -> Unit
) {
    var tab by remember { mutableStateOf(DtcTab.OBD) }
    val busy = state.dtcBusy || state.vagBusy

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Tab selector
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(PanelDark),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DtcTab.entries.forEach { t ->
                val selected = t == tab
                val color = if (t == DtcTab.VAG) NeonOrange else NeonPink
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { tab = t }
                        .background(if (selected) color.copy(alpha = 0.15f) else Color.Transparent)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (t == DtcTab.OBD) "OBD-II STANDARD" else "VAG MULTI-ECU",
                        color = if (selected) color else Steel,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (tab) {
            DtcTab.OBD -> OdbDtcContent(state, onRead, onClear, busy)
            DtcTab.VAG -> VagDtcContent(state, onVagScan, onVagClear, busy)
        }
    }
}

@Composable
private fun OdbDtcContent(state: UiState, onRead: () -> Unit, onClear: () -> Unit, busy: Boolean) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("LEGGI DTC", NeonPink, modifier = Modifier.weight(1f), onClick = onRead, enabled = !busy)
            NeonButton("CANCELLA", Steel, modifier = Modifier.weight(1f), onClick = onClear,
                enabled = !busy && state.dtcGroups?.isNotEmpty() == true)
        }
        Spacer(modifier = Modifier.height(12.dp))
        when {
            state.dtcBusy -> Center { CircularProgressIndicator(color = NeonPink) }
            state.dtcGroups == null -> Hint("Premi LEGGI DTC per i codici guasto standard OBD-II\n(Mode 03 / 07 / 0A)")
            state.dtcGroups.isEmpty() -> Hint("Nessun DTC OBD-II ✓")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.dtcGroups.forEach { group ->
                    item {
                        Text(group.label.uppercase(), color = NeonPink, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                    items(group.codes) { dtc ->
                        DtcCard(code = dtc.code, description = dtc.description, accent = NeonPink)
                    }
                }
            }
        }
    }
}

@Composable
private fun VagDtcContent(
    state: UiState,
    onScan: () -> Unit,
    onClear: () -> Unit,
    busy: Boolean
) {
    val hasVagDtcs = state.vagResults?.any { it.dtcs.isNotEmpty() } == true

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("SCAN ECU VAG", NeonOrange, modifier = Modifier.weight(1f),
                onClick = onScan, enabled = !busy)
            NeonButton("CANCELLA TUTTI", Steel, modifier = Modifier.weight(1f),
                onClick = onClear, enabled = !busy && hasVagDtcs)
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Info box
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PanelDark),
            shape = RoundedCornerShape(6.dp)) {
            Text(
                text = "Interroga ${org.diggio.obdiggio.core.vag.VagEcus.all.size} ECU via CAN fisico (ATSH) • " +
                       "UDS service 19 02 FF • Include immobilizer, cambio, ABS, airbag",
                color = Steel.copy(alpha = 0.7f), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        when {
            state.vagBusy -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = NeonOrange)
                Spacer(modifier = Modifier.height(12.dp))
                if (state.vagProgress.isNotBlank()) {
                    Text(state.vagProgress, color = NeonOrange, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                // Show partial results while scanning
                state.vagResults?.takeIf { it.isNotEmpty() }?.let { partial ->
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(partial) { r -> VagEcuCard(r, scanning = true) }
                    }
                }
            }
            state.vagResults == null -> Hint(
                "Premi SCAN ECU VAG\n\nLegge i DTC da ogni ECU direttamente\nvia indirizzo CAN fisico — " +
                "include codici non visibili in OBD-II standard\n(immobilizer, trasmissione, airbag…)"
            )
            state.vagResults.isEmpty() -> Hint("Nessuna ECU risponde — controlla protocollo CAN")
            else -> {
                val alive = state.vagResults.count { it.alive }
                val total = state.vagResults.size
                val dtcCount = state.vagResults.sumOf { it.dtcs.size }
                Text("$alive/$total ECU raggiungibili  •  $dtcCount DTC totali",
                    color = Steel, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.vagResults) { r -> VagEcuCard(r, scanning = false) }
                }
            }
        }
    }
}

@Composable
private fun VagEcuCard(result: VagEcuResult, scanning: Boolean) {
    var expanded by remember(result.ecu.id) { mutableStateOf(result.dtcs.isNotEmpty()) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = result.alive) { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = PanelDark),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.ecu.name.uppercase(),
                        color = when {
                            !result.alive          -> Steel.copy(alpha = 0.4f)
                            result.dtcs.isNotEmpty()-> NeonOrange
                            else                   -> NeonGreen
                        },
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    if (result.ecu.note.isNotBlank()) {
                        Text(result.ecu.note, color = Steel.copy(alpha = 0.5f), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                // Status badge
                when {
                    scanning && !result.alive -> Text("…", color = Steel, fontFamily = FontFamily.Monospace)
                    !result.alive -> {
                        StatusBadge("NO RESP", Steel.copy(alpha = 0.4f))
                    }
                    result.dtcs.isEmpty() -> StatusBadge("OK ✓", NeonGreen)
                    else -> StatusBadge("${result.dtcs.size} DTC", NeonOrange)
                }
            }

            // Error note
            result.error?.let {
                Text(it, color = Steel.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            // Expanded DTC list
            if (expanded && result.dtcs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = NeonOrange.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(8.dp))
                result.dtcs.forEach { dtc ->
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(dtc.code, color = NeonOrange, fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                            Text(dtc.statusText, color = Steel.copy(alpha = 0.7f), fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                        Text(dtc.description, color = Steel, fontSize = 11.sp)
                        if (dtc.rawHex.isNotBlank()) {
                            Text("raw: ${dtc.rawHex}", color = Steel.copy(alpha = 0.35f),
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Collapse hint
            if (result.alive && result.dtcs.isNotEmpty()) {
                Text(
                    text = if (expanded) "▲ comprimi" else "▼ espandi",
                    color = Steel.copy(alpha = 0.5f), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun DtcCard(code: String, description: String, accent: Color) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PanelDark)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(code, color = accent, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            Text(description, color = Steel, fontSize = 12.sp)
        }
    }
}

// ── Freeze Frame ──────────────────────────────────────────────────────────────

@Composable
private fun FreezeScreen(state: UiState, onRead: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        NeonButton("LEGGI FREEZE FRAME", NeonCyan, modifier = Modifier.fillMaxWidth(),
            onClick = onRead, enabled = !state.freezeBusy)
        Spacer(modifier = Modifier.height(12.dp))
        when {
            state.freezeBusy -> Center { CircularProgressIndicator(color = NeonCyan) }
            state.freeze == null -> Hint("Premi LEGGI FREEZE FRAME\nper vedere i dati al momento del guasto")
            else -> {
                state.freeze.dtc?.let {
                    Card(modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("DTC di riferimento", color = Steel, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text(it.code, color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                            Text(it.description, color = Steel, fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.freeze.values) { pr ->
                        Card(modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(pr.name, color = Steel, fontSize = 12.sp)
                                if (pr.value != null)
                                    Text("%.2f %s".format(pr.value, pr.unit), color = NeonCyan,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                else
                                    Text("—", color = Steel.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Connect Screen ────────────────────────────────────────────────────────────

@Composable
private fun ConnectScreen(
    state: UiState,
    onConnect: () -> Unit,
    onMock: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("⛓", fontSize = 64.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))
        Text("OBDIGGIO", color = NeonGreen, fontSize = 28.sp, fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
        Text("OBD-II via Bluetooth LE", color = Steel, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(32.dp))

        if (state.connected) {
            Text(state.status, color = NeonGreen, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
            NeonButton("DISCONNETTI", NeonPink, modifier = Modifier.fillMaxWidth(), onClick = onDisconnect)
        } else {
            NeonButton(
                if (state.connecting) "Connessione…" else "CONNETTI BLE",
                NeonGreen, modifier = Modifier.fillMaxWidth(),
                onClick = onConnect, enabled = !state.connecting
            )
            Spacer(modifier = Modifier.height(12.dp))
            NeonButton("USA SIMULATORE", Steel, modifier = Modifier.fillMaxWidth(),
                onClick = onMock, enabled = !state.connecting)
            if (state.connecting) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(color = NeonGreen)
                Spacer(modifier = Modifier.height(8.dp))
                Text(state.status, color = Steel, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

// ── Tachometer ────────────────────────────────────────────────────────────────

@Composable
private fun Tachometer(rpm: Float, modifier: Modifier = Modifier) {
    val animRpm by animateFloatAsState(targetValue = rpm, animationSpec = tween(300), label = "rpm")
    Canvas(modifier = modifier) {
        val cx = size.width / 2; val cy = size.height / 2
        val radius = size.minDimension / 2 - 12.dp.toPx()
        val startAngle = 135f; val sweepTotal = 270f; val maxRpm = 8000f
        val fraction = (animRpm / maxRpm).coerceIn(0f, 1f)
        drawArc(color = PanelDark, startAngle = startAngle, sweepAngle = sweepTotal, useCenter = false,
            style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round))
        drawArc(brush = Brush.sweepGradient(listOf(NeonGreen, NeonCyan, NeonPink), center = Offset(cx, cy)),
            startAngle = startAngle, sweepAngle = sweepTotal * fraction, useCenter = false,
            style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round))
        for (i in 0..40) {
            val angle = Math.toRadians((startAngle + sweepTotal * i / 40.0))
            val inner = radius - 10.dp.toPx(); val outer = radius
            drawLine(color = if (i % 10 == 0) Steel else Steel.copy(alpha = 0.3f),
                start = Offset((cx + cos(angle) * inner).toFloat(), (cy + sin(angle) * inner).toFloat()),
                end   = Offset((cx + cos(angle) * outer).toFloat(), (cy + sin(angle) * outer).toFloat()),
                strokeWidth = if (i % 10 == 0) 2.dp.toPx() else 1.dp.toPx())
        }
        val needleAngle = Math.toRadians((startAngle + sweepTotal * fraction).toDouble())
        val needleLen = radius - 20.dp.toPx()
        drawLine(color = NeonGreen, start = Offset(cx, cy),
            end = Offset((cx + cos(needleAngle) * needleLen).toFloat(), (cy + sin(needleAngle) * needleLen).toFloat()),
            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color = NeonGreen, radius = 6.dp.toPx(), center = Offset(cx, cy))
    }
}

@Composable
private fun SpeedDisplay(speed: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(16.dp)) {
        Text("%d".format(speed.toInt()), color = NeonGreen, fontSize = 64.sp,
            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text("km/h", color = Steel, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MetricTile(label: String, value: org.diggio.obdiggio.core.obd.PidResult?, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = PanelDark),
        shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label.uppercase(), color = Steel, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp, maxLines = 1)
            Spacer(modifier = Modifier.height(4.dp))
            if (value?.value != null) {
                Text("%.1f".format(value.value), color = NeonGreen, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(value.unit, color = Steel, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text("—", color = Steel.copy(alpha = 0.4f), fontSize = 22.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── Navigation ────────────────────────────────────────────────────────────────

@Composable
private fun NeonNavigation(current: Screen, accent: Color, onSelect: (Screen) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(PanelDark).padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        Screen.entries.forEach { screen ->
            val selected = screen == current
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onSelect(screen) }
                    .background(if (selected) accent.copy(alpha = 0.15f) else Color.Transparent)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(screen.symbol, fontSize = 20.sp, color = if (selected) accent else Steel)
                Text(screen.title, fontSize = 8.sp, color = if (selected) accent else Steel,
                    fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp)
            }
        }
    }
}

// ── Utility composables ───────────────────────────────────────────────────────

@Composable
private fun NeonButton(
    text: String, color: Color, modifier: Modifier = Modifier,
    onClick: () -> Unit, enabled: Boolean = true
) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.15f), contentColor = color,
            disabledContainerColor = PanelDark, disabledContentColor = Steel.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (enabled) color.copy(alpha = 0.6f) else Steel.copy(alpha = 0.2f))
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp, fontSize = 12.sp)
    }
}

@Composable
private fun Hint(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Steel.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace,
            fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
