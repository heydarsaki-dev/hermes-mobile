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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.core.datastore.ServerConfig
import ir.hermes.mobile.core.net.RpcEvent
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
import ir.hermes.mobile.ui.skills.SkillsScreen
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
    var route by rememberSaveable { mutableStateOf("") }
    var ready by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val status by HermesRepo.socket.status.collectAsState()
    val sessionId by remember { mutableStateOf("") }
    var sid by remember { mutableStateOf("") }

    // اتصال رویدادهای سوکت به موتور چت
    LaunchedEffect(Unit) {
        HermesRepo.socket.events.collect { e ->
            if (e is RpcEvent.Event) {
                engine.onEvent(e)
                e.sid?.let { s -> if (s.isNotBlank()) { HermesRepo.startSession(s); sid = s } }
            }
        }
    }

    // بازیابی تنظیمات
    LaunchedEffect(Unit) {
        val cfg = HermesRepo.settings.config.first()
        if (cfg.connected && cfg.url.isNotBlank()) {
            HermesRepo.applyConfig(cfg)
            HermesRepo.socket.connect()
            route = "hub"
        }
        loading = false
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
            !ready && route == "" -> ConnectScreen { ready = true; route = "hub" }
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
                            title = "",
                            statusLive = status == RpcEvent.State.CONNECTED,
                            onNewSession = { scope.launch { HermesRepo.newSession("گفت‌وگوی جدید") } },
                            onOpenSessions = { route = "sessions" },
                            onOpenModel = { route = "model" },
                        )
                        "sessions" -> SessionsScreen({ route = "hub" }) { id -> HermesRepo.startSession(id); sid = id; route = "chat" }
                        "model" -> ModelScreen { route = "hub" }
                        "tools" -> ToolsScreenProxy { route = "hub" }
                        "skills" -> SkillsScreen { route = "hub" }
                        "cron" -> CronScreen { route = "hub" }
                        "terminal" -> TerminalScreen { route = "hub" }
                        "settings" -> SettingsScreen({ route = "hub" }) { route = ""; ready = false }
                        else -> HubScreen(status, sid, "", onGo = { route = it })
                    }
                }
                if (route == "hub") HubBar(route) { route = it }
            }
        }
    }
}

@Composable
private fun ToolsScreenProxy(onBack: () -> Unit) {
    ir.hermes.mobile.ui.tools.ToolsScreen(onBack)
}

@Composable
private fun HubBar(current: String, onGo: (String) -> Unit) {
    val items = listOf(
        Triple("hub", "خانه", Icons.Default.Home),
        Triple("chat", "چت", Icons.AutoMirrored.Filled.Chat),
        Triple("tools", "ابزار", Icons.Default.Build),
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
