package ir.hermes.mobile.ui.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.hermes.mobile.core.runtime.HermesRuntime
import ir.hermes.mobile.core.runtime.RootfsInstaller
import ir.hermes.mobile.ui.components.*
import ir.hermes.mobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * کارت «هرمس داخل خودِ اپ»: نصب rootfs و بالا آوردن سرور بدون ترموکس.
 * پس از READY، آدرس و توکن آماده میشود و [onReady] صدا زده میشود.
 */
@Composable
fun RuntimeCard(onReady: (url: String, token: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(RootfsInstaller.isInstalled(ctx)) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0.0) }
    var progressText by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val runtime by HermesRuntime.state.collectAsState()
    val logs by HermesRuntime.logs.collectAsState()

    // بهمحض آمادهشدن سرور، پارامترهای اتصال را به والد بده
    LaunchedEffect(runtime.state, runtime.port, runtime.token) {
        if (runtime.state == HermesRuntime.State.READY && runtime.port > 0 && runtime.token.isNotEmpty()) {
            onReady("http://127.0.0.1:${runtime.port}", runtime.token)
        }
    }

    GlassCard(Modifier.fillMaxWidth(), borderColor = Gold.copy(alpha = 0.3f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("هرمس داخل اپ", style = MaterialTheme.typography.titleSmall, color = Gold)
            Spacer(Modifier.weight(1f))
            val dotColor = when (runtime.state) {
                HermesRuntime.State.READY -> Lime
                HermesRuntime.State.STARTING -> Amber
                HermesRuntime.State.FAILED -> Rose
                else -> if (installed) Cyan else TextMid
            }
            Pill(
                when (runtime.state) {
                    HermesRuntime.State.READY -> "سرور آماده"
                    HermesRuntime.State.STARTING -> "در حال اجرا"
                    HermesRuntime.State.FAILED -> "خطا"
                    else -> if (installed) "نصب شده" else "بدون نصب"
                },
                dotColor,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "ترموکس لازم نیست؛ سرور هرمس درون خودِ گوشی نصب و اجرا میشود.\n" +
                "دادهٔ سیستمی (~۷۶ مگابایت) هنگام نصب از اینترنت دانلود میشود، پس بهتر است به وای‌فای وصل باشید.",
            style = MaterialTheme.typography.bodySmall, color = TextMid, textAlign = TextAlign.Center,
        )

        AnimatedVisibility(visible = installing) {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(progressText, style = MaterialTheme.typography.bodySmall, color = TextMid)
            }
        }

        AnimatedVisibility(visible = runtime.state == HermesRuntime.State.STARTING) {
            Column(Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    logs.takeLast(8).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1)
                    }
                }
            }
        }

        val failed = runtime.state == HermesRuntime.State.FAILED
        val e = localError ?: runtime.message.takeIf { failed }
        AnimatedVisibility(visible = e != null) {
            Column {
                Spacer(Modifier.height(10.dp))
                ErrorBanner(e ?: "")
            }
        }

        Spacer(Modifier.height(14.dp))

        when {
            installing -> GhostButton("در حال نصب...", onClick = {}, modifier = Modifier.fillMaxWidth(), color = TextMid)
            !installed -> PrimaryButton("نصب هرمس درون اپ (دانلود ۷۶ مگابایت)", onClick = {
                installing = true
                localError = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            RootfsInstaller.install(ctx) { p, t ->
                                progress = p
                                progressText = t
                            }
                        }
                    }
                    installed = runCatching { RootfsInstaller.isInstalled(ctx) }.getOrDefault(false)
                    installing = false
                    ok.onFailure { localError = "نصب ناموفق: ${it.message}" }
                }
            })
            runtime.state == HermesRuntime.State.STARTING ->
                GhostButton("توقف سرور", onClick = { HermesRuntime.stop() }, modifier = Modifier.fillMaxWidth(), color = Rose)
            runtime.state == HermesRuntime.State.READY ->
                GhostButton("توقف سرور (اتصال برقرار است)", onClick = { HermesRuntime.stop() }, modifier = Modifier.fillMaxWidth(), color = Amber)
            else -> PrimaryButton("اجرای هرمس", onClick = { HermesRuntime.start(ctx) }, modifier = Modifier.fillMaxWidth())
        }

        if (installed && runtime.state == HermesRuntime.State.STOPPED) {
            Spacer(Modifier.height(8.dp))
            GhostButton("حذف نصب هرمس (۲۵۳ مگ)", onClick = {
                scope.launch(Dispatchers.IO) {
                    HermesRuntime.stop()
                    RootfsInstaller.uninstall(ctx)
                    installed = false
                }
            }, modifier = Modifier.fillMaxWidth(), color = TextMid)
        }
    }
}
