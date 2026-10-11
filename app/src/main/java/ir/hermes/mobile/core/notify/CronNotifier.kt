package ir.hermes.mobile.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import ir.hermes.mobile.MainActivity
import ir.hermes.mobile.R
import ir.hermes.mobile.core.net.RpcEvent
import ir.hermes.mobile.data.HermesRepo
import ir.hermes.mobile.data.J

/**
 * نوتیفیکیشنِ نتایجِ کرون.
 *
 * برای هر کرون‌جاب یک **کانالِ** نوتیفیکیشن با همان عنوانِ کار ساخته می‌شود
 * (`hermes_cron_<jobId>`)، و هر بار که نتیجهٔ تازه‌ای در نشستِ همان کار نوشته
 * شود، یک نوتیفیکیشن روی همان کانال فرستاده می‌شود.
 *
 * چرا این‌طور؟ سرور برای هر کار دقیقاً یک نشستِ ثابت با شناسهٔ
 * `cron_<jobId>` می‌سازد و فقط **متنِ نتیجه** را داخلش می‌نویسد (پرامپت
 * انگلیسی، دستورهای ترمینال و کد پایتون با `_persist_disabled` حذف شده‌اند).
 *
 * ## چرا تاریخچهٔ زنده خوانده نمی‌شود
 *
 * `session.history` فقط نشست‌های **در حافظه** را می‌خواند؛ نشستِ کرون فقط در
 * دیتابیس نوشته می‌شود و هیچ‌وقت در `_sessions` سرور ثبت نمی‌شود. یک بار
 * `session.resume` می‌کردیم تا زنده شود، ولی آن مسیر یک ایجنت کامل می‌سازد
 * (گران) و اسلاتِ «نشست فعال» می‌گیرد.
 *
 * پس دو RESTِ سبک روی همان دیتابیس داریم:
 *
 * ۱. **شمارش** از `/api/sessions/<id>` — یک نقطهٔ SQLite روی شناسهٔ نشستِ
 *    همان کار که `message_count` را دارد. (به‌جای `session.list` که پیش‌فرض
 *    فقط ۲۰۰ نشستِ تازه را برمی‌گرداند و با نشستِ زیاد می‌تواند نشستِ کار را
 *    جا بگذارد.)
 * ۲. **متنِ نتیجه** فقط وقتی خبری تازه رسیده، از
 *    `/api/sessions/<id>/messages?offset=<count-1>` — دقیقاً آخرین پیام.
 *
 * نتیجه: هیچ نشست زنده و هیچ ایجنتی ساخته نمی‌شود، و فقط وقتی واقعاً خبر
 * هست متن خوانده می‌شود.
 */
object CronNotifier {

    private const val TAG = "CronNotifier"
    private const val PREFS = "hermes_cron_notifier"

    /**
     * نامِ کلیدِ شمارشِ دیده‌شده.
     *
     * مقدار عمدی `-1` است: یعنی «هیچ‌وقت ندیده‌ایم» و از شمارش واقعی
     * (که ۰ هم می‌تواند باشد) قابل تشخیص است. اولین بار که دیده می‌شویم،
     * همین شمارش بدونِ نوتیفیکیشن ذخیره می‌شود تا تاریخچهٔ قبلی یک‌دفعه به
     * شکل نوتیفیکیشن روی کاربر نبارد.
     */
    private const val SEEDED = -1

    /** شناسهٔ کانالِ نوتیفیکیشنِ یک کار. */
    fun channelId(jobId: String): String = "hermes_cron_$jobId"

    /**
     * شناسهٔ نشستِ ثابتِ یک کرون. سرور برای هر کار دقیقاً این نشست را
     * می‌سازد (`cron_<job_id>`) و فقط نتیجهٔ نهایی را داخلش می‌نویسد.
     */
    fun sessionId(jobId: String): String = "cron_$jobId"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** تعداد پیام‌هایی که تا حالا برای این کار دیده‌ایم؛ -1 یعنی هنوز ندانسته‌ایم. */
    private fun seen(ctx: Context, jobId: String): Int = prefs(ctx).getInt("seen_$jobId", SEEDED)

    /** ذخیرهٔ تعداد پیام‌های دیده‌شده (بدونِ فرستادنِ نوتیفیکیشن). */
    private fun setSeen(ctx: Context, jobId: String, count: Int) {
        prefs(ctx).edit().putInt("seen_$jobId", count).apply()
    }

    /**
     * کانالِ نوتیفیکیشنِ یک کار را می‌سازد (یا اگر هست دست نمی‌زند).
     *
     * نامِ کانال همان **عنوانِ کار** است تا کاربر در تنظیماتِ اندروید و در
     * هدرِ هر نوتیفیکیشن ببیند کدام کرون خبر فرستاده. اندروید اجازهٔ تغییرِ
     * نامِ کانالِ موجود را نمی‌دهد، پس اگر عنوان کار بعداً عوض شود کانال با
     * نام قبلی می‌ماند؛ برای همین عنوانِ تازه را در [post] روی خودِ
     * نوتیفیکیشن هم می‌گذاریم.
     */
    fun ensureChannel(ctx: Context, jobId: String, title: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val id = channelId(jobId)
        if (nm.getNotificationChannel(id) != null) return
        val safe = title.ifBlank { "کرون" }
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(id, safe, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "نتایجِ کرون‌جاب: $safe"
                    setShowBadge(true)
                },
            )
        }.onFailure { Log.w(TAG, "createChannel failed", it) }
    }

    /**
     * کاربر فهرست کارها را باز کرده و دارد خودش محتوا را می‌بیند.
     *
     * شمارشِ جاری را به‌عنوان «دیده‌شده» ثبت می‌کنیم تا پیام‌هایی که همین
     * حالا روی صفحه‌اند، بعداً یک‌باره نوتیفیکیشن نگیرند. کاری که هنوز هیچ
     * نشستی ندارد (اجرای اول نگذشته) دست‌نخورده می‌ماند تا اولین اجرا واقعاً
     * نوتیفیکیشن بگیرد.
     */
    suspend fun markViewed(ctx: Context, titles: Map<String, String>) {
        for ((jobId, title) in titles) {
            val count = messageCount(sessionId(jobId)) ?: continue
            ensureChannel(ctx, jobId, title)
            setSeen(ctx, jobId, count)
        }
    }

    /**
     * بررسیِ همهٔ کرون‌جاب‌ها و فرستادنِ نوتیفیکیشن برای پیام‌های تازه.
     *
     * فقط وقتی سوکت واقعاً وصل باشد کار می‌کند؛ وگرنه هر ۶۰ ثانیه یک درخواستِ
     * بی‌پاسخ می‌زد که باتری و داده می‌سوزاند.
     *
     * @return `true` اگر حداقل یک نوتیفیکیشن فرستاده شد.
     */
    suspend fun poll(ctx: Context): Boolean {
        if (HermesRepo.socket.status.value != RpcEvent.State.CONNECTED) return false

        val jobs = cronJobs()
        if (jobs.isEmpty()) return false

        var anyNotified = false

        for ((jobId, title) in jobs) {
            val sid = sessionId(jobId)
            ensureChannel(ctx, jobId, title)
            val count = messageCount(sid) ?: continue // نشست هنوز ساخته نشده
            val seen = seen(ctx, jobId)
            if (seen == SEEDED) {
                // اولین باری که این کار را می‌بینیم: یاد بگیر و بی‌صدا رد شو.
                setSeen(ctx, jobId, count)
                continue
            }
            if (count <= seen) continue
            // شمارش را پیش از خواندنِ متن جلو می‌بریم: اگر خواندنِ نتیجه شکست
            // خورد، دفعهٔ بعد دوباره تلاش کنیم و تاریخچه نباید انباشته شود.
            setSeen(ctx, jobId, count)
            if (HermesRepo.socket.sessionId == sid || HermesRepo.socket.sessionKey == sid) {
                // کاربر همین حالا این نشست را در چت باز دارد؛ خودش می‌بیند.
                continue
            }
            post(ctx, jobId, title, lastResultText(sid, count))
            anyNotified = true
        }
        return anyNotified
    }

    /**
     * شمارشِ پیام‌های یک نشستِ کرون، مستقیم از دیتابیس.
     *
     * به‌جای `session.list` (که پیش‌فرض فقط ۲۰۰ نشستِ تازه را برمی‌گرداند و
     * با نشستِ زیاد می‌تواند نشستِ کار را جا بگذارد) همین یک RESTِ سبک را
     * به‌ازای هر کار می‌زنیم: پاسخ یک نقطهٔ SQLite روی همان شناسه است و
     * `message_count` را دارد.
     *
     * @return شمارش، یا `null` اگر نشست هنوز وجود نداشته باشد.
     */
    private suspend fun messageCount(sid: String): Int? {
        val resp = HermesRepo.api.call("/api/sessions/$sid").getOrNull() ?: return null
        // ۴۰۴ یعنی نشست هنوز ساخته نشده؛ پاسخِ نامعتبر هم همین معنا را دارد.
        val obj = J.obj(resp)
        val id = J.str(obj, "id")
        if (id.isBlank()) return null
        return J.int(obj, "message_count")
    }

    /**
     * فهرست کارها به شکلِ `(شناسه، عنوان)`.
     *
     * سرور در `_format_job` شناسهٔ کار را با کلیدِ `job_id` می‌فرستد (همان
     * `id`ِ داخلیِ کار)، و نشستِ کرون با همان ساخته می‌شود. اگر به‌جای آن
     * «نام» را جلو بیندازیم، به نشستِ ناموجود نگاه می‌کنیم و هیچ‌وقت
     * نوتیفیکیشنی نمی‌آید.
     */
    private suspend fun cronJobs(): List<Pair<String, String>> {
        val resp = HermesRepo.cronList().getOrNull() ?: return emptyList()
        val list = J.listOf(J.obj(resp), "jobs", "items", "cron")
        return list.mapNotNull { element ->
            val job = J.obj(element)
            val jobId = J.str(job, "job_id", J.str(job, "id", J.str(job, "name")))
            if (jobId.isBlank()) null else jobId to J.str(job, "name").ifBlank { jobId }
        }
    }

    /**
     * متنِ آخرین نتیجهٔ یک نشستِ کرون، از طریق REST روی همان دیتابیس.
     *
     * `count` را داریم، پس با `offset = count - 1` دقیقاً آخرین پیام را
     * می‌خوانیم — بدون بارگذاریِ کلِ transcript. اگر نشست خالی بود یا خواندن
     * نشست نشد، متنِ جایگزینِ فارسی برمی‌گردد تا نوتیفیکیشن هرگز با پیام
     * خالی روی کاربر نریزد (و کاربر هم بداند خبری رسیده).
     */
    private suspend fun lastResultText(sid: String, count: Int): String {
        val offset = (count - 1).coerceAtLeast(0)
        val resp = HermesRepo.api.call("/api/sessions/$sid/messages?limit=2&offset=$offset")
            .getOrNull() ?: return FALLBACK_TEXT
        for (element in J.listOf(J.obj(resp), "messages", "items").asReversed()) {
            val msg = J.obj(element)
            if (J.str(msg, "role") != "assistant") continue
            val text = J.str(msg, "content").trim()
            if (text.isNotBlank()) return text
        }
        return FALLBACK_TEXT
    }

    /** پیامِ جایگزین وقتی متنِ نتیجه خوانده نشد (هرگز خالی نماند). */
    private const val FALLBACK_TEXT = "نتیجهٔ تازهٔ این کار آمده است — برای دیدن، لمس کنید."

    /** فرستادنِ نوتیفیکیشن روی کانالِ همان کار. */
    private fun post(ctx: Context, jobId: String, title: String, text: String) {
        val openIntent = android.content.Intent(ctx, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx,
            channelId(jobId).hashCode(),
            openIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val preview = previewOf(text)
        val notification = NotificationCompat.Builder(ctx, channelId(jobId))
            .setSmallIcon(R.drawable.ic_hermes_status)
            .setContentTitle(title.ifBlank { "کرون" })
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                ?.notify(channelId(jobId).hashCode(), notification)
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    /**
     * بریدنِ متن برای پیش‌نمایش: از **وسط** بریده نمی‌شود.
     *
     * نتیجهٔ یک کار جدول/تازه‌ترین بخش است و پیش از آن ستون‌هایی مثل زمانِ
     * اجرا و عبارتِ دقیقِ کار قرار می‌گیرد؛ نوتیفیکیشن باید خودِ خبر را
     * نشان دهد، نه سرِ فرمتِ کار. پس از انتهای متن شروع می‌کنیم.
     */
    private fun previewOf(text: String, max: Int = 260): String {
        val clean = text.replace("\r\n", "\n").trim()
        if (clean.length <= max) return clean
        return "…" + clean.takeLast(max)
    }
}
