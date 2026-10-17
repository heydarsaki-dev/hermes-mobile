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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.delay
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
    // پرووایدرهای ورود OAuth (Nous Portal و…) به‌همراه وضعیت ورودشان.
    var oauthProviders by remember { mutableStateOf<List<JsonObject>>(emptyList()) }

    var showAdd by remember { mutableStateOf(false) }
    var addModelTarget by remember { mutableStateOf<JsonObject?>(null) }
    var keyTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // slug to displayName
    var nousLogin by remember { mutableStateOf(false) }

    /** اعمالِ گزینهٔ مدل‌ها روی state صفحه (هم برای دادهٔ کش، هم برای دادهٔ تازه). */
    fun applyOptions(o: JsonObject) {
        currentProvider = J.str(o, "provider", "")
        currentModel = J.str(o, "model", "")
        providers = J.listOf(o, "providers").map { J.obj(it) }
            .filter { !J.bool(it, "is_user_defined") && J.str(it, "slug").lowercase() != "custom" }
    }

    /**
     * ادغام مدل‌های دریافت‌شدهٔ محلی با مدل‌های ثبت‌شدهٔ سرور.
     *
     * سرور فقط مدل پیش‌فرض هر endpoint را در تنظیمات نگه می‌دارد؛ فهرستی که
     * کاربر با «دریافت مدل‌ها» دیده بود محلی ذخیره می‌شود تا بعداً هم بتواند
     * بین همان مدل‌ها جابه‌جا شود.
     */
    fun withCachedModels(list: List<JsonObject>): List<JsonObject> = list.map { o ->
        val extra = HermesRepo.cachedEndpointModels(J.str(o, "id"))
        if (extra.isEmpty()) o
        else {
            val merged = (jsonStrings(J.listOf(o, "models")) + extra).distinct()
            JsonObject(o + ("models" to JsonArray(merged.map { JsonPrimitive(it) })))
        }
    }

    suspend fun load(refresh: Boolean = false) {
        // ابتدا دادهٔ کش‌شده را فوری نمایش می‌دهیم تا کاربر منتظر سرور
        // نماند؛ سپس در پس‌زمینه از هرمس تازه‌سازی می‌کنیم.
        val fromCache = if (!refresh) {
            HermesRepo.cachedModelOptions()?.also { applyOptions(it) }
            HermesRepo.cachedEndpoints()?.let { endpoints = withCachedModels(it.map { J.obj(it) }) }
        } else null
        // اگر کشی نبود (اولین بازدید)، حالتِ بارگذاری را نشان می‌دهیم تا
        // پیامِ «پرووایدری یافت نشد» به‌اشتباه نمایش داده نشود.
        loading = refresh || fromCache == null
        error = null
        HermesRepo.modelOptions(refresh)
            .onSuccess { r -> applyOptions(J.obj(r)) }
            .onFailure { error = it.message }
        HermesRepo.customEndpoints()
            .onSuccess { r ->
                endpoints = withCachedModels(J.listOf(J.obj(r), "endpoints").map { J.obj(it) })
            }
        loading = false
        // وضعیت OAuth جدا و بعد از نمایش فهرست می‌آید؛ چون سرور ممکن است برای
        // خواندن وضعیت Nous با پورتال صحبت کند و نباید صفحه را معطل کند.
        HermesRepo.oauthProviders()
            .onSuccess { r ->
                oauthProviders = J.listOf(J.obj(r), "providers").map { J.obj(it) }
            }
    }

    /**
     * انتخاب مدل.
     *
     * علاوه بر پیش‌فرض سرور، انتخاب کاربر روی دستگاه ذخیره می‌شود تا نشست‌های
     * بعدی همین مدل را به‌عنوان override بگیرند؛ چون هرمس مدل هر نشست را هنگام
     * ساخت آن ثابت می‌کند و `config.set` فقط پیش‌فرض سرور بود (علت اینکه مدل
     * انتخابی روی نشست‌ها ست نمی‌شد).
     */
    fun applyMain(provider: String, model: String, baseUrl: String = "") {
        scope.launch {
            val err = HermesRepo.applyModel(provider, model, baseUrl)
            if (err != null) error = err
            onModelChanged()
            load()
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
                        "با انتخاب هر مدل، همان مدل روی نشست‌های جدید ست می‌شود (نوبت جاری متوقف و نشست تازه با مدل جدید ساخته می‌شود). در صفحهٔ چت هم از نشان مدل بالای صفحه می‌توانید مدل را همین‌جا عوض کنید.",
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
            // ورود با Nous Portal (OAuth کد دستگاه) — جدا از کارت‌های پرووایدر
            // می‌آید چون ورودش با کلید API نیست و باید جلوتر و پررنگ‌تر دیده شود.
            // عمداً همیشه نمایش داده می‌شود؛ اگر فهرست OAuth دیر برسد یا خطا
            // بدهد، کاربر باز هم می‌تواند دکمهٔ ورود را ببیند.
            item("nous") {
                NousPortalCard(
                    info = oauthProviders.firstOrNull { J.str(it, "id") == "nous" }
                        ?: JsonObject(emptyMap()),
                    nousModels = providers.firstOrNull { J.str(it, "slug") == "nous" }
                        ?.let { jsonStrings(J.listOf(it, "models")) } ?: emptyList(),
                    isActive = currentProvider == "nous",
                    onLogin = { nousLogin = true },
                    onSelectModel = { m -> applyMain("nous", m) },
                    onLogout = {
                        scope.launch {
                            HermesRepo.oauthDisconnect("nous")
                                .onSuccess { load(refresh = true) }
                                .onFailure { error = it.message }
                        }
                    },
                )
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
                                .onSuccess {
                                    // انتخاب کاربر ثبت می‌شود تا نشست تازه با همین مدل ساخته شود
                                    val eid = J.str(e, "id")
                                    HermesRepo.applyModel(eid, J.str(e, "model"), J.str(e, "base_url"))
                                    onModelChanged()
                                    load()
                                }
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
            onNousLogin = { showAdd = false; nousLogin = true },
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

    if (nousLogin) {
        NousLoginDialog(
            onDismiss = { nousLogin = false },
            onLoggedIn = {
                nousLogin = false
                scope.launch {
                    // بعد از ورود، هرمس مدل پیشنهادیِ مناسب (رایگان/پرداختی) را
                    // خودش انتخاب می‌کند؛ بعد هم کاربر می‌تواند از کارت Nous
                    // بین بقیهٔ مدل‌ها جابه‌جا شود.
                    val rec = HermesRepo.recommendedDefault("nous").getOrNull()
                    val m = rec?.let { J.str(J.obj(it), "model") }.orEmpty()
                    if (m.isNotBlank()) {
                        HermesRepo.applyModel("nous", m)
                        onModelChanged()
                    }
                    load(refresh = true)
                }
            },
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

/**
 * کارت اختصاصی «Nous Portal».
 *
 * Nous با کلید API ساده کار نمی‌کند و ورودش OAuth «کد دستگاه» است؛ برای همین
 * جدا از کارت‌های پرووایدر می‌آید تا جای ورود گم نشود.
 */
@Composable
private fun NousPortalCard(
    info: JsonObject,
    nousModels: List<String>,
    isActive: Boolean,
    onLogin: () -> Unit,
    onSelectModel: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    val status = J.obj(info["status"])
    val loggedIn = J.bool(status, "logged_in")
    val source = J.str(status, "source_label", "Nous Portal")
    val token = J.str(status, "token_preview")

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { expanded = !expanded }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(loggedIn, if (loggedIn) Lime else TextLow)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Nous Portal", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (loggedIn) "وارد شده‌اید" else "وارد نشده‌اید",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (loggedIn) Lime else TextLow,
                )
                if (loggedIn && source.isNotBlank()) {
                    Text(
                        if (token.isNotBlank()) "$source — $token" else source,
                        style = MaterialTheme.typography.bodySmall, color = TextLow, maxLines = 1,
                    )
                }
            }
            if (isActive) Pill("فعال", Gold)
            Spacer(Modifier.width(6.dp))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextLow)
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            if (!loggedIn) {
                Text(
                    "با حساب Nous Research وارد شوید تا از مدل‌های آن استفاده کنید. هرمس یک کد به شما می‌دهد و شما آن را در صفحهٔ Nous وارد می‌کنید.",
                    style = MaterialTheme.typography.bodySmall, color = TextMid,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("ورود با Nous", onLogin, Modifier.fillMaxWidth(), icon = Icons.Default.Login)
            } else {
                Text("مدل‌های Nous", style = MaterialTheme.typography.labelMedium, color = TextMid)
                Spacer(Modifier.height(4.dp))
                ModelList(
                    nousModels, onSelectModel,
                    "فهرست مدل‌ها هنوز نیامده — بالا «تازه‌سازی» را بزنید",
                    limit = 20,
                )
                Spacer(Modifier.height(10.dp))
                GhostButton("خروج از حساب", onLogout, Modifier.fillMaxWidth(), color = Rose, icon = Icons.Default.Logout)
            }
        }
    }
}

/**
 * دیالوگ ورود به Nous Portal با جریان «کد دستگاه».
 *
 * ۱) سرور کد و نشانی تأیید را می‌سازد؛ ۲) کاربر کد را در صفحهٔ Nous وارد
 * می‌کند؛ ۳) اپ هر دو ثانیه وضعیت را می‌پرسد تا «approved» شود. توکن‌ها
 * سمت سرور ذخیره می‌شوند و اپ فقط وضعیت را می‌بیند.
 */
@Composable
private fun NousLoginDialog(onDismiss: () -> Unit, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var phase by remember { mutableStateOf("starting") } // starting|waiting|done|error
    var userCode by remember { mutableStateOf("") }
    var verifyUrl by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    fun start() {
        phase = "starting"; message = null; sessionId = ""
        scope.launch {
            HermesRepo.oauthStart("nous")
                .onSuccess { r ->
                    val o = J.obj(r)
                    sessionId = J.str(o, "session_id")
                    userCode = J.str(o, "user_code")
                    verifyUrl = J.str(o, "verification_url")
                    phase = "waiting"
                }
                .onFailure {
                    message = it.message ?: "آغاز ورود ناموفق بود"
                    phase = "error"
                }
        }
    }

    fun cancel() {
        if (sessionId.isNotBlank()) scope.launch { HermesRepo.oauthCancel(sessionId) }
    }

    LaunchedEffect(Unit) { start() }

    // کد دستگاه معمولاً چند ثانیه‌ای تأیید می‌شود؛ تا ۱۵ دقیقه صبر می‌کنیم.
    LaunchedEffect(phase, sessionId) {
        if (phase != "waiting" || sessionId.isBlank()) return@LaunchedEffect
        var tries = 0
        while (phase == "waiting" && tries < 450) {
            delay(2000); tries++
            val o = HermesRepo.oauthPoll("nous", sessionId).getOrNull()?.let { J.obj(it) }
            when (o?.let { J.str(it, "status") }) {
                "approved" -> { phase = "done"; onLoggedIn(); return@LaunchedEffect }
                "denied" -> { message = "ورود لغو شد"; phase = "error"; return@LaunchedEffect }
                "expired" -> { message = "کد منقضی شد — دوباره تلاش کنید"; phase = "error"; return@LaunchedEffect }
                "error" -> {
                    val m = J.str(o, "error_message")
                    message = if (m.isBlank()) "ورود ناموفق بود" else "ورود ناموفق بود: $m"
                    phase = "error"; return@LaunchedEffect
                }
            }
        }
        if (phase == "waiting") {
            message = "زمان ورود به پایان رسید — دوباره تلاش کنید"
            phase = "error"
        }
    }

    AlertDialog(
        onDismissRequest = { cancel(); onDismiss() },
        title = { Text("ورود به Nous Portal") },
        text = {
            Column {
                when (phase) {
                    "starting" -> LoadingRow("در حال ساخت کد ورود…")
                    "error" -> Text(
                        message ?: "خطای نامشخص",
                        style = MaterialTheme.typography.bodySmall, color = Rose,
                    )
                    "done" -> Text(
                        "✓ ورود با موفقیت انجام شد — مدل پیش‌فرض Nous ست شد.",
                        style = MaterialTheme.typography.bodySmall, color = Cyan,
                    )
                    else -> {
                        Text(
                            "این کد را در صفحهٔ Nous وارد کنید:",
                            style = MaterialTheme.typography.bodySmall, color = TextMid,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                userCode, style = MaterialTheme.typography.titleLarge,
                                color = Gold, modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(userCode)); copied = true
                            }) {
                                Icon(
                                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    "کپی کد", tint = if (copied) Lime else TextMid,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton(
                            "باز کردن صفحهٔ Nous", { runCatching { uri.openUri(verifyUrl) } },
                            Modifier.fillMaxWidth(),
                            enabled = verifyUrl.isNotBlank(),
                            icon = Icons.Default.OpenInNew,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = Cyan,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "در انتظار تأیید شما در صفحهٔ Nous…",
                                style = MaterialTheme.typography.bodySmall, color = TextMid,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                "error" -> TextButton(onClick = { start() }) { Text("تلاش دوباره") }
                "done" -> TextButton(onClick = onDismiss) { Text("باشه") }
                else -> TextButton(onClick = { cancel(); onDismiss() }) { Text("انصراف") }
            }
        },
    )
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
                    GhostButton("متصل", onActivate, Modifier.weight(1f), color = Lime, icon = Icons.Default.Check)
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

/** ترجمهٔ پیام‌های انگلیسی endpoint به فارسی، تا همهٔ متن‌ها یکدست باشد. */
private fun faProbeMessage(raw: String): String {
    val m = raw.trim()
    return when {
        m.isBlank() -> "اتصال برقرار نشد"
        m.contains("Enter an endpoint URL", true) -> "اول آدرس endpoint را وارد کنید"
        m.contains("rejected the API key", true) -> "endpoint کلید API را رد کرد — کلید را بررسی کنید"
        m.contains("Could not reach", true) -> "به endpoint دسترسی پیدا نشد — آدرس و اتصال شبکه را بررسی کنید"
        m.startsWith("Endpoint returned HTTP", true) ->
            "endpoint پاسخ HTTP ${m.substringAfterLast("HTTP").trim()} را برگرداند"
        else -> m
    }
}

/**
 * انتخابگر مدل‌های دریافت‌شده از endpoint.
 *
 * فهرست واقعی پرووایدرها می‌تواند صدها مدل باشد؛ پس برای فهرست‌های بلند یک
 * کادر جست‌وجو می‌آید و خودِ فهرست داخل نواحیِ محدود و اسکرول‌شونده نمایش داده
 * می‌شود تا دیالوگ از صفحه بیرون نزند.
 */
@Composable
private fun FetchedModelsPicker(
    models: List<String>,
    selected: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val shown = remember(models, query) {
        if (query.isBlank()) models
        else models.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "مدل‌های دریافت‌شده", Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium, color = TextMid,
        )
        Text("${faNum(models.size)} مدل", style = MaterialTheme.typography.bodySmall, color = TextLow)
    }
    if (models.size > 8) {
        Spacer(Modifier.height(6.dp))
        LabeledField(
            "جست‌وجوی مدل", query, onQueryChange,
            placeholder = "بخشی از نام مدل…",
            trailing = { Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = TextLow) },
        )
    }
    Spacer(Modifier.height(6.dp))
    // فهرست مدل‌ها می‌تواند صدها ردیف باشد؛ LazyColumn فقط ردیف‌های دیده‌شده
    // را می‌سازد تا دیالوگ هنگام تایپ در جست‌وجو کند نشود.
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 176.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(vertical = 4.dp)
    ) {
        if (shown.isEmpty()) {
            item("empty") {
                Text(
                    "مدلی با این نام پیدا نشد",
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = TextLow,
                )
            }
        }
        items(shown) { m ->
            val active = m == selected
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(m) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (active) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    null, Modifier.size(17.dp), tint = if (active) Gold else TextLow,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    m, Modifier.weight(1f), maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) Gold else TextHi,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onNousLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var apiMode by remember { mutableStateOf("") }
    var extraModels by remember { mutableStateOf("") }
    var fetchMsg by remember { mutableStateOf<String?>(null) }
    var fetchBusy by remember { mutableStateOf(false) }
    var saveBusy by remember { mutableStateOf(false) }
    var dlgError by remember { mutableStateOf<String?>(null) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelQuery by remember { mutableStateOf("") }

    val modes = listOf("" to "خودکار", "chat_completions" to "Chat", "anthropic_messages" to "Anthropic", "codex_responses" to "Responses")

    fun parsedModels(): List<String> =
        extraModels.split('\n', ',').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    /** دریافت فهرست مدل‌های endpoint با آدرس/کلیدِ همین فرم، برای انتخاب مدل پیش‌فرض. */
    fun fetchModels() {
        if (fetchBusy || saveBusy) return
        scope.launch {
            fetchBusy = true; fetchMsg = null; dlgError = null
            HermesRepo.validateCustomEndpoint(
                name = name.trim().ifBlank { "probe" },
                baseUrl = baseUrl.trim(), model = model.trim(),
                apiKey = apiKey.trim(), apiMode = apiMode,
            ).onSuccess { r ->
                val o = J.obj(r)
                val list = jsonStrings(J.listOf(o, "models")).distinct()
                val ok = J.bool(o, "ok")
                val reachable = J.bool(o, "reachable", true)
                fetchedModels = list
                // اگر کاربر هنوز مدلی ننوشته، اولین مدلِ endpoint را همان‌جا
                // انتخاب می‌کنیم تا ذخیره یک‌کلیکی شود؛ باقی مدل‌ها هم در
                // فهرست می‌مانند تا در صورت نیاز عوضش کند.
                if (model.isBlank() && list.isNotEmpty()) model = list.first()
                fetchMsg = when {
                    ok && list.isNotEmpty() && list.size > 1 ->
                        "✓ اتصال برقرار شد — ${faNum(list.size)} مدل دریافت شد؛ یکی را به‌عنوان مدل پیش‌فرض انتخاب کنید."
                    ok && list.isNotEmpty() ->
                        "✓ اتصال برقرار شد — تنها مدل endpoint «${list.first()}» است و انتخاب شد."
                    ok ->
                        "✓ اتصال برقرار شد، ولی endpoint فهرست مدلی اعلام نکرد؛ مدل را دستی وارد کنید."
                    !reachable ->
                        "✗ به endpoint دسترسی پیدا نشد؛ آدرس و اتصال شبکه را بررسی کنید. (می‌توانید مدل را دستی وارد و ذخیره کنید.)"
                    else -> "✗ ${faProbeMessage(J.str(o, "message"))}"
                }
            }.onFailure { dlgError = it.message }
            fetchBusy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saveBusy) onDismiss() },
        title = { Text("افزودن پرووایدر سفارشی") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "یک endpoint سازگار با OpenAI اضافه کنید. آدرس و کلید را که وارد کردید، «دریافت مدل‌ها» فهرست مدل‌های endpoint را می‌آورد تا یکی را به‌عنوان مدل پیش‌فرض انتخاب کنید.",
                    style = MaterialTheme.typography.bodySmall, color = TextMid,
                )
                Spacer(Modifier.height(12.dp))
                // راه دوم افزودن پرووایدر: ورود با Nous Research (OAuth، بدون کلید).
                GhostButton(
                    "ورود با حساب Nous Research",
                    onNousLogin,
                    Modifier.fillMaxWidth(),
                    color = Gold,
                    icon = Icons.Default.Login,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "اگر می‌خواهی از مدل‌های Nous استفاده کنی، همین‌جا با حسابت وارد شو؛ نیازی به آدرس و کلید نیست.",
                    style = MaterialTheme.typography.bodySmall, color = TextLow,
                )
                Spacer(Modifier.height(12.dp))
                LabeledField("نام پرووایدر", name, { name = it }, placeholder = "My Provider")
                Spacer(Modifier.height(10.dp))
                LabeledField("آدرس endpoint", baseUrl, { baseUrl = it }, placeholder = "https://api.example.com/v1")
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    "کلید API (اختیاری)", apiKey, { apiKey = it },
                    placeholder = "sk-…",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(12.dp))
                GhostButton(
                    if (fetchBusy) "در حال دریافت مدل‌ها…" else "دریافت مدل‌ها",
                    ::fetchModels,
                    Modifier.fillMaxWidth(),
                    color = Cyan,
                    icon = Icons.Default.CloudDownload,
                )
                fetchMsg?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (msg.startsWith("✓")) Cyan else Rose,
                    )
                }
                if (fetchedModels.isNotEmpty()) {
                    FetchedModelsPicker(
                        models = fetchedModels,
                        selected = model.trim(),
                        query = modelQuery,
                        onQueryChange = { modelQuery = it },
                        onSelect = { model = it },
                    )
                }
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    "مدل پیش‌فرض", model, { model = it },
                    placeholder = "gpt-4o-mini",
                    trailing = if (model.isNotBlank()) {
                        { Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = Lime) }
                    } else null,
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
                dlgError?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Rose)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saveBusy && !fetchBusy && name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
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
                        ).onSuccess { r ->
                            // فهرست دریافت‌شده محلی نگه داشته می‌شود تا بعد از
                            // ذخیره هم بتوان از کارت پرووایدر بین مدل‌ها جابه‌جا شد.
                            HermesRepo.saveEndpointModels(J.str(J.obj(r), "id"), fetchedModels)
                            onSaved()
                        }
                            .onFailure { dlgError = it.message }
                        saveBusy = false
                    }
                },
            ) { Text(if (saveBusy) "در حال ذخیره…" else "ذخیره و اتصال") }
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
