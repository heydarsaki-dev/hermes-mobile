package ir.hermes.mobile.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import ir.hermes.mobile.core.util.CrashLogger
import ir.hermes.mobile.core.util.Jalali
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * نصب runtime هرمس داخل حافظهٔ برنامه:
 *  ۱. باینری‌های proot + loader و کتابخانه‌هایشان (کوچک، چند صد کیلوبایت) از assets
 *  ۲. دانلود rootfs اوبونتو (شامل پایتون ۳.۱۲ و هرمس ۰.۱۹.۰) از asset ریلیس گیت‌هاب
 *  ۳. استخراج، اعتبارسنجی و نوشتن نشانگر نصب
 *
 * چرا دانلود و نه تعبیه در APK؟ rootfs ~۷۶ مگابایت فشرده است و ~۸۷٪ حجم APK را
 * می‌ساخت (APK ~۹۱ مگابایت). با دانلود، APK به ~۱۲ مگابایت می‌رسد و دادهٔ سیستمی
 * فقط برای کاربری دانلود می‌شود که واقعاً «هرمس درون اپ» را می‌خواهد.
 *
 * حجم: ~۷۶ مگابایت دانلود → ~۲۵۳ مگابایت روی حافظه.
 */
object RootfsInstaller {

    /**
     * نسخهٔ ساختار runtime؛ با تغییر، نصب دوباره انجام می‌شود.
     *
     * عمداً ۳ نگه داشته شده: دادهٔ rootfs دقیقاً همان بایت‌های قبلی است (همان
     * SHA-256)، پس کاربرانی که از قبل نصب کرده‌اند نباید ۷۶ مگابایت را دوباره
     * دانلود کنند. فقط منبع تأمین عوض شده، نه محتوا.
     */
    const val VERSION = 3

    /** منبع دادهٔ سیستمی: asset ریلیس گیت‌هاب (نه داخل APK) */
    private const val ROOTFS_URL =
        "https://github.com/heydarsaki-dev/hermes-mobile/releases/download/runtime-v1/rootfs.tar.gz"

    /** اثر انگشت SHA-256 همان فایل؛ اگر تطابق نداشت، دانلود دور انداخته می‌شود */
    private const val ROOTFS_SHA256 =
        "5ca65bcbf95dac07abade6aaf54a8affc6f9da3d42978123be146a4a66f9723b"

    /** اندازهٔ دقیق فایل روی سرور (برای resume و نمایش درصد) */
    const val TOTAL_BYTES = 79_279_895L

    /** حجم دانلود به مگابایت، فقط برای نمایش به کاربر */
    private val TOTAL_MB: Int = (TOTAL_BYTES / (1024 * 1024)).toInt()

    /** فضای موردنیاز: ۲۵۳MB ریشه + ۷۶MB فایل فشرده هنگام استخراج + حاشیه */
    const val REQUIRED_FREE = 380L * 1024 * 1024

    const val PREFERRED_PORT = 9119

    private const val MAX_ATTEMPTS = 3
    private const val USER_AGENT = "HermesMobile"

    fun rootfsDir(ctx: Context): File = File(ctx.filesDir, "runtime/rootfs")
    fun binDir(ctx: Context): File = File(ctx.filesDir, "runtime/bin")
    fun libDir(ctx: Context): File = File(ctx.filesDir, "runtime/lib")
    fun marker(ctx: Context): File = File(ctx.filesDir, "runtime/.installed")

    /** محل موقت فایل دانلودشده؛ بعد از استخراج پاک می‌شود */
    private fun archiveFile(ctx: Context): File = File(ctx.filesDir, "runtime/tmp/rootfs.tar.gz")

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun isInstalled(ctx: Context): Boolean {
        val m = marker(ctx)
        if (!m.exists()) return false
        if (m.readText().trim() != VERSION.toString()) return false
        // اعتبارسنجی واقعی: نقطهٔ ورود هرمس داخل venv
        return File(rootfsDir(ctx), "opt/hermes-venv/bin/hermes").exists()
    }

    fun hasEnoughFreeSpace(): Boolean = try {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong >= REQUIRED_FREE
    } catch (t: Throwable) {
        true // اگر قابل محاسبه نبود، بگذار خود استخراج تصمیم بگیرد
    }

    private fun isOnline(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        when {
            cm == null -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                cm.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            else -> legacyConnected(cm)
        }
    } catch (t: Throwable) {
        true
    }

    @Suppress("DEPRECATION")
    private fun legacyConnected(cm: ConnectivityManager): Boolean =
        cm.activeNetworkInfo?.isConnected == true

    /**
     * نصب کامل. باید روی dispatcher پس‌زمینه فراخوانی شود.
     * @param onProgress (نقطه‌ای ۰..۱، شرح فارسی) — از هر رشته صدا زده می‌شود
     */
    @JvmStatic
    fun install(ctx: Context, onProgress: (Double, String) -> Unit = { _, _ -> }) {
        if (isInstalled(ctx)) {
            onProgress(1.0, "نصب از قبل کامل است")
            return
        }
        if (!hasEnoughFreeSpace()) {
            throw IllegalStateException(
                "فضای آزاد کافی نیست. برای نصب هرمس حدود ۳۸۰ مگابایت فضای خالی لازم است."
            )
        }

        val rootfs = rootfsDir(ctx)
        val bin = binDir(ctx)
        val lib = libDir(ctx)

        // پاکسازی نصب ناقص قبلی (بدون نشانگر، هر چیزی ناقص است)
        rootfs.deleteRecursively()
        bin.mkdirs(); lib.mkdirs(); rootfs.mkdirs()

        // ۱) باینری‌ها و کتابخانه‌ها (کوچک، داخل APK می‌مانند)
        onProgress(0.01, "کپی باینری‌های proot...")
        copyAssetExec(ctx, "runtime/proot", File(bin, "proot"))
        copyAssetExec(ctx, "runtime/loader", File(bin, "loader"))
        copyAsset(ctx, "runtime/lib/libtalloc.so.2", File(lib, "libtalloc.so.2"))
        copyAsset(ctx, "runtime/lib/libandroid-shmem.so", File(lib, "libandroid-shmem.so"))

        // ۲) دانلود rootfs از ریلیس گیت‌هاب
        val archive = archiveFile(ctx)
        downloadRootfs(ctx, archive) { frac ->
            val mb = (((frac * TOTAL_BYTES) / (1024 * 1024)).toInt()).coerceIn(0, TOTAL_MB)
            onProgress(
                0.02 + frac * 0.78,
                "دانلود داده‌های سیستمی: ${Jalali.fa(mb)} از ${Jalali.fa(TOTAL_MB)} مگابایت (${Jalali.fa((frac * 100).toInt())}٪)",
            )
        }

        // ۳) استخراج (بخش اصلی زمان)
        try {
            TarExtractor.extract(listOf(archive.toPath()), rootfs.toPath(), stripComponents = 1) { read, total ->
                val frac = if (total > 0) read.toDouble() / total else 0.0
                onProgress(
                    0.80 + frac * 0.18,
                    "استخراج سیستم‌عامل هرمس... ${Jalali.fa((frac * 100).toInt())}٪",
                )
            }
        } finally {
            // فایل فشرده بعد از استخراج اضافه است؛ ~۷۶ مگابایت فضا پس داده می‌شود
            runCatching { archive.parentFile?.deleteRecursively() }
        }

        // ۴) اعتبارسنجی و نشانگر
        val hermesBin = File(rootfs, "opt/hermes-venv/bin/hermes")
        if (!hermesBin.exists()) {
            throw IllegalStateException("نصب ناقص ماند: نقطهٔ ورود هرمس پیدا نشد. دوباره تلاش کنید.")
        }
        // DNS مهمان: اگر خالی/خراب بود، پیش‌فرض قابل اعتماد بنویس
        val resolv = File(rootfs, "etc/resolv.conf")
        runCatching {
            val txt = if (resolv.exists()) resolv.readText() else ""
            if (!txt.contains("nameserver")) {
                resolv.parentFile?.mkdirs()
                resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }
        }
        // پوشهٔ ریشه باید برای هرمس قابل نوشتن باشد (با -0 هم لازم است)
        runCatching { File(rootfs, "root").mkdirs() }

        marker(ctx).writeText(VERSION.toString())
        onProgress(1.0, "نصب هرمس کامل شد")
    }

    @JvmStatic
    fun uninstall(ctx: Context) {
        File(ctx.filesDir, "runtime").deleteRecursively()
    }

    // ---------- دانلود ----------

    /**
     * دانلود [ROOTFS_URL] به [dest] با پشتیبانی از ادامهٔ دانلود (HTTP Range)
     * و چند بار تلاش مجدد. در پایان، هش فایل با [ROOTFS_SHA256] بررسی می‌شود.
     */
    private fun downloadRootfs(ctx: Context, dest: File, onProgress: (Double) -> Unit) {
        dest.parentFile?.mkdirs()

        // اگر از قبل کامل و سالم است، دوباره دانلود نکن
        if (dest.length() == TOTAL_BYTES && sha256(dest) == ROOTFS_SHA256) {
            onProgress(1.0)
            return
        }

        if (!isOnline(ctx)) {
            throw IOException(
                "اینترنت در دسترس نیست. برای دانلود دادهٔ سیستمی هرمس (~۷۶ مگابایت) به اتصال نیاز است."
            )
        }

        var attempt = 0
        while (true) {
            attempt++
            try {
                runDownload(dest, onProgress)
                if (dest.length() != TOTAL_BYTES) {
                    throw IOException("فایل ناقص دانلود شد (${dest.length()} از $TOTAL_BYTES بایت)")
                }
                val got = sha256(dest)
                if (got != ROOTFS_SHA256) {
                    // فایل خراب است؛ کاملاً دور بینداز تا از صفر گرفته شود
                    dest.delete()
                    throw IOException("فایل دانلودشده خراب است (هش نامطابق)")
                }
                onProgress(1.0)
                return
            } catch (t: Throwable) {
                CrashLogger.breadcrumb("خطای دانلود rootfs (تلاش $attempt): ${t.message}")
                if (attempt >= MAX_ATTEMPTS) {
                    throw IOException(
                        "دانلود داده‌های سیستمی بعد از $MAX_ATTEMPTS تلاش ناموفق ماند. " +
                            "اتصال اینترنت را بررسی کنید و دوباره بزنید. (${t.message})"
                    )
                }
                // مکث کوتاه و تلاش دوباره؛ پارت‌های دانلودشده باقی می‌مانند
                Thread.sleep(1500L * attempt)
            }
        }
    }

    /** یک تلاش دانلود: اگر فایل نیمه‌کاره باشد از همان‌جا ادامه می‌دهد. */
    private fun runDownload(dest: File, onProgress: (Double) -> Unit) {
        var have = dest.length()
        if (have > TOTAL_BYTES) {
            dest.delete()
            have = 0L
        }

        val builder = Request.Builder()
            .url(ROOTFS_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/octet-stream")
        if (have > 0L && have < TOTAL_BYTES) builder.header("Range", "bytes=$have-")

        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("پاسخ سرور: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("پاسخ خالی از سرور")

            // ۲۰۰ یعنی سرور Range را نادیده گرفت → از صفر بنویس
            val restart = resp.code == 200 || have == 0L
            if (restart) {
                dest.delete()
                have = 0L
            }

            body.byteStream().use { input ->
                FileOutputStream(dest, !restart).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = have
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress((done.toDouble() / TOTAL_BYTES).coerceIn(0.0, 1.0))
                    }
                    out.flush()
                }
            }
        }
    }

    private fun sha256(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        ""
    }

    // ---------- کمکی‌ها ----------

    private fun copyAsset(ctx: Context, asset: String, dest: File, onProgress: (Double) -> Unit = {}) {
        if (dest.exists() && dest.length() > 0 && dest.length() == assetSize(ctx, asset)) return
        dest.parentFile?.mkdirs()
        val total = assetSize(ctx, asset).coerceAtLeast(1L)
        ctx.assets.open(asset).use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done.toDouble() / total)
                }
            }
        }
    }

    private fun copyAssetExec(ctx: Context, asset: String, dest: File) {
        copyAsset(ctx, asset, dest)
        dest.setExecutable(true, false)
    }

    private fun assetSize(ctx: Context, asset: String): Long = try {
        ctx.assets.openFd(asset).use { it.length }
    } catch (t: Throwable) {
        // assets پشت zipalign/packaging ممکن است fd ندهند
        0L
    }
}
