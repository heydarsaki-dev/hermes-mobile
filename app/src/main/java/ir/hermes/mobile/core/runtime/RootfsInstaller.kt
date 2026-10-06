package ir.hermes.mobile.core.runtime

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream

/**
 * نصب runtime هرمس داخل حافظهٔ برنامه:
 *  ۱. باینریهای proot + loader و کتابخانههایشان از assets به files/runtime/{bin,lib}
 *  ۲. استخراج rootfs اوبونتو (شامل پایتون۳.۱۲ و هرمس۰.۱۹.۰) از دو پارت gzip
 *  ۳. نوشتن نشانگر نصب تا دوباره استخراج نشود
 *
 * حجم: ~۷۶ مگابایت فشرده → ~۲۵۳ مگابایت روی حافظه.
 */
object RootfsInstaller {

    /** نسخهٔ ساختار runtime؛ با تغییر، نصب دوباره انجام میشود */
    const val VERSION = 3

    const val PART1_BYTES = 41943040L
    const val PART2_BYTES = 37336855L
    const val TOTAL_COMPRESSED = PART1_BYTES + PART2_BYTES

    /** فضای موردنیاز: ۲۵۳MB ریشه + ۷۶MB پارت هنگام استخراج + حاشیه */
    const val REQUIRED_FREE = 380L * 1024 * 1024

    const val PREFERRED_PORT = 9119

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
     * نصب کامل. باید روی dispatcher پسزمینه فراخوانی شود.
     * @param onProgress (نقطهای ۰..۱، شرح فارسی) — از هر رشته صدا زده میشود
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

        val runtimeRoot = File(ctx.filesDir, "runtime")
        val rootfs = rootfsDir(ctx)
        val bin = binDir(ctx)
        val lib = libDir(ctx)

        // پاکسازی نصب ناقص قبلی (بدون نشانگر، هر چیزی ناقص است)
        if (!isInstalled(ctx)) {
            rootfs.deleteRecursively()
        }
        bin.mkdirs(); lib.mkdirs(); rootfs.mkdirs()

        // ۱) باینریها و کتابخانهها
        onProgress(0.01, "کپی باینریهای proot...")
        copyAssetExec(ctx, "runtime/proot", File(bin, "proot"))
        copyAssetExec(ctx, "runtime/loader", File(bin, "loader"))
        copyAsset(ctx, "runtime/lib/libtalloc.so.2", File(lib, "libtalloc.so.2"))
        copyAsset(ctx, "runtime/lib/libandroid-shmem.so", File(lib, "libandroid-shmem.so"))

        // ۲) استخراج rootfs (بخش اصلی)
        val part1 = File(bin, "rootfs.tar.gz.00")
        val part2 = File(bin, "rootfs.tar.gz.01")
        copyAsset(ctx, "runtime/rootfs.tar.gz.00", part1) { p ->
            onProgress(0.02 + p * 0.03, "آمادهسازی دادههای سیستمی ${(p * 100).toInt()}٪")
        }
        copyAsset(ctx, "runtime/rootfs.tar.gz.01", part2) { p ->
            onProgress(0.05 + p * 0.03, "آمادهسازی دادههای سیستمی ${(3 + p * 3).toInt()}٪")
        }

        try {
            TarExtractor.extract(listOf(part1.toPath(), part2.toPath()), rootfs.toPath(), stripComponents = 1) { read, total ->
                val frac = if (total > 0) read.toDouble() / total else 0.0
                onProgress(
                    0.08 + frac * 0.86,
                    "استخراج سیستم‌عامل هرمس... ${(frac * 100).toInt()}٪",
                )
            }
        } finally {
            // پارتها بعد از استخراج فایل اضافهاند؛ فضا را پس میدهیم
            part1.delete(); part2.delete()
        }

        // ۳) اعتبارسنجی و نشانگر
        val hermesBin = File(rootfs, "opt/hermes-venv/bin/hermes")
        if (!hermesBin.exists()) {
            throw IllegalStateException("نصب ناقص ماند: نقطهٔ ورود هرمس پیدا نشد. دوباره تلاش کنید.")
        }
        // DNS مهمان: اگر خالی/خراب بود، پیشفرض قابل اعتماد بنویس
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

    // ---------- کمکیها ----------

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
        // assets پشت zipalign/packaging ممکن است fd ندهند؛ از پیش میدانیم
        when (asset) {
            "runtime/rootfs.tar.gz.00" -> PART1_BYTES
            "runtime/rootfs.tar.gz.01" -> PART2_BYTES
            else -> 0L
        }
    }
}
