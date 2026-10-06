package ir.hermes.mobile.core.runtime

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.SecureRandom

/**
 * اجرای زندهٔ سرور هرمس از طریق proot، داخل خودِ اپ (بدون ترموکس).
 *
 * دستور اجرا:
 *   proot -0 --link2symlink -r rootfs -b /dev -b /proc ... \
 *     /usr/bin/env -i HERMES_DASHBOARD_SESSION_TOKEN=<توکن تصادفی> \
 *     hermes serve --host 127.0.0.1 --port 9119 --skip-build
 *
 * نشان آمادگی (روی stdout):
 *   HERMES_BACKEND_READY port=NNNN
 *
 * توکن توسط خودِ اپ ساخته و به هرمس تزریق میشود؛ همان توکن برای
 * احراز هویت REST (Bearer) و وبسوکت (?token=) استفاده میشود.
 */
object HermesRuntime {

    enum class State { STOPPED, STARTING, READY, FAILED }

    data class Snapshot(
        val state: State = State.STOPPED,
        val port: Int = 0,
        val token: String = "",
        val message: String = "",
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @Volatile private var process: Process? = null
    @Volatile private var watchdog: Thread? = null

    val isRunning: Boolean
        get() = process?.let { runCatching { it.exitValue(); false }.getOrDefault(true) } ?: false

    /** توکن نشست به شکل secrets.token_urlsafe(32) پایتون */
    fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * انتخاب پورت برای سرور درون‌اپی.
     *
     * اگر نسخهٔ قبلی سرور به هر دلیلی هنوز زنده باشد (مثلاً اپ توسط سیستم kill
     * شده ولی فرآیند فرزند proot باقی مانده)، پورت پیش‌فرض اشغال است و سرور تازه
     * موقع bind شکست می‌خورد؛ آن‌وقت کاربر فقط پیام «سرور بسته شد» را می‌دید و
     * هیچ راهی برای درست‌کردنش نداشت. چون توکن آن سرور قدیمی را نمی‌دانیم،
     * نمی‌توانیم به آن وصل شویم؛ پس اولین پورت آزاد بعدی را برمی‌داریم.
     */
    private fun freePort(): Int {
        for (p in RootfsInstaller.PREFERRED_PORT until RootfsInstaller.PREFERRED_PORT + 5) {
            if (!isPortBusy(p)) {
                if (p != RootfsInstaller.PREFERRED_PORT) log("پورت ${RootfsInstaller.PREFERRED_PORT} اشغال بود؛ پورت $p استفاده می‌شود")
                return p
            }
        }
        return RootfsInstaller.PREFERRED_PORT
    }

    /** آیا چیزی روی این پورت localhost گوش می‌دهد؟ */
    private fun isPortBusy(port: Int): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
            true
        }
    } catch (_: Throwable) {
        false
    }

    private fun log(line: String) {
        _logs.value = (_logs.value + line).takeLast(300)
    }

    /**
     * بالا آوردن سرور. اگر از قبل در حال اجراست، بلافاصله برمیگردد.
     * نتیجه از طریق [state] دنبال میشود.
     */
    @Synchronized
    fun start(ctx: Context) {
        if (isRunning) {
            if (_state.value.state == State.STOPPED) _state.value = Snapshot(State.STARTING, message = "در حال انتظار...")
            return
        }
        val app = ctx.applicationContext

        if (!RootfsInstaller.isInstalled(app)) {
            _state.value = Snapshot(State.FAILED, message = "ابتدا هرمس را نصب کنید")
            return
        }

        val token = generateToken()
        val port = freePort()
        _state.value = Snapshot(State.STARTING, token = token, message = "راهاندازی سرور هرمس...")
        _logs.value = emptyList()

        val bin = RootfsInstaller.binDir(app)
        val lib = RootfsInstaller.libDir(app)
        val rootfs = RootfsInstaller.rootfsDir(app)
        val proot = File(bin, "proot")
        if (!proot.exists()) {
            _state.value = Snapshot(State.FAILED, token = token, message = "باینری proot پیدا نشد؛ هرمس را دوباره نصب کنید")
            return
        }

        val cmd = buildCommand(app, proot, rootfs, token, port)

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        // کتابخانههای proot: libtalloc و libandroid-shmem از filesDir + نسخههای
        // استخراجشدهٔ jniLibs از nativeLibraryDir (الگوی Mobile Harness)
        env["LD_LIBRARY_PATH"] = lib.absolutePath + ":" + app.applicationInfo.nativeLibraryDir
        env["PROOT_LOADER"] = File(bin, "loader").absolutePath
        env["PROOT_NO_SECCOMP"] = "1"
        env["PROOT_TMP_DIR"] = File(app.cacheDir, "proot-tmp").absolutePath
        File(app.cacheDir, "proot-tmp").mkdirs()

        val proc = try {
            pb.start()
        } catch (t: Throwable) {
            _state.value = Snapshot(State.FAILED, token = token, message = "اجرای proot ناموفق: ${t.message}")
            return
        }
        process = proc
        log("دستور اجرا: proot ... hermes serve --host 127.0.0.1 --port $port")

        // خواندن خروجی سرور
        Thread({
            var readyPort = 0
            val readyRegex = Regex("""HERMES_BACKEND_READY port=(\d+)|HERMES_DASHBOARD_READY port=(\d+)""")
            try {
                BufferedReader(InputStreamReader(proc.inputStream), 8 * 1024).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        log(line)
                        val m = readyRegex.find(line)
                        if (m != null && readyPort == 0) {
                            readyPort = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: port
                            _state.value = Snapshot(State.READY, port = readyPort, token = token, message = "سرور هرمس آماده است")
                        }
                    }
                }
            } catch (t: Throwable) {
                log("پایان جریان خروجی: ${t.message}")
            } finally {
                process = null
                watchdog?.interrupt(); watchdog = null
                val st = _state.value.state
                if (st != State.STOPPED) {
                    val last = _logs.value.lastOrNull() ?: "جزئیاتی ثبت نشد"
                    _state.value = Snapshot(State.FAILED, token = token, message = "سرور بسته شد: $last")
                }
            }
        }, "hermes-stdout").start()

        // خواندن خطاهای احتمالی (python traceback و...)
        Thread({
            try {
                BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) log("[stderr] $line")
                    }
                }
            } catch (_: Throwable) {
            }
        }, "hermes-stderr").start()

        // نگهبان: اگر تا ۹۰ ثانیه آماده نشد، خطا گزارش کن
        watchdog = Thread({
            try {
                Thread.sleep(90_000)
                if (_state.value.state == State.STARTING) {
                    log("مهلت راهاندازی تمام شد")
                    stopInternal()
                    _state.value = Snapshot(
                        State.FAILED, token = token,
                        message = "سرور در ۹۰ ثانیه آماده نشد؛ لاگها را ببینید",
                    )
                }
            } catch (_: InterruptedException) {
            }
        }, "hermes-watchdog").also { it.isDaemon = true; it.start() }
    }

    /** توقف سرور و فرزندانش */
    @Synchronized
    fun stop() {
        stopInternal()
        if (_state.value.state != State.STOPPED) {
            _state.value = Snapshot(State.STOPPED, message = "سرور خاموش شد")
        }
    }

    private fun stopInternal() {
        watchdog?.interrupt()
        watchdog = null
        val p = process ?: return
        process = null
        runCatching { p.destroy() }
        // اگر بعد از ۳ ثانیه نمرد، اجباری
        Thread {
            runCatching {
                if (p.isAlive) {
                    Thread.sleep(3_000)
                    if (p.isAlive) runCatching { p.destroyForcibly() }
                }
            }
        }.start()
    }

    /** ساخت آرگومانهای proot + فرمان مهمان */
    private fun buildCommand(
        ctx: Context,
        proot: File,
        rootfs: File,
        token: String,
        port: Int,
    ): List<String> {
        val cmd = ArrayList<String>(40)
        cmd += proot.absolutePath
        cmd += "--link2symlink"
        cmd += "-0" // جعل root: فایلهای rootfs متعلق به uid صفراند و /root با700
        cmd += "-r"; cmd += rootfs.absolutePath
        cmd += "--kill-on-exit"

        // اتصالهای میزبان لازم برای مهمان
        bindIf(cmd, "/dev")
        bindIf(cmd, "/proc")
        bindIf(cmd, "/sys")
        bindIf(cmd, "/system") // لینکر و کتابخانههای سیستم
        bindIf(cmd, "/apex") // اندروید ۱۰+
        if (File("/linkerconfig/ld.config.txt").exists()) {
            cmd += "-b"; cmd += "/linkerconfig/ld.config.txt:/linkerconfig/ld.config.txt"
        }
        bindIf(cmd, "/storage") // دسترسی فایلها (اختیاری)

        cmd += "-w"; cmd += "/root"

        // محیط کاملاً تمیز مهمان — فقط همینجا HERMES_DASHBOARD_SESSION_TOKEN تزریق میشود
        cmd += "/usr/bin/env"; cmd += "-i"
        cmd += "HOME=/root"
        cmd += "USER=root"
        cmd += "LOGNAME=root"
        cmd += "SHELL=/bin/bash"
        cmd += "TERM=xterm-256color"
        cmd += "LANG=C.UTF-8"
        cmd += "LC_ALL=C.UTF-8"
        cmd += "PATH=/opt/hermes-venv/bin:/usr/local/bin:/usr/bin:/bin"
        cmd += "TMPDIR=/tmp"
        cmd += "HERMES_DASHBOARD_SESSION_TOKEN=$token"
        cmd += "/opt/hermes-venv/bin/hermes"
        cmd += "serve"
        cmd += "--host"; cmd += "127.0.0.1"
        cmd += "--port"; cmd += port.toString()
        cmd += "--skip-build" // بدون این، سرور تلاش به build کردن SPA میکند (npm لازم دارد)
        return cmd
    }

    private fun bindIf(cmd: MutableList<String>, path: String) {
        if (File(path).exists()) {
            cmd += "-b"
            cmd += path
        }
    }
}
