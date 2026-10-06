package ir.hermes.mobile.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * صفحهٔ «مدل و پرووایدر».
 *
 * دو دسته پرووایدر نمایش داده می‌شود:
 *  - پرووایدرهای شناخته‌شدهٔ هرمس (از `model.options`) که با `model.save_key` کلید می‌گیرند.
 *  - پرووایدرهای سفارشی/endpoint (از REST `/api/providers/custom-endpoints`) که می‌توان
 *    ساخت، فعال (وصل به هرمس)، و حذف کرد.
 *
 * نکتهٔ مهم: افزودن پرووایدر جدید در هرمس از طریق REST داشبورد انجام می‌شود، نه
 * `config.set`؛ چون `config.set` فقط کلیدهای ثابت را می‌پذیرد و پرووایدر نمی‌سازد.
 */
@Composable
fun ModelScreen(onBack: () -> Unit, onModelChanged: () -> Unit = {}) {
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentProvider by remember { mutableStateOf("") }
    var currentModel by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var endpoints by remember { mutableStateOf<List<JsonObject>>(emptyList()) }

    var showAdd by remember { mutableStateOf(false) }
    var addModelTarget by remember { mutableStateOf<JsonObject?>(null) }
    var keyTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // slug to displayName

    suspend fun load(refresh: Boolean = false) {
        loading = true; error = null
        HermesRepo.modelOptions(refresh)
            .onSuccess { r ->
                val o = J.obj(r)
                currentProvider = J.str(o, "provider", "")
                currentModel = J.str(o, "model", "")
                providers = J.listOf(o, "providers").map { J.obj(it) }
                    .filter { !J.bool(it, "is_user_defined") && J.str(it, "slug").lowercase() != "custom" }
            }
            .onFailure { error = it.message }
        HermesRepo.customEndpoints()
            .onSuccess { r -> endpoints = J.listOf(J.obj(r), "endpoints").map { J.obj(it) } }
        loading = false
    }

    // انتخاب مدل اصلی؛ اگر هرمس برای مدل گران درخواست تأیید کند، همان پیام را نشان می‌دهیم.
    fun applyMain(provider: String, model: String, baseUrl: String = "") {
        scope.launch {
            HermesRepo.setMainModel(provider, model, baseUrl)
                .onSuccess { r ->
                    val o = J.obj(r)
                    if (J.bool(o, "confirm_required")) {
                        error = J.str(o, "confirm_message", "انتخاب این مدل نیاز به تأیید دارد")
                    } else {
                        HermesRepo.clearSession(); onModelChanged(); load()
                    }
                }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopBar("مدل و پرووایدر", onBack) {
            IconButton(onClick = { scope.launch { load(refresh = true) } }) {
                Icon(Icons.Default.Refresh, "تازه‌سازی", tint = TextMid)
            }
            IconButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, "افزودن پرووایدر", tint = Gold)
            }
        }
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let { msg ->
                item("err") { ErrorBanner(msg) { scope.launch { load(refresh = true) } } }
            }

            item("active") {
                GlassCard(Modifier.fillMaxWidth()) {
                    SectionTitle("مدل فعال هرمس")
                    Spacer(Modifier.height(6.dp))
                    if (currentModel.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp), tint = Gold)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(currentModel, style = MaterialTheme.typography.titleSmall, color = Gold)
                                if (currentProvider.isNotBlank()) {
                                    Text(currentProvider, style = MaterialTheme.typography.bodySmall, color = TextMid)
                                }
                            }
                        }
                    } else {
                        Text("مدل فعالی از سرور خوانده نشد", style = MaterialTheme.typography.bodySmall, color = TextMid)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "تغییر مدل روی نشست‌های جدید اعمال می‌شود؛ در چت با دکمهٔ «نشست جدید» یک گفت‌وگوی تازه بسازید.",
                        style = MaterialTheme.typography.bodySmall, color = TextLow,
                    )
                }
            }

            if (loading) {
                item("loading") { LoadingRow() }
            }

            item("providers-title") {
                SectionTitle("پرووایدرهای هرمس") {
                    SmallTextButton("افزودن پرووایدر جدید") { showAdd = true }
                }
            }
            if (providers.isEmpty() && !loading) {
                item("providers-empty") {
                    EmptyState("پرووایدر شناخته‌شده‌ای یافت نشد — یکی بسازید", Icons.Default.CloudOff)
                }
            }
            items(providers, key = { "c:${J.str(it, "slug", J.str(it, "name"))}" }) { p ->
                CanonicalProviderCard(
                    p = p,
                    isActive = J.bool(p, "is_current") ||
                        (currentProvider.isNotBlank() && J.str(p, "slug") == currentProvider),
                    onSelectModel = { model -> applyMain(J.str(p, "slug"), model) },
                    onSetKey = {
                        keyTarget = J.str(p, "slug") to J.str(p, "name", J.str(p, "slug"))
                    },
                    onDisconnect = {
                        scope.launch {
                            HermesRepo.disconnectProvider(J.str(p, "slug"))
                                .onFailure { error = it.message }
                            load()
                        }
                    },
                )
            }

            item("custom-title") { SectionTitle("پرووایدرهای سفارشی (endpoint)") }
            if (endpoints.isEmpty() && !loading) {
                item("custom-empty") {
                    EmptyState("هنوز endpoint سفارشی نساخته‌اید", Icons.Default.Dns)
                }
            }
            items(endpoints, key = { "e:${J.str(it, "id", J.str(it, "name"))}" }) { e ->
                CustomEndpointCard(
                    e = e,
                    onActivate = {
                        scope.launch {
                            HermesRepo.activateCustomEndpoint(J.str(e, "id"))
                                .onSuccess { HermesRepo.clearSession(); onModelChanged(); load() }
                                .onFailure { error = it.message }
                        }
                    },
                    onAddModel = { addModelTarget = e },
                    onSelectModel = { model -> applyMain(J.str(e, "id"), model, J.str(e, "base_url")) },
                    onDelete = {
                        scope.launch {
                            HermesRepo.deleteCustomEndpoint(J.str(e, "id"))
                                .onSuccess { load() }
                                .onFailure { error = it.message }
                        }
                    },
                )
            }

            item("spacer") { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (showAdd) {
        AddProviderDialog(
            onDismiss = { showAdd = false },
            onSaved = {
                showAdd = false
                HermesRepo.clearSession(); onModelChanged()
                scope.launch { load(refresh = true) }
            },
        )
    }

    addModelTarget?.let { e ->
        AddModelDialog(
            endpoint = e,
            onDismiss = { addModelTarget = null },
            onSaved = { addModelTarget = null; scope.launch { load(refresh = true) } },
        )
    }

    keyTarget?.let { (slug, name) ->
        KeyDialog(
            slug = slug,
            title = name,
            onDismiss = { keyTarget = null },
            onSaved = { keyTarget = null; scope.launch { load(refresh = true) } },
        )
    }
}

@Composable
private fun SmallTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** فهرست مدل‌ها به‌صورت قابل کلیک؛ حداکثر [limit] مورد اول برای جلوگیری از فهرست‌های غول‌آسا. */
@Composable
private fun ModelList(models: List<String>, onSelect: (String) -> Unit, emptyText: String, limit: Int = 15) {
    if (models.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodySmall, color = TextLow)
        return
    }
    models.take(limit).forEach { m ->
        Row(
            Modifier.fillMaxWidth().clickable { onSelect(m) }.padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ChevronLeft, null, Modifier.size(15.dp), tint = TextLow)
            Spacer(Modifier.width(4.dp))
            Text(m, style = MaterialTheme.typography.bodyMedium, color = TextHi, modifier = Modifier.weight(1f))
        }
    }
    if (models.size > limit) {
        Text("و ${faNum(models.size - limit)} مدل دیگر…", style = MaterialTheme.typography.bodySmall, color = TextLow)
    }
}

@Composable
private fun CanonicalProviderCard(
    p: JsonObject,
    isActive: Boolean,
    onSelectModel: (String) -> Unit,
    onSetKey: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var expanded by remember { mutableStateOf(isActive) }
    val slug = J.str(p, "slug", J.str(p, "name"))
    val label = J.str(p, "name", slug)
    val connected = J.bool(p, "authenticated")
    val authType = J.str(p, "auth_type")
    val keyEnv = J.str(p, "key_env")
    val warning = J.str(p, "warning")
    val models = jsonStrings(J.listOf(p, "models"))

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
                if (warning.isNotBlank() && !connected) {
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = Amber)
                }
            }
            if (isActive) Pill("فعال", Gold)
            Spacer(Modifier.width(6.dp))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextLow)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text("مدل‌ها", style = MaterialTheme.typography.labelMedium, color = TextMid)
            Spacer(Modifier.height(4.dp))
            ModelList(models, onSelectModel, "فهرست مدل‌ها خالی است")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (authType == "api_key" || keyEnv.isNotBlank()) {
                    GhostButton("ثبت کلید", onSetKey, Modifier.weight(1f), icon = Icons.Default.VpnKey)
                }
                if (connected) {
                    GhostButton("قطع اتصال", onDisconnect, Modifier.weight(1f), color = Rose)
                }
            }
        }
    }
}

@Composable
private fun CustomEndpointCard(
    e: JsonObject,
    onActivate: () -> Unit,
    onAddModel: () -> Unit,
    onSelectModel: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    val id = J.str(e, "id", J.str(e, "name"))
    val label = J.str(e, "name", id)
    val baseUrl = J.str(e, "base_url")
    val isCurrent = J.bool(e, "is_current")
    val hasKey = J.bool(e, "has_api_key")
    val models = jsonStrings(J.listOf(e, "models"))

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(isCurrent, if (isCurrent) Lime else Cyan)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                if (baseUrl.isNotBlank()) {
                    Text(baseUrl, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1)
                }
                Text(
                    buildString {
                        append(if (hasKey) "کلید ذخیره شده" else "بدون کلید")
                        append(" • ")
                        append(faNum(models.size))
                        append(" مدل")
                    },
                    style = MaterialTheme.typography.bodySmall, color = TextLow,
                )
            }
            if (isCurrent) Pill("فعال", Gold) else Pill("سفارشی", Cyan)
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, tint = TextLow,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text("مدل‌ها", style = MaterialTheme.typography.labelMedium, color = TextMid)
            Spacer(Modifier.height(4.dp))
            ModelList(models, onSelectModel, "مدلی ثبت نشده — مدل جدید اضافه کنید")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isCurrent) {
                    PrimaryButton("اتصال به هرمس", onActivate, Modifier.weight(1f), icon = Icons.Default.Link)
                } else {
                    GhostButton("وصل‌شده", onActivate, Modifier.weight(1f), color = Lime, icon = Icons.Default.Check)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("افزودن مدل", onAddModel, Modifier.weight(1f), icon = Icons.Default.Add)
                GhostButton("حذف", { confirmDelete = true }, Modifier.weight(1f), color = Rose, icon = Icons.Default.Delete)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف پرووایدر سفارشی") },
            text = { Text("«$label» و کلید ذخیره‌شدهٔ آن حذف می‌شود. مطمئنید؟") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("حذف", color = Rose) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("انصراف") } },
        )
    }
}

/** استخراج فهرست شناسه‌های مدل از یک آرایهٔ JSON (رشته یا آبجکت با id/name). */
private fun jsonStrings(arr: JsonArray): List<String> =
    arr.mapNotNull { el ->
        (el as? JsonPrimitive)?.content
            ?: (el as? JsonObject)?.let { J.str(it, "id", J.str(it, "name")) }
    }.filter { it.isNotBlank() }

// ───────────────────────── دیالوگ‌ها ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var apiMode by remember { mutableStateOf("") }
    var extraModels by remember { mutableStateOf("") }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var testBusy by remember { mutableStateOf(false) }
    var saveBusy by remember { mutableStateOf(false) }
    var dlgError by remember { mutableStateOf<String?>(null) }

    val modes = listOf("" to "خودکار", "chat_completions" to "Chat", "anthropic_messages" to "Anthropic", "codex_responses" to "Responses")

    fun parsedModels(): List<String> =
        extraModels.split('\n', ',').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    AlertDialog(
        onDismissRequest = { if (!saveBusy) onDismiss() },
        title = { Text("افزودن پرووایدر سفارشی") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "یک endpoint سازگار با OpenAI اضافه کنید. با «ذخیره و اتصال» هم پرووایدر ساخته می‌شود و هم به‌عنوان مدل فعال هرمس تنظیم می‌شود.",
                    style = MaterialTheme.typography.bodySmall, color = TextMid,
                )
                Spacer(Modifier.height(12.dp))
                LabeledField("نام پرووایدر", name, { name = it }, placeholder = "My Provider")
                Spacer(Modifier.height(10.dp))
                LabeledField("آدرس endpoint", baseUrl, { baseUrl = it }, placeholder = "https://api.example.com/v1")
                Spacer(Modifier.height(10.dp))
                LabeledField("مدل پیش‌فرض", model, { model = it }, placeholder = "gpt-4o-mini")
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    "کلید API (اختیاری)", apiKey, { apiKey = it },
                    placeholder = "sk-…",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(12.dp))
                Text("حالت API", style = MaterialTheme.typography.labelMedium, color = TextMid)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    modes.forEach { (value, labelText) ->
                        FilterChip(
                            selected = apiMode == value,
                            onClick = { apiMode = value },
                            label = { Text(labelText, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    "مدل‌های بیشتر (هر خط یک مدل)", extraModels, { extraModels = it },
                    singleLine = false, placeholder = "model-a\nmodel-b",
                )
                testMsg?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Cyan)
                }
                dlgError?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Rose)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !testBusy && !saveBusy && baseUrl.isNotBlank(),
                    onClick = {
                        scope.launch {
                            testBusy = true; testMsg = null; dlgError = null
                            HermesRepo.validateCustomEndpoint(
                                name = name.ifBlank { "probe" },
                                baseUrl = baseUrl, model = model, apiKey = apiKey, apiMode = apiMode,
                            ).onSuccess { r ->
                                val o = J.obj(r)
                                val models = jsonStrings(J.listOf(o, "models"))
                                val ok = J.bool(o, "ok")
                                val msg = J.str(o, "message")
                                testMsg = when {
                                    ok -> "✓ اتصال برقرار شد — ${faNum(models.size)} مدل پیدا شد" +
                                        if (models.isNotEmpty()) ":\n" + models.take(8).joinToString("، ") else ""
                                    msg.isNotBlank() -> "✗ $msg"
                                    else -> "✗ اتصال برقرار نشد"
                                }
                            }.onFailure { dlgError = it.message }
                            testBusy = false
                        }
                    },
                ) { Text(if (testBusy) "در حال تست…" else "تست") }
                TextButton(
                    enabled = !saveBusy && !testBusy && name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
                    onClick = {
                        scope.launch {
                            saveBusy = true; dlgError = null
                            HermesRepo.addCustomEndpoint(
                                name = name.trim(),
                                baseUrl = baseUrl.trim(),
                                model = model.trim(),
                                apiKey = apiKey.trim(),
                                apiMode = apiMode,
                                models = parsedModels(),
                                makeDefault = true,
                            ).onSuccess { onSaved() }
                                .onFailure { dlgError = it.message }
                            saveBusy = false
                        }
                    },
                ) { Text(if (saveBusy) "در حال ذخیره…" else "ذخیره و اتصال") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

@Composable
private fun AddModelDialog(endpoint: JsonObject, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val id = J.str(endpoint, "id")
    val name = J.str(endpoint, "name", id)
    val baseUrl = J.str(endpoint, "base_url")
    val defaultModel = J.str(endpoint, "model")
    var model by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dlgError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("افزودن مدل به $name") },
        text = {
            Column {
                Text(
                    "شناسهٔ مدل همان‌طور که endpoint می‌شناسد وارد کنید (مثلاً gpt-4o-mini).",
                    style = MaterialTheme.typography.bodySmall, color = TextMid,
                )
                Spacer(Modifier.height(12.dp))
                LabeledField("شناسه مدل", model, { model = it }, placeholder = "model-id")
                dlgError?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Rose)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && model.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true; dlgError = null
                        // مدل پیش‌فرض دست‌نخورده می‌ماند؛ فقط مدل جدید به فهرست اضافه می‌شود.
                        HermesRepo.addCustomEndpoint(
                            id = id,
                            name = name,
                            baseUrl = baseUrl,
                            model = defaultModel.ifBlank { model.trim() },
                            models = listOf(model.trim()),
                            makeDefault = false,
                        ).onSuccess { onSaved() }
                            .onFailure { dlgError = it.message }
                        busy = false
                    }
                },
            ) { Text(if (busy) "در حال ذخیره…" else "افزودن") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

@Composable
private fun KeyDialog(slug: String, title: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var keyValue by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dlgError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("کلید API — $title") },
        text = {
            Column {
                Text(
                    "کلید روی خود سرور هرمس ذخیره می‌شود، نه روی گوشی.",
                    style = MaterialTheme.typography.bodySmall, color = TextMid,
                )
                Spacer(Modifier.height(12.dp))
                LabeledField(
                    "کلید", keyValue, { keyValue = it },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                dlgError?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Rose)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && keyValue.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true; dlgError = null
                        HermesRepo.saveKey(slug, keyValue.trim())
                            .onSuccess { onSaved() }
                            .onFailure { dlgError = it.message }
                        busy = false
                    }
                },
            ) { Text(if (busy) "در حال ذخیره…" else "ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}
