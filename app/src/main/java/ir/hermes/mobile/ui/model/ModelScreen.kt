package ir.hermes.mobile.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun ModelScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var raw by remember { mutableStateOf<JsonObject?>(null) }
    var providers by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf("") }
    var keyProvider by remember { mutableStateOf<String?>(null) }
    var keyValue by remember { mutableStateOf("") }

    suspend fun load() {
        loading = true; error = null
        HermesRepo.modelOptions()
            .onSuccess { r ->
                val o = J.obj(r)
                raw = o
                current = J.str(o, "model", J.str(o, "current", ""))
                providers = J.listOf(o, "providers", "options").map { J.obj(it) }
                if (providers.isEmpty()) providers = J.listOf(o).map { J.obj(it) }
            }
            .onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopBar("مدل و پرووایدر", onBack) {
            IconButton(onClick = { scope.launch { load() } }) { Icon(Icons.Default.Refresh, "تازه‌سازی", tint = TextMid) }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp)) {
            error?.let { ErrorBanner(it) { scope.launch { load() } } }
            if (loading) { LoadingRow(); return@Column }

            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("مدل فعال")
                Spacer(Modifier.height(6.dp))
                if (current.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp), tint = Gold)
                        Spacer(Modifier.width(8.dp))
                        Text(current, style = MaterialTheme.typography.titleSmall, color = Gold)
                    }
                } else {
                    Text("مدل فعال از سرور خوانده نشد", style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
            }
            Spacer(Modifier.height(12.dp))

            SectionTitle("پرووایدرها")
            if (providers.isEmpty()) EmptyState("پرووایدری یافت نشد", Icons.Default.CloudOff) else
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // کلید یکتا؛ برخی پاسخ‌ها شناسه/نام تکراری برمی‌گردانند و
                // LazyColumn با کلید تکراری کرش می‌کند.
                itemsIndexed(
                    providers,
                    key = { i, p -> "$i:${J.str(p, "id", J.str(p, "name"))}" },
                ) { _, p -> ProviderCard(p, onSelect = { name ->
                    scope.launch { HermesRepo.configSet("model", kotlinx.serialization.json.JsonPrimitive(name)) ; load() }
                }, onKey = { keyProvider = J.str(p, "id", J.str(p, "name")) },
                    onDisconnect = { name ->
                        scope.launch { HermesRepo.disconnectProvider(name); load() }
                    }) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    keyProvider?.let { provider ->
        AlertDialog(
            onDismissRequest = { keyProvider = null },
            title = { Text("کلید API — $provider") },
            text = {
                Column {
                    Text(
                        "کلید روی خود سرور هرمس ذخیره می‌شود، نه روی گوشی.",
                        style = MaterialTheme.typography.bodySmall, color = TextMid
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledField("کلید", keyValue, { keyValue = it },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        HermesRepo.saveKey(provider, keyValue)
                            .onSuccess { error = null }
                            .onFailure { error = it.message }
                        keyProvider = null; keyValue = ""; load()
                    }
                }) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = { keyProvider = null }) { Text("انصراف") } },
        )
    }
}

@Composable
private fun ProviderCard(
    p: JsonObject,
    onSelect: (String) -> Unit,
    onKey: () -> Unit,
    onDisconnect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val name = J.str(p, "id", J.str(p, "name", J.str(p, "provider")))
    val label = J.str(p, "label", J.str(p, "display_name", name))
    val connected = J.bool(p, "connected", J.bool(p, "authenticated", false))
    val models = J.listOf(p, "models").map { J.str(J.obj(it), "id", J.obj(it).toString()) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { expanded = !expanded }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(connected, if (connected) Lime else TextLow)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (connected) "متصل" else "متصل نیست",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) Lime else TextLow,
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, tint = TextLow
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            if (models.isNotEmpty()) {
                Text("مدل‌ها", style = MaterialTheme.typography.labelMedium, color = TextMid)
                Spacer(Modifier.height(6.dp))
                models.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(m) }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.ChevronLeft, null, Modifier.size(15.dp), tint = TextLow)
                        Spacer(Modifier.width(4.dp))
                        Text(m, style = MaterialTheme.typography.bodyMedium, color = TextHi)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("ثبت کلید", onKey, Modifier.weight(1f), icon = Icons.Default.VpnKey)
                if (connected) {
                    GhostButton("قطع اتصال", { onDisconnect(name) }, Modifier.weight(1f), color = Rose)
                }
            }
        }
    }
}
