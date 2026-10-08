package ir.hermes.mobile.core.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import ir.hermes.mobile.MainActivity
import ir.hermes.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * سرویس پیش‌زمینه‌ای که سرور هرمس را وقتی اپ در پس‌زمینه است زنده نگه می‌دارد.
 *
 * **چرا لازم است؟** سرور هرمس یک فرزندِ پروسهٔ این اپ است (proot --kill-on-exit).
 * بدون این سرویس، وقتی اپ چند دقیقه به پس‌زمینه می‌رود، اندروید پروسهٔ اپ را
 * کشت می‌کند و سرور هم همراهِ آن می‌میرد؛ نتیجه: دستورات در حال اجرا نصفه
 * می‌مانند و چت قطع می‌شود.
 *
 * سه کار انجام می‌دهد:
 * ۱. با یک نوتیفیکیشنِ دائمی، پروسه را به اولویتِ «پیش‌زمینه» بالا می‌برد تا
 *    اندروید آن را نکشد.
 * ۲. یک WakeLock از نوع PARTIAL می‌گیرد تا CPU حتی زمانی که صفحه خاموش است
 *    بیدار بماند و دستوراتِ طولانی (ترمینال، جست‌وجو، build) بدون توقف اجرا شوند.
 * ۳. وضعیت سرور را زیر نظر دارد؛ هرگاه سرور واقعاً متوقف شد، سرویس هم تمام
 *    می‌شود تا نوتیفیکیشنِ بی‌مورد روی صفحه نماند.
 *
 * چرخهٔ کامل در [HermesRuntime.start] شروع و در [HermesRuntime.stop] تمام می‌شود.
 */
class HermesService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        acquireWakeLock()
        // نوتیفیکیشن باید بلافاصله نشان داده شود — startForeground در اندروید ۱۲+
        // با تأخیر بیشتر از ~۵ ثانیه باعث ANR/kill می‌شود.
        startForeground(NOTIF_ID, buildNotification(this, "سرور هرمس در حال اجرا…"))
        // هرگاه سرور واقعاً متوقف شد، سرویس خود را می‌بندیم.
        watcher = scope.launch {
            HermesRuntime.state.collectLatest { snap ->
                when (snap.state) {
                    HermesRuntime.State.STOPPED, HermesRuntime.State.FAILED -> {
                        stopForegroundCompat()
                        stopSelf()
                    }
                    else -> notifyRunning(snap.message.ifBlank { "سرور هرمس آماده است" })
                }
            }
        }
    }

    /** به‌روزرسانیِ متنِ نوتیفیکیشن بدون از نو ساختنِ آن (تا فلیکر نکند). */
    private fun notifyRunning(text: String) {
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
                ?.notify(NOTIF_ID, buildNotification(this, text))
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    /**
     * گرفتن WakeLock از نوع PARTIAL: صفحه و دکمه‌ها خاموش می‌شوند ولی CPU بیدار
     * می‌ماند. بدون این، در حالت Doze دستورهای طولانیِ هرمس متوقف می‌شوند.
     *
     * timeout گذاشته نمی‌شود: سرور هرمس ممکن است ساعت‌ها در پس‌زمینه کار کند
     * و WakeLock فقط تا وقتی سرویس زنده است نگه داشته می‌شود (در [onDestroy]
     * آزاد می‌شود). اندروید خودش در صورت کمبود شدیدِ باتری آن را می‌گیرد.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:server").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "wakeLock failed", it) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: اگر سیستم سرویس را کشت، دوباره ساخته می‌شود.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // کاربر اپ را از لیستِ اخیرها بست. چون سرور هنوز در حال اجراست و ممکن
        // است کارِ مهمی داشته باشد، سرویس را نگه می‌داریم (اندروید این را اجازه
        // می‌دهد چون پیش‌زمینه است). پروسهٔ واقعی وقتی کاربر «توقف سرور» را
        // بزند تمام می‌شود.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        }
    }

    companion object {
        private const val TAG = "HermesService"
        private const val CHANNEL_ID = "hermes_server"
        private const val NOTIF_ID = 9119

        /** شروعِ سرویس پیش‌زمینه (اگر از قبل روشن است، بی‌اثر است). */
        fun start(ctx: Context) {
            runCatching {
                val intent = Intent(ctx.applicationContext, HermesService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.applicationContext.startForegroundService(intent)
                } else {
                    ctx.applicationContext.startService(intent)
                }
            }.onFailure { Log.w(TAG, "start failed", it) }
        }

        /** توقفِ سرویس پیش‌زمینه. */
        fun stop(ctx: Context) {
            runCatching {
                ctx.applicationContext.stopService(Intent(ctx.applicationContext, HermesService::class.java))
            }
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID,
                "سرور هرمس",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "اعلامِ اجرای سرور هرمس در پس‌زمینه"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        private fun buildNotification(ctx: Context, text: String): Notification {
            val openIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                ctx, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hermes_status)
                .setContentTitle("هرمس")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    }
}
