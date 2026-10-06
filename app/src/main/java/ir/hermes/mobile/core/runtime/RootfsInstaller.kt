package ir.hermes.mobile.core.runtime

import android.content.Context
import android.os.Environment
import android.os.StatFs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * نصب runtime هرمس داخل حافظهٔ برنامه.
 *
 * برای کوچک ماندن حجم APK، دیگر هیچ باینری‌ای داخل assets بسته‌بندی نمی‌شود؛
 * همه‌چیز یک‌بار از ریلیز گیت‌هاب دانلود می‌شود:
 *  ۱. دانلود proot + loader + libtalloc + libandroid-shmem به files/runtime/{bin,lib}
 *  ۲. دانلود rootfs.tar.gz (~۷۶ مگابایت) و استخراج آن در files/runtime/rootfs
 *  ۳. نوشتن نشانگر نصب تا دوباره دانلود/استخراج نشود
 *
 * حجم: ~۷۶ مگابایت دانلود → ~۲۵۳ مگابایت روی حافظه.
 * هر فایل با طول دقیق و SHA-256 بازبینی می‌شود.
 */
object RootfsInstaller {

    /** نسخهٔ ساختار runtime؛ با تغییر، نصب دوباره انجام می‌شود */
    const val VERSION = 3

    const val PREFERRED_PORT = 9119

    /** محل payload در ریلیز گیت‌هاب */
    private const val BASE_URL =
        "https://github.com/heydarsaki-dev/hermes-mobile/releases/download/runtime-v1"

    /** فضای موردنیاز: ۲۵۳MB ریشه + ۷۶MB فایل فشرده هنگام استخراج + حاشیه */
    const val REQUIRED_FREE = 380L * 1024 * 1024

    // اندازه و SHA-256 دقیق فایل‌های ریلیز
    private const val PROOT_NAME = "proot"
    private const val PROOT_BYTES = 247_488L
    private const val PROOT_SHA = "1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717"

    private const val LOADER_NAME = "loader"
    private const val LOADER_BYTES = 18_136L
    private const val LOADER_SHA = "cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551"

    private const val TALLOC_NAME = "libtalloc.so.2"
    private const val TALLOC_BYTES = 33_592L
    private const val TALLOC_SHA = "742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309"

    private const val SHMEM_NAME = "libandroid-shmem.so"
    private const val SHMEM_BYTES = 14_432L
    private const val SHMEM_SHA = "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731"

    private const val ROOTFS_NAME = "rootfs.tar.gz"
    private const val ROOTFS_BYTES = 79_279_895L
    private const val ROOTFS_SHA = "5ca65bcbf95dac07abade6aaf54a8affc6f9da3d42978123be146a4a66f9723b"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun rootfsDir(ctx: Context): File = File(ctx.filesDir, "runtime/rootfs")
    fun binDir(ctx: Context): File = File(ctx.filesDir, "runtime/bin")
    fun libDir(ctx: Context): File = File(ctx.filesDir, "runtime/lib")
    fun marker(ctx: Context): File = File(ctx.filesDir, "runtime/.installed")

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

        // پاک‌سازی نصب ناقص قبلی (بدون نشانگر، هر چیزی ناقص است)
        rootfs.deleteRecursively()
        bin.mkdirs(); lib.mkdirs(); rootfs.mkdirs()

        // ۱) باینری‌ها و کتابخانه‌ها (کوچک)
        onProgress(0.005, "دانلود proot...")
        download(PROOT_NAME, File(bin, "proot"), PROOT_BYTES, PROOT_SHA)
        File(bin, "proot").setExecutable(true, false)

        onProgress(0.010, "دانلود loader...")
        download(LOADER_NAME, File(bin, "loader"), LOADER_BYTES, LOADER_SHA)
        File(bin, "loader").setExecutable(true, false)

        onProgress(0.015, "دانلود کتابخانه‌های proot...")
        download(TALLOC_NAME, File(lib, TALLOC_NAME), TALLOC_BYTES, TALLOC_SHA)
        download(SHMEM_NAME, File(lib, SHMEM_NAME), SHMEM_BYTES, SHMEM_SHA)

        // ۲) دانلود rootfs (بخش اصلی)
        val tar = File(ctx.cacheDir, ROOTFS_NAME)
        download(ROOTFS_NAME, tar, ROOTFS_BYTES, ROOTFS_SHA) { p ->
            onProgress(0.02 + p * 0.28, "دانلود سیستم هرمس... ${(p * 100).toInt()}٪")
        }

        try {
            TarExtractor.extract(listOf(tar.toPath()), rootfs.toPath(), stripComponents = 1) { read, total ->
                val frac = if (total > 0) read.toDouble() / total else 0.0
                onProgress(
                    0.30 + frac * 0.68,
                    "استخراج سیستم‌عامل هرمس... ${(frac * 100).toInt()}٪",
                )
            }
        } finally {
            // فایل فشرده بعد از استخراج اضافه است؛ فضا را پس می‌دهیم
            tar.delete()
        }

        // ۳) اعتبارسنجی و نشانگر
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
        runCatching { File(ctx.cacheDir, ROOTFS_NAME).delete() }
    }

    // ---------- کمکی‌ها ----------

    /**
     * دانلود یک فایل از ریلیز. اگر فایل از قبل با همان اندازه و SHA-256 موجود
     * باشد، دوباره دانلود نمی‌شود.
     */
    private fun download(
        name: String,
        dest: File,
        expectBytes: Long,
        expectSha: String,
        onProgress: (Double) -> Unit = {},
    ) {
        if (dest.exists() && dest.length() == expectBytes && sha256Of(dest) == expectSha) return

        dest.parentFile?.mkdirs()
        val request = Request.Builder().url("$BASE_URL/$name").build()
        val digest = MessageDigest.getInstance("SHA-256")

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("دانلود $name ناموفق بود (HTTP ${response.code})")
            val body = response.body ?: error("پاسخ خالی برای $name")
            val total = if (expectBytes > 0) expectBytes else body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toDouble() / total)
                    }
                }
            }
        }

        val gotBytes = dest.length()
        if (expectBytes > 0 && gotBytes != expectBytes) {
            dest.delete()
            error("حجم $name ناهمخوان است ($gotBytes ≠ $expectBytes)")
        }
        if (expectSha.isNotEmpty()) {
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != expectSha) {
                dest.delete()
                error("بازبینی SHA-256 برای $name ناموفق بود")
            }
        }
    }

    private fun sha256Of(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        ""
    }
}
