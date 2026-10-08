package ir.hermes.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.core.datastore.ModelSelection
import ir.hermes.mobile.core.datastore.ServerConfig
import ir.hermes.mobile.core.net.RpcEvent
import ir.hermes.mobile.core.runtime.HermesRuntime
import ir.hermes.mobile.core.util.CrashLogger
import ir.hermes.mobile.data.ChatEngine
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.ui.chat.ChatScreen
import ir.hermes.mobile.ui.connect.ConnectScreen
import ir.hermes.mobile.ui.cron.CronScreen
import ir.hermes.mobile.ui.hub.HubScreen
import ir.hermes.mobile.ui.model.ModelScreen
import ir.hermes.mobile.ui.sessions.SessionsScreen
import ir.hermes.mobile.ui.settings.SettingsScreen
import ir.hermes.mobile.ui.terminal.TerminalScreen
import ir.hermes.mobile.ui.theme.HermesTheme
import ir.hermes.mobile.ui.theme.Ink0
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // باید قبل از هر چیز نصب شود تا هر کرشی ثبت و بعداً قابل استخراج باشد.
        CrashLogger.install(applicationContext)
        HermesRepo.init(applicationContext)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HermesTheme { Root() }
            }
        }
    }
}

@Composable
private fun Root() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val engine = remember { ChatEngine() }
    // توجه: ready/route عمداً دیگر rememberSaveable نیستند.
    //
    // سرور تعبیه‌شده فرآیند فرزند همین اپ است؛ با بسته‌شدن کامل اپ (یا kill شدن
    // توسط سیستم) خاموش می‌شود. اگر وضعیت «وصل» را از حافظهٔ قبلی بازیابی کنیم،
    // بعد از باز شدن دوباره اپ خود را وصل فرض می‌کند ولی سرور خاموش است و
    // اولین تعامل خطا می‌دهد. پس با هر باز شدن، وضعیت از نو از روی اتصال واقعی
    // سوکت تعیین می‌شود.
    var route by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    // اگر کاربر «خروج از حساب» بزند، نباید اتصال خودکار دوباره برقرار شود.
    var autoReconnect by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    val status by HermesRepo.socket.status.collectAsState()
    var sid by remember { mutableStateOf("") }
    // عنوان نشستی که در چت باز است؛ تا کاربر بداند کدام نشست را باز کرده.
    var chatTitle by remember { mutableStateOf("") }
    // مدلی که کاربر انتخاب کرده؛ نشست‌های جدید با همین ساخته می‌شوند
    val selection by HermesRepo.settings.selection.collectAsState(initial = ModelSelection())

    // اتصال رویدادهای سوکت به موتور چت
    LaunchedEffect(Unit) {
        HermesRepo.socket.events.collect { e ->
            if (e is RpcEvent.Event) {
                engine.onEvent(e)
                // خودِ سوکت هنگام رسیدن رویداد، `sessionId` و `sessionKey` را
                // از روی `sid`/`session_key` همگام می‌کند؛ اینجا فقط وضعیتِ
                // محلیِ UI را به‌روز می‌کنیم (startSession صدا نمی‌زنیم تا
                // sessionKey پاک نشود).
                e.sid?.let { s -> if (s.isNotBlank()) { sid = s } }
            }
        }
    }

    // بازیابی تنظیمات + بالا آوردن خودکار سرور درون‌اپی
    LaunchedEffect(Unit) {
        val cfg = HermesRepo.settings.config.first()
        if (cfg.connected && cfg.url.isNotBlank()) {
            HermesRepo.applyConfig(cfg)
            // سرور درون‌اپی بعد از بسته‌شدن اپ خاموش شده است؛ اگر آدرس لوکال است
            // و نصب وجود دارد، خودکار دوباره بالا می‌آید و [RuntimeCard] پس از
            // آماده‌شدن، اتصال را با توکن تازه برقرار می‌کند.
            val embedded = cfg.url.contains("127.0.0.1") || cfg.url.contains("localhost")
            if (embedded) {
                // توکنِ ذخیره‌شده مال سرور قبلی است و حالا بی‌اعتبار است؛ پس
                // عجله‌ای برای وصل‌شدن با آن نیست — منتظر READY می‌مانیم.
                runCatching { HermesRuntime.start(ctx) }
            } else {
                HermesRepo.socket.connect()
            }
        }
        loading = false
    }

    // «آماده» بودن از وضعیت واقعی سوکت می‌آید، نه از حافظه.
    // با قطع/خطای اتصال، کاربر به صفحهٔ اتصال برمی‌گردد تا دوباره وصل کند.
    LaunchedEffect(status) {
        when (status) {
            RpcEvent.State.CONNECTED -> {
                ready = true
                autoReconnect = true
                if (route.isBlank()) route = "hub"
            }
            // قطع سوکت (چه خطا چه بستن تمیز، مثل خاموش‌شدن یا kill‌شدن سرور)
            // یعنی «وصل نیستیم». چند تلاش کوتاه می‌کنیم چون ممکن است قطع گذرا
            // باشد (برگشت اپ از پس‌زمینه)؛ اگر سرور واقعاً خاموش باشد به صفحهٔ
            // اتصال برمی‌گردیم — نه اینکه خود را وصل فرض کنیم.
            RpcEvent.State.FAILED, RpcEvent.State.CLOSED -> {
                if (ready && autoReconnect) {
                    var alive = false
                    for (i in 1..3) {
                        kotlinx.coroutines.delay(1200L * i)
                        // خروج از حساب یا قطع شدن دستی: دست نگه دار
                        if (!autoReconnect || !ready) return@LaunchedEffect
                        alive = runCatching { HermesRepo.api.ping() }
                            .getOrNull()?.isSuccess == true
                        if (alive) break
                    }
                    if (alive) HermesRepo.socket.connect() else ready = false
                }
            }
            else -> Unit
        }
    }

    // نشانهٔ آخرین صفحه‌ها، تا در گزارش کرش معلوم باشد کدام صفحه اپ را بسته است.
    LaunchedEffect(route) { if (route.isNotBlank()) CrashLogger.breadcrumb("صفحه=$route") }

    BackHandler(enabled = route.isNotBlank() && route != "hub") { route = "hub" }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(ir.hermes.mobile.ui.theme.Ink1, Ink0))
        )
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ir.hermes.mobile.ui.theme.Gold)
            }
            // هرگاه واقعاً وصل نباشیم صفحهٔ اتصال نمایش داده می‌شود (نه فقط در
            // شروع). قبلاً شرط «route == ""» هم لازم بود و نتیجه‌اش این بود که
            // بعد از قطع‌شدن سرور، اپ روی همان صفحهٔ قبلی می‌ماند و خود را وصل
            // فرض می‌کرد و اولین درخواست خطا می‌داد.
            !ready -> ConnectScreen {
                autoReconnect = true
                if (route.isBlank()) route = "hub"
                ready = true
            }
            // نکتهٔ مهم: اینجا عمداً از Scaffold/AnimatedContent استفاده نمی‌کنیم.
            //
            // ترکیب قبلی «Scaffold ریشه → AnimatedContent → Scaffold صفحهٔ مقصد»
            // سه لایه SubcomposeLayout تودرتو می‌ساخت و هنگام رفتن به صفحه‌ای که
            // خودش Scaffold دارد، مخزن گروه‌های Compose به‌هم می‌ریخت:
            //   java.lang.ArrayIndexOutOfBoundsException: index=-2
            //     at androidx.compose.runtime.IntStack.peek2
            //     at androidx.compose.runtime.ComposerImpl.end/endGroup/endRoot
            // حالا محتوا و نوار پایین در یک Column چیده می‌شوند و در هر لحظه
            // فقط یک صفحه ترکیب‌بندی می‌شود.
            else -> Column(Modifier.fillMaxSize()) {
                // فِید ملایم هنگام تعویض صفحه (بدون AnimatedContent).
                var shown by remember { mutableStateOf(route) }
                val fade = remember { Animatable(1f) }
                LaunchedEffect(route) {
                    if (shown != route) {
                        fade.snapTo(0f)
                        shown = route
                        fade.animateTo(1f, tween(durationMillis = 180))
                    }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .graphicsLayer { alpha = fade.value }
                ) {
                    when (shown) {
                        "hub" -> HubScreen(status, sid, "", onGo = { route = it })
                        "chat" -> ChatScreen(
                            engine = engine,
                            title = chatTitle,
                            statusLive = status == RpcEvent.State.CONNECTED,
                            modelLabel = selection.model.ifBlank { "" },
                            onNewSession = {
                                scope.launch {
                                    HermesRepo.createSession("گفت‌وگوی جدید")?.let { id ->
                                        sid = id
                                        chatTitle = ""
                                        engine.resetForNewSession()
                                    }
                                }
                            },
                            onOpenSessions = { route = "sessions" },
                            onOpenModel = { route = "model" },
                            // تغییر مدل از داخل چت: نوبت جاری متوقف و نشست فعلی
                            // کنار گذاشته می‌شود تا نشست بعدی با مدل تازه ساخته شود
                            onApplyModel = { p, m, b ->
                                scope.launch {
                                    val err = HermesRepo.applyModel(p, m, b)
                                    engine.resetForNewSession()
                                    sid = ""
                                    chatTitle = ""
                                    if (err != null) engine.notice.value = err
                                }
                            },
                        )
                        // انتخاب یک نشست: تاریخچهٔ همان نشست از سرور خوانده و
                        // جای گفت‌وگوی فعلی نشان داده می‌شود؛ وگرنه چت همچنان
                        // پیام‌های نشست قبلی را نگه می‌داشت و همهٔ نشست‌ها یکسان
                        // به‌نظر می‌رسیدند.
                        "sessions" -> SessionsScreen({ route = "hub" }) { id, title ->
                            sid = id
                            chatTitle = title
                            route = "chat"
                            scope.launch { engine.openSession(id) }
                        }
                        "model" -> ModelScreen(
                            onBack = { route = "hub" },
                            // هر نشست هرمس مدلی را که با آن ساخته شده نگه می‌دارد؛
                            // پس بعد از تغییر مدل، نشست فعال پاک و چت تازه می‌شود.
                            onModelChanged = { HermesRepo.clearSession(); engine.resetForNewSession() },
                        )
                        "cron" -> CronScreen { route = "hub" }
                        "terminal" -> TerminalScreen { route = "hub" }
                        "settings" -> SettingsScreen({ route = "hub" }) {
                            // خروج از حساب: اول اجازهٔ اتصال خودکار را بگیر، بعد وضعیت را پاک کن
                            autoReconnect = false
                            route = ""
                            ready = false
                        }
                        else -> HubScreen(status, sid, "", onGo = { route = it })
                    }
                }
                if (route == "hub") HubBar(route) { route = it }
            }
        }
    }
}

@Composable
private fun HubBar(current: String, onGo: (String) -> Unit) {
    val items = listOf(
        Triple("hub", "خانه", Icons.Default.Home),
        Triple("chat", "چت", Icons.AutoMirrored.Filled.Chat),
        Triple("sessions", "نشست‌ها", Icons.Default.Forum),
        Triple("settings", "تنظیمات", Icons.Default.Settings),
    )
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(ir.hermes.mobile.ui.theme.Ink1.copy(alpha = 0.97f))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { (r, label, icon) ->
            val sel = current == r
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 6.dp)
                    .then(if (sel) Modifier.background(ir.hermes.mobile.ui.theme.Gold.copy(alpha = 0.14f)) else Modifier)
                    .clickable { onGo(r) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    icon, label, Modifier.size(20.dp),
                    tint = if (sel) ir.hermes.mobile.ui.theme.Gold else ir.hermes.mobile.ui.theme.TextLow
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    label, style = MaterialTheme.typography.labelSmall,
                    color = if (sel) ir.hermes.mobile.ui.theme.Gold else ir.hermes.mobile.ui.theme.TextLow
                )
            }
        }
    }
}
