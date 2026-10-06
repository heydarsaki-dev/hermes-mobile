package ir.hermes.mobile.core.util

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * لاگ‌انداز کرش درون‌برنامه‌ای.
 *
 * هر استثنای مدیریت‌نشده را روی حافظهٔ داخلی اپ ذخیره می‌کند (چند فایل آخر)
 * و به‌همراه «مسیرهای اخیر» (breadcrumb) نگه می‌دارد تا بعد از بسته‌شدن اپ،
 * کاربر بتواند از «تنظیمات → گزارش خطا» آن را ببیند، کپی یا ارسال کند.
 *
 * دلیل: روی گوشی بدون دسترسی adb، تنها راه رساندن استک‌تریس واقعی به
 * توسعه‌دهنده همین است.
 */
object CrashLogger {

    private const val DIR = "crashes"
    private const val MAX_FILES = 8
    private const val MAX_CRUMBS = 30
    private const val PREFS = "hermes_crash"
    private const val KEY_CRUMBS = "breadcrumbs"

    @Volatile private var appCtx: Context? = null
    @Volatile private var installed = false

    private val stampFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** نصب هندلر جهانی. چند بار صدا زدن بی‌خطر است. */
    fun install(context: Context) {
        val ctx = context.applicationContext
        appCtx = ctx
        if (installed) return
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // نوشتن گزارش باید قبل از مرگ پردازش کامل شود.
            runCatching { writeCrash(ctx, thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * ثبت «مسیر اخیر» — یک نشانه از کاری که اپ در حال انجامش بود.
     * فقط آخرین [MAX_CRUMBS] خط نگه داشته می‌شود.
     */
    fun breadcrumb(text: String) {
        val ctx = appCtx ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_CRUMBS, "").orEmpty()
            val line = "[${timeFmt.format(Date())}] $text"
            val lines = (existing.lines() + line)
                .filter { it.isNotBlank() }
                .takeLast(MAX_CRUMBS)
            prefs.edit().putString(KEY_CRUMBS, lines.joinToString("\n")).apply()
        }
    }

    private fun writeCrash(ctx: Context, thread: Thread, t: Throwable) {
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "crash-${stampFmt.format(Date())}.txt")
        file.writeText(buildReport(ctx, thread, t))
        // فقط آخرین چند گزارش بماند تا حافظه پر نشود.
        runCatching {
            dir.listFiles()
                ?.sortedByDescending { it.name }
                ?.drop(MAX_FILES)
                ?.forEach { it.delete() }
        }
    }

    private fun buildReport(ctx: Context, thread: Thread, t: Throwable): String {
        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { t.printStackTrace(it) }
        }.toString()
        val crumbs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CRUMBS, "").orEmpty()
        return buildString {
            appendLine("=== HÉRMES CRASH REPORT ===")
            appendLine("زمان: ${timeFmt.format(Date())}  (شمسی: ${Jalali.format()})")
            appendLine("نسخه اپ: ${appVersion(ctx)}")
            appendLine("اندروید: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("دستگاه: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("معماری: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("نخ (thread): ${thread.name} id=${thread.id}")
            appendLine()
            appendLine("--- مسیرهای اخیر اپ ---")
            appendLine(crumbs.ifBlank { "(ثبت نشده)" })
            appendLine()
            appendLine("--- استک‌تریس ---")
            append(stack)
        }
    }

    /** همهٔ گزارش‌ها، جدیدترین اول، در یک متن. */
    fun all(): String = reports().joinToString("\n\n────────────\n\n") { file ->
        runCatching { file.readText() }.getOrDefault("<خواندن ${file.name} ناموفق بود>")
    }

    fun reports(): List<File> {
        val ctx = appCtx ?: return emptyList()
        return runCatching {
            File(ctx.filesDir, DIR)
                .listFiles()
                ?.sortedByDescending { it.name }
                ?.toList()
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun count(): Int = reports().size

    fun clear() {
        reports().forEach { runCatching { it.delete() } }
        runCatching {
            appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()?.remove(KEY_CRUMBS)?.apply()
        }
    }

    /** نام و کد نسخهٔ نصب‌شدهٔ اپ؛ برای مطمئن‌شدن از نصب‌بودن APK درست. */
    fun appVersion(ctx: Context): String = runCatching {
        val info: PackageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${info.versionName} (${versionCodeOf(info)})"
    }.getOrDefault("نامعلوم")

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
}
