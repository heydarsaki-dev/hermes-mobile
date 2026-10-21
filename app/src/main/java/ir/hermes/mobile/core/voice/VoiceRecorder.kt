package ir.hermes.mobile.core.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * ضبط‌کنندهٔ پیام صوتی برای چت.
 *
 * برای «فشردن و گفتن»: تا وقتی انگشت روی دکمهٔ میکروفون است ضبط می‌شود و با
 * رهاکردن، فایلِ آمادهٔ ارسال برگردانده می‌شود.
 *
 * چرا MediaRecorder و نه AudioRecord؟ خروجی MediaRecorder از همان ابتدا
 * فشرده است (AAC در ظرف M4A)، پس فایلِ ضبطِ یک‌دقیقه‌ای چند صد کیلوبایت
 * بیشتر نمی‌شود و هم ارسالِ آن به سرور سریع است و هم سقف ۲۵ مگابایتیِ
 * سرور هرگز رد نمی‌شود. AudioRecord خروجی PCM خام می‌داد که برای همان یک
 * دقیقه ~۱۰ مگابایت می‌شد.
 *
 * نکته: [stop] حتی در بدترین حالت (ضبطِ صفرثانیه‌ای) فایلِ بی‌اعتبار را
 * پاک می‌کند و `null` برمی‌گرداند، تا چیزی که قابلِ تبدیل به متن نیست هرگز
 * به سرور فرستاده نشود.
 */
class VoiceRecorder private constructor(private val ctx: Context) {

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var startedAt: Long = 0

    /** آیا در حالِ ضبط است؟ */
    val isRecording: Boolean get() = recorder != null

    /** مدتِ ضبطِ جاری به ثانیه (برای نمایشِ شمارندهٔ زنده). */
    fun elapsedSeconds(): Int =
        if (recorder == null) 0 else ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    /** شروعِ ضبط. اگر میکروفون در اختیارِ دیگری باشد [IOException] می‌دهد. */
    @Throws(IOException::class)
    fun start() {
        if (recorder != null) return
        val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
        // پسوند m4a: هم نگاشتِ MIME سمتِ سرور می‌شناسد (audio/mp4) و هم
        // MediaRecorder روی همهٔ نسخه‌های اندروید پشتیبانی‌اش می‌کند.
        val f = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION", "UnsafeOptInUsageError")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // ۲۲٫۰۵۰ هرتز تک‌کاناله: برای گفتار کاملاً کافی است (ویسپر هم
            // خودش به ۱۶ کیلوهرتز بازنمونه‌گیری می‌کند) و حجم را نصف می‌کند.
            r.setAudioSamplingRate(22_050)
            r.setAudioEncodingBitRate(48_000)
            r.setAudioChannels(1)
            r.setMaxDuration(120_000) // دو دقیقه: سقفِ عقلانیِ یک پیام صوتی
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (t: Throwable) {
            runCatching { r.release() }
            runCatching { f.delete() }
            throw t
        }
        recorder = r
        outFile = f
        startedAt = System.currentTimeMillis()
    }

    /**
     * پایانِ ضبط.
     *
     * فایلِ ضبط‌شده را برمی‌گرداند و ضبط‌کننده را آزاد می‌کند. اگر چیزی
     * ضبط نشده بود (فشردنِ یک‌ لحظه‌ای، اشغالِ میکروفون و…) فایل پاک و
     * `null` برگردانده می‌شود.
     */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = outFile
        recorder = null
        outFile = null
        var ok = false
        try {
            r.stop()
            ok = true
        } catch (_: Throwable) {
            // stop پیش از ضبطِ هر داده‌ای (کلیکِ یک‌لحظه‌ای) IllegalStateException
            // می‌دهد؛ فایلِ خالی بی‌اعتبار است.
        } finally {
            runCatching { r.release() }
        }
        if (!ok || f == null || !f.exists() || f.length() < 512L) {
            runCatching { f?.delete() }
            return null
        }
        return f
    }

    /** لغوِ ضبط: فایل پاک می‌شود و چیزی برگردانده نمی‌شود. */
    fun cancel() {
        val r = recorder ?: return
        val f = outFile
        recorder = null
        outFile = null
        try {
            r.stop()
        } catch (_: Throwable) {
        } finally {
            runCatching { r.release() }
        }
        runCatching { f?.delete() }
    }

    companion object {
        fun from(ctx: Context): VoiceRecorder = VoiceRecorder(ctx.applicationContext)
    }
}
