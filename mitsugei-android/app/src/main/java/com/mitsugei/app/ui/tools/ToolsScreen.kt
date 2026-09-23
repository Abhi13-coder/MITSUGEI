package com.mitsugei.app.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

data class ToolItem(val id: String, val title: String, val icon: ImageVector)

private val tools = listOf(
    ToolItem("coin", "Coin flip", Icons.Default.Toll),
    ToolItem("dice", "Dice roll", Icons.Default.Casino),
    ToolItem("cards", "Card draw", Icons.Default.Style),
    ToolItem("timer", "Timer", Icons.Default.Timer),
    ToolItem("calc", "Calculator", Icons.Default.Calculate),
    ToolItem("color", "Color picker", Icons.Default.Palette),
    ToolItem("clock", "Clock", Icons.Default.AccessTime),
    ToolItem("calendar", "Calendar", Icons.Default.CalendarMonth),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    var active by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (active != null) active = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (active) {
                null -> ToolGrid { active = it }
                "coin" -> CoinFlip { active = null }
                "dice" -> DiceRoll { active = null }
                "cards" -> CardDraw { active = null }
                "timer" -> TimerTool { active = null }
                "calc" -> CalculatorTool { active = null }
                "color" -> ColorPickerTool { active = null }
                "clock" -> ClockTool { active = null }
                "calendar" -> CalendarTool { active = null }
            }
        }
    }
}

@Composable
private fun ToolGrid(onSelect: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(tools) { t ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clickable { onSelect(t.id) },
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(t.icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(t.title, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun CoinFlip(onDone: () -> Unit) {
    var side by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(side ?: "Tap flip", fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { side = if (Random.nextBoolean()) "Heads" else "Tails" }) { Text("Flip") }
    }
}

@Composable
private fun DiceRoll(onDone: () -> Unit) {
    var value by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (value == 0) "—" else value.toString(), fontSize = 64.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { value = Random.nextInt(1, 7) }) { Text("Roll d6") }
    }
}

@Composable
private fun CardDraw(onDone: () -> Unit) {
    val ranks = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
    val suits = listOf("♠", "♥", "♦", "♣")
    var card by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(card ?: "Draw a card", fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { card = "${ranks.random()}${suits.random()}" }) { Text("Draw") }
    }
}

@Composable
private fun TimerTool(onDone: () -> Unit) {
    var seconds by remember { mutableIntStateOf(60) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        while (running && seconds > 0) {
            delay(1000)
            seconds--
        }
        if (seconds == 0) running = false
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("%02d:%02d".format(seconds / 60, seconds % 60), fontSize = 56.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { running = !running }) { Text(if (running) "Pause" else "Start") }
            OutlinedButton(onClick = { running = false; seconds = 60 }) { Text("Reset") }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, 120, 300).forEach { s ->
                AssistChip(onClick = { seconds = s; running = false }, label = { Text("${s}s") })
            }
        }
    }
}

@Composable
private fun CalculatorTool(onDone: () -> Unit) {
    var display by remember { mutableStateOf("0") }
    var acc by remember { mutableStateOf(0.0) }
    var op by remember { mutableStateOf<String?>(null) }
    var fresh by remember { mutableStateOf(true) }

    fun input(d: String) {
        display = if (fresh || display == "0") d else display + d
        fresh = false
    }
    fun applyOp(next: String?) {
        val v = display.toDoubleOrNull() ?: return
        acc = when (op) {
            "+" -> acc + v
            "-" -> acc - v
            "×" -> acc * v
            "÷" -> if (v != 0.0) acc / v else acc
            else -> v
        }
        display = if (acc == acc.toLong().toDouble()) acc.toLong().toString() else acc.toString()
        op = next
        fresh = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
        Text(display, fontSize = 40.sp, fontWeight = FontWeight.Light, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
        Spacer(Modifier.height(12.dp))
        val rows = listOf(
            listOf("C", "÷", "×", "⌫"),
            listOf("7", "8", "9", "-"),
            listOf("4", "5", "6", "+"),
            listOf("1", "2", "3", "="),
            listOf("0", ".", "", "")
        )
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    if (key.isEmpty()) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        Button(
                            onClick = {
                                when (key) {
                                    "C" -> { display = "0"; acc = 0.0; op = null; fresh = true }
                                    "⌫" -> display = display.dropLast(1).ifEmpty { "0" }
                                    in listOf("+", "-", "×", "÷") -> applyOp(key)
                                    "=" -> applyOp(null)
                                    else -> input(key)
                                }
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = CircleShape
                        ) { Text(key, fontSize = 18.sp) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ColorPickerTool(onDone: () -> Unit) {
    var hue by remember { mutableFloatStateOf(280f) }
    val color = Color.hsv(hue, 0.7f, 0.95f)
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(160.dp).clip(CircleShape).background(color))
        Spacer(Modifier.height(24.dp))
        Text("#%02X%02X%02X".format(
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        ), fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))
        Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
    }
}

@Composable
private fun ClockTool(onDone: () -> Unit) {
    var now by remember { mutableStateOf(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            delay(1000)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(now, fontSize = 56.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun CalendarTool(onDone: () -> Unit) {
    val cal = java.util.Calendar.getInstance()
    val month = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(cal.time)
    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(month, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
        Text(day.toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold)
        Text(java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(cal.time), fontSize = 18.sp)
    }
}
