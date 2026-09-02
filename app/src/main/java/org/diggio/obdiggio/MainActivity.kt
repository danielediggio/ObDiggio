package org.diggio.obdiggio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.diggio.obdiggio.core.obd.Pid
import org.diggio.obdiggio.core.obd.PidResult
import org.diggio.obdiggio.core.vag.VagEcuResult
import org.diggio.obdiggio.core.vag.VagEcus
import org.diggio.obdiggio.ui.NeonCyan
import org.diggio.obdiggio.ui.NeonGreen
import org.diggio.obdiggio.ui.NeonPink
import org.diggio.obdiggio.ui.ObdiggioTheme
import org.diggio.obdiggio.ui.PanelBlack
import org.diggio.obdiggio.ui.PanelDark
import org.diggio.obdiggio.ui.Steel
import kotlin.math.cos
import kotlin.math.sin

private val AcidGreen = Color(0xFFB6FF00)
private val NeonOrange = Color(0xFFFF8C00)
private val WarningRed = Color(0xFFFF304F)
private val Carbon = Color(0xFF070A10)
private val Asphalt = Color(0xFF10131B)
private val Glass = Color(0xCC101827)
private val TrackLine = Color(0x22FFFFFF)

private enum class Screen(val title: String, val symbol: String) {
    DASHBOARD("LIVE", "RPM"),
    DTC("SCAN", "DTC"),
    FREEZE("FREEZE", "FRZ"),
    CONNECT("GARAGE", "BLE")
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

    Box(modifier = Modifier.fillMaxSize().background(PanelBlack)) {
        UndergroundBackdrop()
        Column(modifier = Modifier.fillMaxSize()) {
            RacingHeader(state, accent(currentScreen))
            state.message?.let { DiagnosticBanner(it) }
            Box(modifier = Modifier.weight(1f)) {
                when (currentScreen) {
                    Screen.DASHBOARD -> Dashboard(state, vm.dashboardPids)
                    Screen.DTC -> DtcScreen(
                        state = state,
                        onRead = { vm.readDtcs() },
                        onClear = { vm.clearDtcs() },
                        onVagScan = { vm.scanVag() },
                        onVagClear = { vm.clearVagDtcs() }
                    )
                    Screen.FREEZE -> FreezeScreen(state, onRead = { vm.readFreezeFrame() })
                    Screen.CONNECT -> ConnectScreen(
                        state = state,
                        onConnect = { vm.connect(false) },
                        onMock = { vm.connect(true) },
                        onDisconnect = { vm.disconnect() }
                    )
                }
            }
            NeonNavigation(currentScreen, accent(currentScreen)) { currentScreen = it }
        }
    }
}

private fun accent(screen: Screen): Color = when (screen) {
    Screen.DASHBOARD -> AcidGreen
    Screen.DTC -> NeonPink
    Screen.FREEZE -> NeonCyan
    Screen.CONNECT -> NeonOrange
}

@Composable
private fun UndergroundBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF02040A), Color(0xFF071018), Color(0xFF050507))))
        val stripeWidth = 28.dp.toPx()
        var x = -size.height
        while (x < size.width + size.height) {
            drawLine(
                color = TrackLine,
                start = Offset(x, size.height),
                end = Offset(x + size.height, 0f),
                strokeWidth = stripeWidth
            )
            x += stripeWidth * 3.5f
        }
        drawCircle(NeonCyan.copy(alpha = 0.12f), radius = size.width * 0.55f, center = Offset(size.width * 0.95f, size.height * 0.08f))
        drawCircle(NeonPink.copy(alpha = 0.10f), radius = size.width * 0.42f, center = Offset(size.width * 0.05f, size.height * 0.72f))
    }
}

@Composable
private fun RacingHeader(state: UiState, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Carbon, Asphalt, Carbon)))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.35f)))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    "OBDIGGIO",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 3.sp
                )
                Text(
                    "UNDERGROUND DIAGNOSTICS",
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
            StatusPill(
                text = if (state.connected) "ONLINE" else if (state.connecting) "PAIRING" else "OFFLINE",
                color = if (state.connected) NeonGreen else if (state.connecting) NeonOrange else Steel
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeonLine(accent, Modifier.weight(1f))
            Text(state.status, color = Steel, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

@Composable
private fun DiagnosticBanner(message: String) {
    Surface(color = WarningRed.copy(alpha = 0.18f), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Dashboard(state: UiState, pids: List<Pid>) {
    val rpm = state.values[pids.firstOrNull { it.code == 12 }?.key]?.value ?: 0.0
    val speed = state.values[pids.firstOrNull { it.code == 13 }?.key]?.value ?: 0.0
    val coolant = state.values[pids.firstOrNull { it.code == 5 }?.key]?.value
    val voltage = state.values[pids.firstOrNull { it.code == 66 }?.key]?.value

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        UndergroundPanel(accent = AcidGreen) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Tachometer(rpm = rpm.toFloat(), modifier = Modifier.size(184.dp))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    SpeedDisplay(speed = speed.toFloat())
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniReadout("TEMP", coolant, "C", NeonCyan, Modifier.weight(1f))
                        MiniReadout("VOLT", voltage, "V", NeonOrange, Modifier.weight(1f))
                    }
                }
            }
        }

        state.boostKpa?.let { BoostStrip(it) }

        Text(
            "LIVE DATASTREAM",
            color = AcidGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp
        )

        val remaining = pids.filter { it.code != 12 && it.code != 13 && it.code != 5 && it.code != 66 }
        remaining.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { pid ->
                    MetricTile(label = pid.name, value = state.values[pid.key], modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

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
    val obdCount = state.dtcGroups?.sumOf { it.codes.size } ?: 0
    val vagCount = state.vagResults?.sumOf { it.dtcs.size } ?: 0
    val alive = state.vagResults?.count { it.alive } ?: 0

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UndergroundPanel(accent = NeonPink) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticSummary("FAULTS", (obdCount + vagCount).toString(), "totali", if (obdCount + vagCount > 0) WarningRed else NeonGreen, Modifier.weight(1f))
                DiagnosticSummary("ECU", "$alive/${VagEcus.all.size}", "risposte", NeonOrange, Modifier.weight(1f))
                DiagnosticSummary("MODE", if (tab == DtcTab.OBD) "OBD" else "VAG", "scan", NeonCyan, Modifier.weight(1f))
            }
        }

        UndergroundTabs(tab) { tab = it }

        when (tab) {
            DtcTab.OBD -> OdbDtcContent(state, onRead, onClear, busy)
            DtcTab.VAG -> VagDtcContent(state, onVagScan, onVagClear, busy)
        }
    }
}

@Composable
private fun UndergroundTabs(selected: DtcTab, onSelect: (DtcTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
            .background(Glass)
            .border(BorderStroke(1.dp, NeonPink.copy(alpha = 0.35f)), CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DtcTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val color = if (tab == DtcTab.OBD) NeonPink else NeonOrange
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                    .clickable { onSelect(tab) }
                    .background(if (isSelected) color.copy(alpha = 0.24f) else Color.Transparent)
                    .border(BorderStroke(1.dp, if (isSelected) color else Color.Transparent), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (tab == DtcTab.OBD) "OBD-II" else "VAG MULTI-ECU",
                    color = if (isSelected) Color.White else Steel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun OdbDtcContent(state: UiState, onRead: () -> Unit, onClear: () -> Unit, busy: Boolean) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("SCAN DTC", NeonPink, modifier = Modifier.weight(1f), onClick = onRead, enabled = !busy)
            NeonButton("CLEAR", Steel, modifier = Modifier.weight(1f), onClick = onClear, enabled = !busy && state.dtcGroups?.isNotEmpty() == true)
        }
        when {
            state.dtcBusy -> Center { CircularProgressIndicator(color = NeonPink) }
            state.dtcGroups == null -> EmptyGarage("OBD-II STANDARD", "Mode 03 / 07 / 0A pronti per la lettura errori.")
            state.dtcGroups.isEmpty() -> EmptyGarage("NO FAULTS", "Nessun DTC OBD-II rilevato.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.dtcGroups.forEach { group ->
                    item { SectionLabel(group.label.uppercase(), NeonPink) }
                    items(group.codes) { dtc ->
                        DtcCard(code = dtc.code, description = dtc.description, accent = NeonPink)
                    }
                }
            }
        }
    }
}

@Composable
private fun VagDtcContent(state: UiState, onScan: () -> Unit, onClear: () -> Unit, busy: Boolean) {
    val hasVagDtcs = state.vagResults?.any { it.dtcs.isNotEmpty() } == true

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("SCAN ECU", NeonOrange, modifier = Modifier.weight(1f), onClick = onScan, enabled = !busy)
            NeonButton("CLEAR ALL", Steel, modifier = Modifier.weight(1f), onClick = onClear, enabled = !busy && hasVagDtcs)
        }
        InfoRibbon("CAN physical address - UDS 19 02 FF - ABS, airbag, immobilizer, cambio")

        when {
            state.vagBusy -> Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = NeonOrange)
                Spacer(modifier = Modifier.height(10.dp))
                if (state.vagProgress.isNotBlank()) {
                    Text(state.vagProgress, color = NeonOrange, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                state.vagResults?.takeIf { it.isNotEmpty() }?.let { partial ->
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(partial) { r -> VagEcuCard(r, scanning = true) }
                    }
                }
            }
            state.vagResults == null -> EmptyGarage("VAG MULTI-ECU", "Scansione centraline in stile OBDeleven, con lista ECU e DTC per modulo.")
            state.vagResults.isEmpty() -> EmptyGarage("NO RESPONSE", "Nessuna ECU risponde sul protocollo CAN selezionato.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                item {
                    val alive = state.vagResults.count { it.alive }
                    val dtcCount = state.vagResults.sumOf { it.dtcs.size }
                    SectionLabel("$alive/${state.vagResults.size} ECU ONLINE - $dtcCount DTC", NeonOrange)
                }
                items(state.vagResults) { r -> VagEcuCard(r, scanning = false) }
            }
        }
    }
}

@Composable
private fun VagEcuCard(result: VagEcuResult, scanning: Boolean) {
    var expanded by remember(result.ecu.id) { mutableStateOf(result.dtcs.isNotEmpty()) }
    val color = when {
        !result.alive -> Steel.copy(alpha = 0.45f)
        result.dtcs.isNotEmpty() -> WarningRed
        else -> NeonGreen
    }

    UndergroundPanel(accent = color, modifier = Modifier.clickable(enabled = result.alive) { expanded = !expanded }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(result.ecu.name.uppercase(), color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 13.sp)
                if (result.ecu.note.isNotBlank()) {
                    Text(result.ecu.note, color = Steel.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                scanning && !result.alive -> StatusPill("SCAN", Steel)
                !result.alive -> StatusPill("NO RESP", Steel)
                result.dtcs.isEmpty() -> StatusPill("OK", NeonGreen)
                else -> StatusPill("${result.dtcs.size} DTC", WarningRed)
            }
        }

        result.error?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(it, color = Steel.copy(alpha = 0.65f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        if (expanded && result.dtcs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = color.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.height(8.dp))
            result.dtcs.forEach { dtc ->
                DtcRow(dtc.code, dtc.description, dtc.statusText, dtc.rawHex, color)
            }
        }
    }
}

@Composable
private fun DtcCard(code: String, description: String, accent: Color) {
    UndergroundPanel(accent = accent) {
        DtcRow(code, description, "standard", "", accent)
    }
}

@Composable
private fun FreezeScreen(state: UiState, onRead: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UndergroundPanel(accent = NeonCyan) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("FREEZE FRAME", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Text("Snapshot dati al momento del guasto", color = Steel, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                NeonButton("READ", NeonCyan, onClick = onRead, enabled = !state.freezeBusy)
            }
        }
        when {
            state.freezeBusy -> Center { CircularProgressIndicator(color = NeonCyan) }
            state.freeze == null -> EmptyGarage("FREEZE DATA", "Leggi lo snapshot salvato dalla ECU quando e' stato registrato un guasto.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.freeze.dtc?.let {
                    item {
                        UndergroundPanel(accent = NeonCyan) {
                            Text("DTC REFERENCE", color = Steel, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text(it.code, color = NeonCyan, fontWeight = FontWeight.Black, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
                            Text(it.description, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                items(state.freeze.values) { pr -> FreezeMetric(pr) }
            }
        }
    }
}

@Composable
private fun ConnectScreen(state: UiState, onConnect: () -> Unit, onMock: () -> Unit, onDisconnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        UndergroundPanel(accent = NeonOrange) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("GARAGE LINK", color = NeonOrange, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text("OBDIGGIO", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 5.sp)
                Text("BLE OBD-II TUNER DIAGNOSTICS", color = Steel, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(24.dp))

                if (state.connected) {
                    StatusPill(state.status.uppercase(), NeonGreen)
                    Spacer(modifier = Modifier.height(16.dp))
                    NeonButton("DISCONNECT", WarningRed, modifier = Modifier.fillMaxWidth(), onClick = onDisconnect)
                } else {
                    NeonButton(if (state.connecting) "PAIRING..." else "CONNECT BLE", NeonOrange, modifier = Modifier.fillMaxWidth(), onClick = onConnect, enabled = !state.connecting)
                    Spacer(modifier = Modifier.height(10.dp))
                    NeonButton("SIMULATOR MODE", Steel, modifier = Modifier.fillMaxWidth(), onClick = onMock, enabled = !state.connecting)
                    if (state.connecting) {
                        Spacer(modifier = Modifier.height(18.dp))
                        CircularProgressIndicator(color = NeonOrange)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(state.status, color = Steel, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tachometer(rpm: Float, modifier: Modifier = Modifier) {
    val animRpm by animateFloatAsState(targetValue = rpm, animationSpec = tween(300), label = "rpm")
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val radius = size.minDimension / 2 - 14.dp.toPx()
            val startAngle = 135f
            val sweepTotal = 270f
            val maxRpm = 8000f
            val fraction = (animRpm / maxRpm).coerceIn(0f, 1f)

            drawCircle(Color.Black.copy(alpha = 0.40f), radius = radius + 12.dp.toPx(), center = Offset(cx, cy))
            drawArc(PanelDark, startAngle, sweepTotal, false, style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round))
            drawArc(
                brush = Brush.sweepGradient(listOf(AcidGreen, NeonCyan, NeonOrange, WarningRed), center = Offset(cx, cy)),
                startAngle = startAngle,
                sweepAngle = sweepTotal * fraction,
                useCenter = false,
                style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
            )
            for (i in 0..40) {
                val angle = Math.toRadians((startAngle + sweepTotal * i / 40.0))
                val inner = radius - if (i % 5 == 0) 14.dp.toPx() else 8.dp.toPx()
                val outer = radius + 2.dp.toPx()
                drawLine(
                    color = if (i % 5 == 0) Color.White.copy(alpha = 0.75f) else Steel.copy(alpha = 0.28f),
                    start = Offset((cx + cos(angle) * inner).toFloat(), (cy + sin(angle) * inner).toFloat()),
                    end = Offset((cx + cos(angle) * outer).toFloat(), (cy + sin(angle) * outer).toFloat()),
                    strokeWidth = if (i % 5 == 0) 2.dp.toPx() else 1.dp.toPx()
                )
            }
            val needleAngle = Math.toRadians((startAngle + sweepTotal * fraction).toDouble())
            val needleLen = radius - 24.dp.toPx()
            drawLine(
                color = WarningRed,
                start = Offset(cx, cy),
                end = Offset((cx + cos(needleAngle) * needleLen).toFloat(), (cy + sin(needleAngle) * needleLen).toFloat()),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(WarningRed, radius = 7.dp.toPx(), center = Offset(cx, cy))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%04d".format(animRpm.toInt()), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text("RPM", color = AcidGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SpeedDisplay(speed: Float) {
    Column(horizontalAlignment = Alignment.End) {
        Text("%03d".format(speed.toInt()), color = Color.White, fontSize = 62.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text("KM/H", color = AcidGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
    }
}

@Composable
private fun MetricTile(label: String, value: PidResult?, modifier: Modifier = Modifier) {
    UndergroundPanel(accent = NeonCyan.copy(alpha = 0.85f), modifier = modifier.height(92.dp)) {
        Text(label.uppercase(), color = Steel, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(6.dp))
        if (value?.value != null) {
            Text("%.1f".format(value.value), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(value.unit, color = NeonCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        } else {
            Text("--", color = Steel.copy(alpha = 0.55f), fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun MiniReadout(label: String, value: Double?, unit: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .background(Color.Black.copy(alpha = 0.32f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .padding(8.dp)
    ) {
        Text(label, color = Steel, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(if (value != null) "%.1f".format(value) else "--", color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
        Text(unit, color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun BoostStrip(boostKpa: Double) {
    UndergroundPanel(accent = NeonCyan) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("BOOST PRESSURE", color = Steel, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("TURBO DELTA", color = NeonCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Text("%.1f kPa".format(boostKpa), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun DiagnosticSummary(label: String, value: String, caption: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = Steel, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text(caption.uppercase(), color = Steel.copy(alpha = 0.7f), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DtcRow(code: String, description: String, status: String, raw: String, color: Color) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(code, color = color, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
            Text(status.uppercase(), color = Steel, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(description, color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp)
        if (raw.isNotBlank()) {
            Text("RAW $raw", color = Steel.copy(alpha = 0.45f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun FreezeMetric(pr: PidResult) {
    UndergroundPanel(accent = NeonCyan) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(pr.name.uppercase(), color = Steel, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (pr.value != null) "%.2f %s".format(pr.value, pr.unit) else "--",
                color = Color.White,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun NeonNavigation(current: Screen, accent: Color, onSelect: (Screen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Carbon, Asphalt, Carbon)))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.32f)))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Screen.entries.forEach { screen ->
            val selected = screen == current
            val color = if (selected) accent(screen) else Steel
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
                    .clip(CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                    .clickable { onSelect(screen) }
                    .background(if (selected) color.copy(alpha = 0.20f) else Color.Transparent)
                    .border(BorderStroke(1.dp, if (selected) color else Steel.copy(alpha = 0.18f)), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(screen.symbol, fontSize = 12.sp, color = color, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                Text(screen.title, fontSize = 9.sp, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun UndergroundPanel(accent: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Glass),
        shape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)))
                .padding(12.dp),
            content = content
        )
    }
}

@Composable
private fun NeonButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.18f),
            contentColor = Color.White,
            disabledContainerColor = PanelDark,
            disabledContentColor = Steel.copy(alpha = 0.45f)
        ),
        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
        border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.75f) else Steel.copy(alpha = 0.2f))
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(
        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
        color = color.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.65f))
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun InfoRibbon(text: String) {
    Text(
        text = text,
        color = Steel.copy(alpha = 0.78f),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .border(BorderStroke(1.dp, NeonOrange.copy(alpha = 0.30f)), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun EmptyGarage(title: String, body: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text(body, color = Steel.copy(alpha = 0.75f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NeonLine(color, Modifier.weight(1f))
        Text(text, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 8.dp))
        NeonLine(color, Modifier.weight(1f))
    }
}

@Composable
private fun NeonLine(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(2.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, color, Color.Transparent))))
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
