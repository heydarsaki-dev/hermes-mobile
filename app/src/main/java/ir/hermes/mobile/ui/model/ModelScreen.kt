package ir.hermes.mobile.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * صفحهٔ «مدل و پرووایدر» با چیدمانِ یکدست.
 *
 * هر پرووایدر (شناخته‌شده، Nous Research، یا سفارشی) یک ردیفِ جمع‌شوندهٔ
 * یکسان دارد: نقطهٔ وضعیت، نام، یک خط توضیح، و نشانِ «فعال». با لمسِ ردیف
 * فهرست مدل‌ها و دکمه‌های عمل باز می‌شود. این کار هم صفحه را خلوت می‌کند و هم
 * تکرارِ قبلیِ «Nous Portal» (کارتِ اختصاصی + ردیفِ معمولی) را از بین می‌برد.
 *
 * بارگذاری عمداً موازی است: `model.options`، endpointهای سفارشی و وضعیت OAuth
 * هم‌زمان خوانده می‌شوند تا زمانِ انتظار جمعِ آن‌ها نباشد بلکه بیشترین‌شان باشد.
 */
@Composable
fun ModelScreen(onBack: () -> Unit, onModelChanged: () -> Unit = {}) {
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentProvider by remember { mutableStateOf("") }
    var currentModel by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var endpoints by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    // پرووایدرهای ورود OAuth (Nous Portal و…) به‌همراه وضعیت ورودشان.
    var oauthProviders by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    // مدل‌های Nous جدا نگه داشته می‌شوند؛ چون ردیفِ مخصوص خودش را دارد و از
    // فهرستِ پرووایدرهای معمولی حذف می‌شود تا دوبار دیده نشود. مقدار اولیه از
    // کشِ محلی می‌آید تا صفحه بدون انتظار برای سرور، مدل‌ها را نشان دهد.
    var nousModels by remember { mutableStateOf(HermesRepo.cachedEndpointModels("nous")) }

    var showAdd by remember { mutableStateOf(false) }
    var addModelTarget by remember { mutableStateOf<JsonObject?>(null) }
    var keyTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // slug to displayName
    var nousLogin by remember { mutableStateOf(false) }
    // وضعیتِ باز/بستهٔ هر ردیف، با کلیدِ پایدار؛ تا وقتی که کاربر خودش عوض کند،
    // ردیفِ فعال به‌طور پیش‌فرض باز است.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    /** اعمالِ گزینهٔ مدل‌ها روی state صفحه (هم برای دادهٔ کش، هم برای دادهٔ تازه). */
    fun applyOptions(o: JsonObject) {
        currentProvider = J.str(o, "provider", "")
        currentModel = J.str(o, "model", "")
        val all = J.listOf(o, "providers").map { J.obj(it) }
            .filter { !J.bool(it, "is_user_defined") && J.str(it, "slug").lowercase() != "custom" }
        // مدل‌های Nous را برمی‌داریم و بعد ردیفش را از فهرستِ معمولی حذف
        // می‌کنیم؛ وگرنه Nous دو بار (کارت + ردیف) نمایش داده می‌شود.
        val fresh = all.firstOrNull { J.str(it, "slug").lowercase() == "nous" }
            ?.let { jsonStrings(J.listOf(it, "models")) } ?: emptyList()
        if (fresh.isNotEmpty()) {
            HermesRepo.saveEndpointModels("nous", fresh)
            nousModels = (fresh + HermesRepo.cachedEndpointModels("nous")).distinct()
        }
        providers = all.filter { J.str(it, "slug").lowercase() != "nous" }
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

    /**
     * بارگذاریِ موازیِ دادهٔ صفحه.
     *
     * ابتدا کشِ محلی فوراً نشان داده می‌شود تا کاربر منتظر سرور نماند؛ بعد هر
     * سه فراخوانِ سرور هم‌زمان اجرا می‌شوند (نه پشتِ سرِ هم).
     */
    suspend fun load(refresh: Boolean = false) {
        var hadCache = false
        if (!refresh) {
            HermesRepo.cachedModelOptions()?.also { hadCache = true; applyOptions(it) }
            HermesRepo.cachedEndpoints()?.let { endpoints = withCachedModels(it.map { e -> J.obj(e) }) }
        }
        // اگر کشی نبود (اولین بازدید یا تازه‌سازیِ دستی)، حالتِ بارگذاری را
        // نشان می‌دهیم تا پیامِ «یافت نشد» به‌اشتباه ظاهر نشود. در هر حال
        // تا پایانِ کارِ سرور «مشغول» هستیم تا ردیف‌های خالی چرخِ انتظار
        // نشان دهند.
        loading = !hadCache
        busy = true
        error = null
        coroutineScope {
            launch {
                HermesRepo.modelOptions(refresh)
                    .onSuccess { r -> applyOptions(J.obj(r)) }
                    .onFailure { error = it.message }
            }
            launch {
                HermesRepo.customEndpoints()
                    .onSuccess { r -> endpoints = withCachedModels(J.listOf(J.obj(r), "endpoints").map { J.obj(it) }) }
            }
            launch {
                HermesRepo.oauthProviders()
                    .onSuccess { r -> oauthProviders = J.listOf(J.obj(r), "providers").map { J.obj(it) } }
            }
        }
        loading = false
        busy = false
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

    val nousInfo = oauthProviders.firstOrNull { J.str(it, "id") == "nous" } ?: JsonObject(emptyMap())
    val nousStatus = J.obj(nousInfo["status"])
    val nousLoggedIn = J.bool(nousStatus, "logged_in") || nousModels.isNotEmpty()

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopBar("مدل و پرووایدر", onBack) {
            IconButton(onClick = { scope.launch { busy = true; load(refresh = true); busy = false } }) {
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
                item("err") { ErrorBanner(msg) { scope.launch { busy = true; load(refresh = true); busy = false } } }
            }

            // ── مدلِ فعال: یک نوارِ خلوت، بدون متن‌های طولانی ─────────────
            item("active") {
                ActiveModelHeader(currentModel, currentProvider)
            }

            if (loading) item("loading") { LoadingRow() }

            // ── پرووایدرهای هرمس (Nous هم یک ردیفِ همین فهرست است) ────────
            item("providers-title") {
                SectionTitle("پرووایدرها") {
                    SmallTextButton("افزودن") { showAdd = true }
                }
            }

            item("nous") {
                val isActive = currentProvider == "nous"
                val source = J.str(nousStatus, "source_label")
                val token = J.str(nousStatus, "token_preview")
                ProviderRow(
                    key = "nous",
                    title = "Nous Research",
                    subtitle = when {
                        nousLoggedIn && source.isNotBlank() && token.isNotBlank() -> "$source — $token"
                        nousLoggedIn -> "متصل به Nous Portal"
                        else -> "وارد نشده — برای استفاده از مدل‌های Nous وارد شوید"
                    },
                    active = isActive,
                    accent = Lime,
                    badge = if (isActive) "فعال" else null,
                    expanded = expanded,
                    defaultOpen = isActive,
                ) {
                    if (!nousLoggedIn) {
                        Text(
                            "با حساب Nous Research وارد شوید تا از مدل‌های آن استفاده کنید. هرمس یک کد به شما می‌دهد و شما آن را در صفحهٔ Nous وارد می‌کنید.",
                            style = MaterialTheme.typography.bodySmall, color = TextMid,
                        )
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton("ورود با حساب Nous", { nousLogin = true }, Modifier.fillMaxWidth(), icon = Icons.Default.Login)
                    } else {
                        if (busy && nousModels.isEmpty()) {
                            LoadingRow("در حال دریافت فهرست مدل‌های Nous…")
                        } else {
                            ModelPicker(
                                models = nousModels,
                                selected = if (isActive) currentModel else "",
                                onSelect = { m -> applyMain("nous", m) },
                                emptyText = "فهرستی نیامد — «تازه‌سازی» را بزنید",
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        GhostButton(
                            "خروج از حساب Nous",
                            {
                                scope.launch {
                                    HermesRepo.oauthDisconnect("nous")
                                        .onSuccess { load(refresh = true) }
                                        .onFailure { error = it.message }
                                }
                            },
                            Modifier.fillMaxWidth(), color = Rose, icon = Icons.Default.Logout,
                        )
                    }
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
                    currentModel = currentModel,
                    expanded = expanded,
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

            // ── پرووایدرهای سفارشی (endpoint) ─────────────────────────────
            item("custom-title") {
                SectionTitle("پرووایدرهای سفارشی") {
                    SmallTextButton("افزودن") { showAdd = true }
                }
            }
            if (endpoints.isEmpty() && !loading) {
                item("custom-empty") {
                    EmptyState("هنوز endpoint سفارشی نساخته‌اید", Icons.Default.Dns)
                }
            }
            items(endpoints, key = { "e:${J.str(it, "id", J.str(it, "name"))}" }) { e ->
                CustomEndpointCard(
                    e = e,
                    currentModel = currentModel,
                    expanded = expanded,
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
                scope.launch { busy = true; load(refresh = true); busy = false }
            },
            onNousLogin = { showAdd = false; nousLogin = true },
        )
    }

    addModelTarget?.let { e ->
        AddModelDialog(
            endpoint = e,
            onDismiss = { addModelTarget = null },
            onSaved = { addModelTarget = null; scope.launch { busy = true; load(refresh = true); busy = false } },
        )
    }

    keyTarget?.let { (slug, name) ->
        KeyDialog(
            slug = slug,
            title = name,
            onDismiss = { keyTarget = null },
            onSaved = { keyTarget = null; scope.launch { busy = true; load(refresh = true); busy = false } },
        )
    }

    if (nousLogin) {
        NousLoginDialog(
            onDismiss = { nousLogin = false },
            onLoggedIn = {
                nousLogin = false
                // دو کارِ مستقل را موازی می‌فرستیم: فهرست/مدل‌ها را تازه می‌کنیم
                // و هم‌زمان مدلِ پیشنهادی را می‌گیریم. قبلاً این دو پشتِ سرِ هم
                // بودند و «تازه‌سازیِ سنگین» هم همهٔ پرووایدرها را دوباره
                // می‌خواند؛ حالا نه.
                busy = true
                scope.launch { load() }
                scope.launch {
                    val rec = HermesRepo.recommendedDefault("nous").getOrNull()
                    val m = rec?.let { J.str(J.obj(it), "model") }.orEmpty()
                    if (m.isNotBlank()) {
                        HermesRepo.applyModel("nous", m)
                        onModelChanged()
                        load()
                    }
                }
            },
        )
    }
}

/** نام‌های خوانا برای اسلاگ‌های پرکاربرد؛ بقیه همان اسلاگ نمایش داده می‌شوند. */
private val PROVIDER_LABELS = mapOf(
    "nous" to "Nous Research",
    "openrouter" to "OpenRouter",
    "anthropic" to "Anthropic",
    "openai" to "OpenAI",
    "openai-codex" to "OpenAI Codex",
    "google" to "Google",
    "gemini" to "Google Gemini",
    "xai" to "xAI",
    "xai-oauth" to "xAI Grok",
    "deepseek" to "DeepSeek",
    "mistral" to "Mistral",
    "groq" to "Groq",
    "minimax" to "MiniMax",
    "qwen" to "Qwen",
    "copilot" to "GitHub Copilot",
)

private fun providerLabel(slug: String): String = PROVIDER_LABELS[slug.lowercase()] ?: slug

/** نوارِ فشردهٔ مدلِ فعال. */
@Composable
private fun ActiveModelHeader(model: String, provider: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Gold.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(19.dp), tint = Gold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (model.isBlank()) "مدلی انتخاب نشده" else model,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (model.isBlank()) TextMid else TextHi,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (provider.isBlank()) "از سرور خوانده نشد" else providerLabel(provider),
                    style = MaterialTheme.typography.bodySmall, color = TextLow, maxLines = 1,
                )
            }
            if (model.isNotBlank()) Pill("فعال", Gold)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "با انتخاب هر مدل، نشست‌های جدید با همان مدل ساخته می‌شوند.",
            style = MaterialTheme.typography.bodySmall, color = TextLow,
        )
    }
}

/**
 * ردیفِ یکدستِ پرووایدر.
 *
 * سربرگ همیشه یکسان است؛ محتوای بازشده را فراخوان می‌دهد. وضعیتِ باز/بسته در
 * نگاشتِ [expanded] نگه داشته می‌شود تا ردیفِ فعال به‌طور پیش‌فرض باز بماند
 * ولی کاربر هم بتواند آزادانه باز/بسته کند.
 */
@Composable
private fun ProviderRow(
    key: String,
    title: String,
    subtitle: String,
    active: Boolean,
    accent: Color = Lime,
    badge: String? = null,
    badgeColor: Color = Gold,
    expanded: MutableMap<String, Boolean>,
    defaultOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val open = expanded[key] ?: defaultOpen
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (active) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant, shape)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded[key] = !open }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(active, accent, 9)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) accent else TextMid,
                    maxLines = 2,
                )
            }
            if (badge != null) { Pill(badge, badgeColor); Spacer(Modifier.width(6.dp)) }
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (open) "بستن" else "باز کردن", tint = TextLow, modifier = Modifier.size(20.dp),
            )
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                content = content,
            )
        }
    }
}

/** فهرستِ مدل‌های یک پرووایدر، با جست‌وجو برای فهرست‌های بلند. */
@Composable
private fun ModelPicker(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    emptyText: String,
    maxHeight: Dp = 220.dp,
) {
    if (models.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodySmall, color = TextLow)
        return
    }
    var query by remember(models) { mutableStateOf("") }
    val shown = remember(models, query) {
        if (query.isBlank()) models
        else models.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("مدل‌ها", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = TextMid)
        Text("${faNum(models.size)} مدل", style = MaterialTheme.typography.bodySmall, color = TextLow)
    }
    if (models.size > 8) {
        Spacer(Modifier.height(6.dp))
        LabeledField(
            "جست‌وجوی مدل", query, { query = it },
            placeholder = "بخشی از نام مدل…",
            trailing = { Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = TextLow) },
        )
    }
    Spacer(Modifier.height(6.dp))
    // LazyColumn فقط ردیف‌های دیده‌شده را می‌سازد؛ ارتفاع محدود است تا داخل
    // فهرستِ بیرونی به‌درستی جا شود.
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = maxHeight)
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
            val isActive = m == selected
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(m) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (isActive) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    null, Modifier.size(17.dp), tint = if (isActive) Gold else TextLow,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    m, Modifier.weight(1f), maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) Gold else TextHi,
                )
            }
        }
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

@Composable
private fun CanonicalProviderCard(
    p: JsonObject,
    currentModel: String,
    expanded: MutableMap<String, Boolean>,
    onSelectModel: (String) -> Unit,
    onSetKey: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val slug = J.str(p, "slug", J.str(p, "name"))
    val label = J.str(p, "name", providerLabel(slug))
    val connected = J.bool(p, "authenticated")
    val isCurrent = J.bool(p, "is_current")
    val authType = J.str(p, "auth_type")
    val keyEnv = J.str(p, "key_env")
    val warning = J.str(p, "warning")
    val models = jsonStrings(J.listOf(p, "models"))

    ProviderRow(
        key = "c:$slug",
        title = label,
        subtitle = when {
            connected -> "متصل"
            warning.isNotBlank() -> warning
            authType == "api_key" || keyEnv.isNotBlank() -> "برای استفاده، کلید API را ثبت کنید"
            else -> "متصل نیست"
        },
        active = isCurrent,
        accent = Lime,
        badge = if (isCurrent) "فعال" else null,
        expanded = expanded,
        defaultOpen = isCurrent,
    ) {
        ModelPicker(
            models = models,
            selected = if (isCurrent) currentModel else "",
            onSelect = onSelectModel,
            emptyText = "فهرست مدل‌ها خالی است",
        )
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

@Composable
private fun CustomEndpointCard(
    e: JsonObject,
    currentModel: String,
    expanded: MutableMap<String, Boolean>,
    onActivate: () -> Unit,
    onAddModel: () -> Unit,
    onSelectModel: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val id = J.str(e, "id", J.str(e, "name"))
    val label = J.str(e, "name", id)
    val baseUrl = J.str(e, "base_url")
    val isCurrent = J.bool(e, "is_current")
    val hasKey = J.bool(e, "has_api_key")
    val models = jsonStrings(J.listOf(e, "models"))

    ProviderRow(
        key = "e:$id",
        title = label,
        subtitle = buildString {
            if (baseUrl.isNotBlank()) append(baseUrl)
            append(if (baseUrl.isNotBlank()) " • " else "")
            append(if (hasKey) "کلید ذخیره شده" else "بدون کلید")
            append(" • ")
            append(faNum(models.size))
            append(" مدل")
        },
        active = isCurrent,
        accent = Cyan,
        badge = if (isCurrent) "فعال" else "سفارشی",
        badgeColor = if (isCurrent) Gold else Cyan,
        expanded = expanded,
        defaultOpen = isCurrent,
    ) {
        ModelPicker(
            models = models,
            selected = if (isCurrent) currentModel else "",
            onSelect = onSelectModel,
            emptyText = "مدلی ثبت نشده — مدل جدید اضافه کنید",
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isCurrent) {
                PrimaryButton("اتصال به هرمس", onActivate, Modifier.weight(1f), icon = Icons.Default.Link)
            } else {
                GhostButton("متصل", {}, Modifier.weight(1f), color = Lime, icon = Icons.Default.Check)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("افزودن مدل", onAddModel, Modifier.weight(1f), icon = Icons.Default.Add)
            GhostButton("حذف", { confirmDelete = true }, Modifier.weight(1f), color = Rose, icon = Icons.Default.Delete)
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
                        "✓ ورود با موفقیت انجام شد — فهرست مدل‌ها در حال آمدن است.",
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

/** استخراج فهرست شناسه‌های مدل از یک آرایهٔ JSON (رشته یا آبجکت با id/name). */
private fun jsonStrings(arr: JsonArray): List<String> =
    arr.mapNotNull { el ->
        (el as? JsonPrimitive)?.content
            ?: (el as? JsonObject)?.let { J.str(it, "id", J.str(it, "name")) }
    }.filter { it.isNotBlank() }

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
        title = { Text("افزودن پرووایدر") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                Spacer(Modifier.height(14.dp))
                Text(
                    "یا یک endpoint سازگار با OpenAI اضافه کن. آدرس و کلید را که وارد کردی، «دریافت مدل‌ها» فهرست مدل‌های endpoint را می‌آورد تا یکی را به‌عنوان مدل پیش‌فرض انتخاب کنی.",
                    style = MaterialTheme.typography.bodySmall, color = TextMid,
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
                    Spacer(Modifier.height(12.dp))
                    ModelPicker(
                        models = fetchedModels,
                        selected = model.trim(),
                        onSelect = { model = it },
                        emptyText = "مدلی پیدا نشد",
                        maxHeight = 176.dp,
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
