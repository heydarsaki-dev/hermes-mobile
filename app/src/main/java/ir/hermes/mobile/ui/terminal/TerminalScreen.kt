package ir.hermes.mobile.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    fun run(cmd: String) {
        if (cmd.isBlank() || busy) return
        lines = lines + "> $cmd"
        busy = true
        scope.launch {
            HermesRepo.shell(cmd)
                .onSuccess { lines = lines + J.pretty(it) }
                .onFailure { lines = lines + "خطا: ${it.message}" }
            busy = false
        }
    }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopBar("ترمینال", onBack) {
            IconButton(onClick = { lines = emptyList() }) {
                Icon(Icons.Default.DeleteSweep, "پاک کردن", tint = TextMid)
            }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(14.dp)) {
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Ink0.copy(alpha = 0.7f)).padding(12.dp)
            ) {
                if (lines.isEmpty()) {
                    EmptyState(
                        "دستور خود را وارد کنید.\nنمونه: ls -la  •  git status  •  hermes --help",
                        Icons.Default.Terminal
                    )
                } else {
                    LazyColumn(state = listState) {
                        itemsIndexed(lines) { _, l ->
                            Text(
                                l,
                                Modifier.padding(vertical = 2.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (l.startsWith(">")) Gold else TextHi,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("دستور شل…", color = TextLow, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Gold, unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { run(input); input = "" },
                    enabled = !busy && input.isNotBlank(),
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "اجرا") }
            }
        }
    }
}
