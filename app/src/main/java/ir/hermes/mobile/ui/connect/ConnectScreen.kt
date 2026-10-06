package ir.hermes.mobile.ui.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.hermes.mobile.core.datastore.ServerConfig
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch

/**
 * صفحهٔ اول فقط دو کار دارد: نصب (دانلود یک‌بارهٔ) هرمس داخل اپ و اجرای آن.
 * آدرس و توکن دستی حذف شده‌اند؛ خودِ اپ سرور را روی 127.0.0.1 با توکن تصادفی
 * بالا می‌آورد و به‌محض آماده‌شدن، اتصال خودکار انجام می‌شود.
 */
@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    // وقتی سرور تعبیه‌شدهٔ هرمس آماده شد، اتصال خودکار انجام می‌شود
    var autoConnect by remember { mutableStateOf(false) }

    // اجرای اتصال پس از آماده‌شدن سرور تعبیه‌شده (url/token تازه شده‌اند)
    val doConnect: () -> Unit = remember(url, token) {
        val action: () -> Unit = {
            testing = true; error = null; info = null
            scope.launch {
                val cfg = ServerConfig(url.trim(), token.trim())
                HermesRepo.applyConfig(cfg)
                HermesRepo.api.ping()
                    .onSuccess {
                        HermesRepo.settings.save(cfg.url, cfg.token)
                        HermesRepo.socket.connect()
                        info = "اتصال برقرار شد"
                        onConnected()
                    }
                    .onFailure { error = it.message ?: "خطای ناشناخته" }
                testing = false
            }
        }
        action
    }

    LaunchedEffect(autoConnect, url, token) {
        if (autoConnect && url.startsWith("http://127.0.0.1") && token.isNotEmpty()) {
            autoConnect = false
            doConnect()
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ink1, Ink0, Ink0)))
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(26.dp))
                    .background(Gold.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("هرمس", fontSize = 26.sp, color = Gold, style = MaterialTheme.typography.displaySmall)
            }
            Spacer(Modifier.height(18.dp))
            Text("دستیار هوشمند هرمس", style = MaterialTheme.typography.headlineSmall, color = TextHi)
            Spacer(Modifier.height(6.dp))
            Text(
                "سیستم هرمس یک‌بار داخل خودِ اپ دانلود می‌شود و پس از آن آفلاین اجرا می‌شود.",
                color = TextMid, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            // نصب/اجرا/توقف سرور و اتصال خودکار پس از آماده‌شدن
            RuntimeCard { readyUrl, readyToken ->
                if (url != readyUrl || token != readyToken) {
                    url = readyUrl
                    token = readyToken
                    autoConnect = true
                }
            }

            if (testing) {
                Spacer(Modifier.height(12.dp))
                Pill("در حال اتصال…", Amber)
            }

            val e = error
            AnimatedVisibility(visible = e != null) {
                e?.let { s ->
                    Column {
                        Spacer(Modifier.height(12.dp))
                        ErrorBanner(s) { if (url.isNotBlank() && token.isNotBlank()) doConnect() }
                    }
                }
            }
            val i = info
            AnimatedVisibility(visible = i != null) {
                i?.let { s ->
                    Column { Spacer(Modifier.height(12.dp)); Pill(s, Lime) }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
