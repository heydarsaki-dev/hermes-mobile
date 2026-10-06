package ir.hermes.mobile.ui.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.hermes.mobile.core.datastore.ServerConfig
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    // مقادیر ذخیره‌شده را از مخزن بخوان تا بعد از باز شدن دوبارهٔ اپ، آدرس/توکن
    // قبلی در فیلدها بماند (Root پیش از این applyConfig را صدا زده است).
    var url by remember { mutableStateOf(HermesRepo.api.baseUrl.ifBlank { "http://127.0.0.1:8080" }) }
    var token by remember { mutableStateOf(HermesRepo.api.token) }
    var showToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    // وقتی سرور تعبیهشدهٔ هرمس آماده شد، اتصال خودکار انجام میشود
    var autoConnect by remember { mutableStateOf(false) }

    // به‌جای تابع محلی که state را از بیرون ترکیب‌بندی تغییر می‌داد،
    // از یک LaunchedEffect با کلید url/token استفاده می‌کنیم تا
    // به‌روزرسانی state همیشه در فاز ترکیب‌بندی انجام شود.
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

    // اجرای اتصال پس از آمادهشدن سرور تعبیهشده (url/token تازه شدهاند)
    LaunchedEffect(autoConnect, url, token) {
        if (autoConnect && url.startsWith("http://127.0.0.1") && token.isNotEmpty()) {
            autoConnect = false
            doConnect()
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Ink1, Ink0, Ink0)
                )
            )
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
                "برای شروع، آدرس داشبورد و توکن نشست را وارد کنید",
                color = TextMid, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            // اجرای هرمس داخل خودِ اپ — بدون ترموکس؛ پس از آمادهشدن،
            // آدرس و توکن خودکار در فیلدهای زیر پر میشوند.
            RuntimeCard { readyUrl, readyToken ->
                // اگر فیلد آدرس در اختیار کاربر نباشد (خالی، لوکال، یا همان سرور
                // درون‌اپی)، با آماده‌شدن سرور، آدرس/توکن تازه پر و اتصال خودکار
                // برقرار می‌شود — هم در اولین اجرا و هم بعد از خاموش‌شدن سرور و
                // باز شدن دوبارهٔ اپ (توکن قبلی بی‌اعتبار است).
                val ours = url.isBlank() || url == readyUrl ||
                    url.contains("127.0.0.1") || url.contains("localhost")
                if (ours) {
                    if (url != readyUrl) url = readyUrl
                    if (token != readyToken) token = readyToken
                    autoConnect = true
                }
            }
            Spacer(Modifier.height(20.dp))

            GlassCard(Modifier.fillMaxWidth()) {
                LabeledField(
                    "آدرس سرور", url, { url = it },
                    placeholder = "http://192.168.1.10:8080",
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(16.dp))
                LabeledField(
                    "توکن نشست", token, { token = it },
                    placeholder = "HERMES_DASHBOARD_SESSION_TOKEN",
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                "نمایش", tint = TextMid
                            )
                        }
                    },
                )
                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    if (testing) "در حال اتصال…" else "اتصال و ورود",
                    doConnect, Modifier.fillMaxWidth(), enabled = !testing,
                )
            }

            // از !! و فراخوانی پرانتزی داخل lambda استفاده نمی‌کنیم
            // تا در بازترکیب‌بندی کرش رخ ندهد.
            val e = error
            AnimatedVisibility(visible = e != null) {
                e?.let { s ->
                    Column { Spacer(Modifier.height(12.dp)); ErrorBanner(s) { doConnect() } }
                }
            }
            val i = info
            AnimatedVisibility(visible = i != null) {
                i?.let { s ->
                    Column { Spacer(Modifier.height(12.dp)); Pill(s, Lime) }
                }
            }

            Spacer(Modifier.height(24.dp))
            GlassCard(Modifier.fillMaxWidth(), borderColor = Cyan.copy(alpha = 0.25f)) {
                Text("راهنمای اتصال", style = MaterialTheme.typography.titleSmall, color = Cyan)
                Spacer(Modifier.height(10.dp))
                listOf(
                    "توکن را در ترموکس با دستور زیر بسازید و ذخیره کنید:",
                    "export HERMES_DASHBOARD_SESSION_TOKEN=\$(head -c 32 /dev/urandom | base64 | tr -d '=+/')",
                    "سپس هرمس را با همان متغیر اجرا کنید:",
                    "HERMES_DASHBOARD_SESSION_TOKEN=\$HERMES_DASHBOARD_SESSION_TOKEN hermes dashboard --host 0.0.0.0",
                    "روی گوشی، IP ترموکس را وارد کنید نه localhost (مثلاً http://192.168.1.10:8080).",
                ).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = TextMid)
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
