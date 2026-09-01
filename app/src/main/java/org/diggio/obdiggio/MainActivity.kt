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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.diggio.obdiggio.ui.NeonCyan
import org.diggio.obdiggio.ui.NeonGreen
import org.diggio.obdiggio.ui.NeonPink
import org.diggio.obdiggio.ui.ObdiggioTheme
import org.diggio.obdiggio.ui.PanelBlack
import org.diggio.obdiggio.ui.PanelDark
import org.diggio.obdiggio.ui.Steel
import kotlin.math.cos
import kotlin.math.sin

private enum class Screen(val title: String, val symbol: String) {
    DASHBOARD("CRUSCOTTO", "◴"),
    DTC("ERRORI",      "▣"),
    FREEZE("FREEZE",   "❄"),
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
            Box(modifier = Modifier.weight(1f)) {
                when (currentScreen) {
                    Screen.DASHBOARD -> Dashboard(state, vm.dashboardPids)
                    Screen.DTC       -> DtcScreen(state, onRead = { vm.readDtcs() }, onClear = { vm.clearDtcs() })
                    Screen.FREEZE    -> FreezeScreen(state, onRead = { vm.readFreezeFrame() })
                    Screen.CONNECT   -> ConnectScreen(state, onConnect = { vm.connect(false) }, onMock = { vm.connect(true) }, onDisconnect = { vm.disconnect() })
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
        Text(
            text = "OBDIGGIO",
            color = accent,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 4.sp
        )
        Text(
            text = status,
            color = Steel,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun Dashboard(state: UiState, pids: List<org.diggio.obdiggio.core.obd.Pid>) {
    val rpm = state.values[pids.firstOrNull { it.code == 12 }?.key]?.value ?: 0.0
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
                    MetricTile(
                        label = pid.name,
                        value = state.values[pid.key],
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        state.boostKpa?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("BOOST", color = NeonCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("%.1f kPa".format(it), color = NeonCyan, fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun Tachometer(rpm: Float, modifier: Modifier = Modifier) {
    val animRpm by animateFloatAsState(targetValue = rpm, animationSpec = tween(300), label = "rpm")
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val radius = size.minDimension / 2 - 12.dp.toPx()
        val startAngle = 135f
        val sweepTotal = 270f
        val maxRpm = 8000f
        val fraction = (animRpm / maxRpm).coerceIn(0f, 1f)

        // Background arc
        drawArc(color = PanelDark, startAngle = startAngle, sweepAngle = sweepTotal, useCenter = false,
            style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round))

        // Neon ring
        drawArc(
            brush = Brush.sweepGradient(listOf(NeonGreen, NeonCyan, NeonPink), center = Offset(cx, cy)),
            startAngle = startAngle, sweepAngle = sweepTotal * fraction, useCenter = false,
            style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
        )

        // Tick marks
        for (i in 0..40) {
            val angle = Math.toRadians((startAngle + sweepTotal * i / 40.0))
            val inner = radius - 10.dp.toPx()
            val outer = radius
            drawLine(
                color = if (i % 10 == 0) Steel else Steel.copy(alpha = 0.3f),
                start = Offset((cx + cos(angle) * inner).toFloat(), (cy + sin(angle) * inner).toFloat()),
                end   = Offset((cx + cos(angle) * outer).toFloat(), (cy + sin(angle) * outer).toFloat()),
                strokeWidth = if (i % 10 == 0) 2.dp.toPx() else 1.dp.toPx()
            )
        }

        // Needle
        val needleAngle = Math.toRadians((startAngle + sweepTotal * fraction).toDouble())
        val needleLen = radius - 20.dp.toPx()
        rotate(0f, pivot = Offset(cx, cy)) {
            drawLine(
                color = NeonGreen,
                start = Offset(cx, cy),
                end = Offset((cx + cos(needleAngle) * needleLen).toFloat(), (cy + sin(needleAngle) * needleLen).toFloat()),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Center dot
        drawCircle(color = NeonGreen, radius = 6.dp.toPx(), center = Offset(cx, cy))
    }
}

@Composable
private fun SpeedDisplay(speed: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(16.dp)) {
        Text(
            text = "%d".format(speed.toInt()),
            color = NeonGreen,
            fontSize = 64.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
        Text("km/h", color = Steel, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MetricTile(label: String, value: org.diggio.obdiggio.core.obd.PidResult?, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = PanelDark),
        shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label.uppercase(), color = Steel, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, maxLines = 1)
            Spacer(modifier = Modifier.height(4.dp))
            if (value?.value != null) {
                Text("%.1f".format(value.value), color = NeonGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(value.unit, color = Steel, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text("—", color = Steel.copy(alpha = 0.4f), fontSize = 22.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun NeonNavigation(current: Screen, accent: Color, onSelect: (Screen) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(PanelDark).padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        Screen.entries.forEach { screen ->
            val selected = screen == current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(screen) }
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

@Composable
private fun DtcScreen(state: UiState, onRead: () -> Unit, onClear: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("LEGGI DTC", NeonPink, modifier = Modifier.weight(1f), onClick = onRead, enabled = !state.dtcBusy)
            NeonButton("CANCELLA", Steel, modifier = Modifier.weight(1f), onClick = onClear, enabled = !state.dtcBusy && state.dtcGroups?.isNotEmpty() == true)
        }
        Spacer(modifier = Modifier.height(12.dp))

        when {
            state.dtcBusy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonPink)
            }
            state.dtcGroups == null -> Hint("Premi LEGGI DTC per caricare i codici guasto")
            state.dtcGroups.isEmpty() -> Hint("Nessun DTC presente ✓")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.dtcGroups.forEach { group ->
                    item {
                        Text(group.label.uppercase(), color = NeonPink, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                    items(group.codes) { dtc ->
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(dtc.code, color = NeonPink, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                                Text(dtc.description, color = Steel, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FreezeScreen(state: UiState, onRead: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        NeonButton("LEGGI FREEZE FRAME", NeonCyan, modifier = Modifier.fillMaxWidth(), onClick = onRead, enabled = !state.freezeBusy)
        Spacer(modifier = Modifier.height(12.dp))

        when {
            state.freezeBusy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonCyan)
            }
            state.freeze == null -> Hint("Premi LEGGI FREEZE FRAME per vedere i dati al momento del guasto")
            else -> {
                state.freeze.dtc?.let {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PanelDark)) {
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
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(pr.name, color = Steel, fontSize = 12.sp)
                                if (pr.value != null) {
                                    Text("%.2f %s".format(pr.value, pr.unit), color = NeonCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                } else {
                                    Text("—", color = Steel.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectScreen(state: UiState, onConnect: () -> Unit, onMock: () -> Unit, onDisconnect: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
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
            NeonButton("USA SIMULATORE", Steel, modifier = Modifier.fillMaxWidth(), onClick = onMock, enabled = !state.connecting)
            if (state.connecting) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(color = NeonGreen)
                Spacer(modifier = Modifier.height(8.dp))
                Text(state.status, color = Steel, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NeonButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.15f),
            contentColor = color,
            disabledContainerColor = PanelDark,
            disabledContentColor = Steel.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.6f) else Steel.copy(alpha = 0.2f))
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 12.sp)
    }
}

@Composable
private fun Hint(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Steel.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace,
            fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}
