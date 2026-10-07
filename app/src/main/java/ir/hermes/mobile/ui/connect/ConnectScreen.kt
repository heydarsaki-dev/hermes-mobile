package ir.hermes.mobile.ui.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.hermes.mobile.core.datastore.ServerConfig
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.launch

/**
 * صفحهٔ اتصال.
 *
 * هرمس روی خودِ گوشی اجرا می‌شود (درون‌اپی) و اتصال کاملاً لوکال است؛ پس
 * نیازی به وارد کردن دستیِ آدرس/توکن سرور بیرونی نیست. به‌محض آماده‌شدن
 * سرورِ تعبیه‌شده، اتصال خودکار برقرار می‌شود.
 */
@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    // آدرس/توکن فقط وضعیت درونیِ اتصال لوکال را نگه می‌دارند؛ فیلد دستی
    // برای کاربر وجود ندارد چون همیشه روی ۱۲۷.۰.۰.۱ همین گوشی است.
    var url by remember { mutableStateOf(HermesRepo.api.baseUrl.ifBlank { "http://127.0.0.1:8080" }) }
    var token by remember { mutableStateOf(HermesRepo.api.token) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoConnect by remember { mutableStateOf(false) }

    val doConnect: () -> Unit = remember(url, token) {
        val action: () -> Unit = {
            testing = true; error = null
            scope.launch {
                val cfg = ServerConfig(url.trim(), token.trim())
                HermesRepo.applyConfig(cfg)
                HermesRepo.api.ping()
                    .onSuccess {
                        HermesRepo.settings.save(cfg.url, cfg.token)
                        HermesRepo.socket.connect()
                        onConnected()
                    }
                    .onFailure { error = it.message ?: "خطای ناشناخته" }
                testing = false
            }
        }
        action
    }

    // اجرای اتصال پس از آماده‌شدن سرور تعبیه‌شده (url/token تازه شدهاند)
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
                "هرمس روی همین گوشی اجرا می‌شود — پس از آماده‌شدن سرور، به‌صورت خودکار متصل می‌شوید",
                color = TextMid, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            // اجرای هرمس داخل خودِ اپ — بدون ترموکس؛ پس از آماده‌شدن،
            // اتصال خودکار برقرار می‌شود.
            RuntimeCard { readyUrl, readyToken ->
                url = readyUrl
                token = readyToken
                autoConnect = true
            }

            val e = error
            AnimatedVisibility(visible = e != null) {
                e?.let { s ->
                    Column { Spacer(Modifier.height(12.dp)); ErrorBanner(s) { doConnect() } }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
