package uk.jj.swarm.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import uk.jj.swarm.Fmt
import uk.jj.swarm.MainActivity
import uk.jj.swarm.R

object Notify {
    const val CH_PROGRESS = "progress"
    const val CH_DONE = "done"
    const val CH_ALERTS = "alerts"
    const val ID_PROGRESS = 1
    private var lastText = ""

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Watchlist and feeds", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(ctx: Context, hash: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (hash != null) i.putExtra("openHash", hash)
        return PendingIntent.getActivity(ctx, hash?.hashCode() ?: 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun svc(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(ctx, code, Intent(ctx, TorrentService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun build(ctx: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(open(ctx))
            .addAction(0, "Pause all", svc(ctx, TorrentService.ACT_PAUSE, 1))
            .addAction(0, "Resume all", svc(ctx, TorrentService.ACT_RESUME, 2))
            .addAction(0, "Exit", svc(ctx, TorrentService.ACT_EXIT, 3))
            .build()

    fun progress(ctx: Context, g: Global, blocked: String?, need: Boolean) {
        if (!TorrentService.running) return
        val title = when {
            blocked != null -> blocked
            g.downloading > 0 -> "${g.downloading} downloading" + if (g.seeding > 0) ", ${g.seeding} seeding" else ""
            g.seeding > 0 -> "${g.seeding} seeding"
            Crawler.running.value -> "Building the DHT index"
            need -> "Waiting"
            else -> "Idle"
        }
        val text = "↓ ${Fmt.speed(g.dl)}   ↑ ${Fmt.speed(g.ul)}"
        val key = "$title|$text"
        if (key == lastText) return
        lastText = key
        try { ctx.getSystemService(NotificationManager::class.java).notify(ID_PROGRESS, build(ctx, title, text)) } catch (e: Exception) { }
    }

    fun cancelProgress(ctx: Context) { try { ctx.getSystemService(NotificationManager::class.java).cancel(ID_PROGRESS) } catch (e: Exception) { } }

    fun completed(ctx: Context, name: String, hash: String) {
        try {
            val n = NotificationCompat.Builder(ctx, CH_DONE).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Download complete").setContentText(name).setStyle(NotificationCompat.BigTextStyle().bigText(name))
                .setAutoCancel(true).setContentIntent(open(ctx, hash)).build()
            ctx.getSystemService(NotificationManager::class.java).notify(hash.hashCode(), n)
        } catch (e: Exception) { }
    }

    fun alert(ctx: Context, id: Int, title: String, text: String) {
        try {
            val n = NotificationCompat.Builder(ctx, CH_ALERTS).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(open(ctx)).build()
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: Exception) { }
    }
}

/** Keeps the process alive while torrents are active. All the work happens in [Engine]. */
class TorrentService : Service() {
    companion object {
        const val ACT_PAUSE = "uk.jj.swarm.PAUSE_ALL"
        const val ACT_RESUME = "uk.jj.swarm.RESUME_ALL"
        const val ACT_EXIT = "uk.jj.swarm.EXIT"
        @Volatile var running = false
        @Volatile private var startAsked = 0L
        @Volatile private var idleTicks = 0

        /** Called every second by the engine: starts the service when there is work, stops it after 15 idle seconds. */
        fun sync(ctx: Context, want: Boolean) {
            if (want) {
                idleTicks = 0
                if (!running && System.currentTimeMillis() - startAsked > 8000) {
                    startAsked = System.currentTimeMillis()
                    try { ContextCompat.startForegroundService(ctx, Intent(ctx, TorrentService::class.java)) }
                    catch (e: Exception) { Log.w("Swarm", "cannot start service from background: ${e.message}") }
                }
            } else if (running && ++idleTicks > 15) {
                try { ctx.stopService(Intent(ctx, TorrentService::class.java)) } catch (e: Exception) { }
            }
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Notify.channels(this)
        val n = Notify.build(this, "Swarm", "Starting…")
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notify.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(Notify.ID_PROGRESS, n)
        } catch (e: Exception) { Log.w("Swarm", "startForeground", e) }
        try {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swarm:downloads").apply { setReferenceCounted(false); acquire() }
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "swarm:wifi").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Engine.start(this)
        val action = intent?.action
        if (action != null) Thread {
            // The widget can start the service cold, so wait for the saved torrents to load before acting.
            var n = 0
            while (!Engine.ready.value && n++ < 100) Thread.sleep(100)
            when (action) {
                ACT_PAUSE -> Engine.pauseAll()
                ACT_RESUME -> Engine.resumeAll()
                ACT_EXIT -> Engine.shutdown()
            }
        }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { wake?.release() } catch (e: Exception) { }
        try { wifi?.release() } catch (e: Exception) { }
        super.onDestroy()
    }
}

/** Home-screen widget: current speeds, active count, pause / resume all. */
class SwarmWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { refresh(context) }

    companion object {
        private var last = ""
        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, SwarmWidget::class.java))
                if (ids.isEmpty()) return
                val g = Engine.global.value
                val line1 = "↓ ${Fmt.speed(g.dl)}   ↑ ${Fmt.speed(g.ul)}"
                val line2 = Engine.blocked.value ?: "${g.downloading} downloading · ${g.seeding} seeding"
                if ("$line1|$line2" == last) return
                last = "$line1|$line2"
                val rv = RemoteViews(ctx.packageName, R.layout.widget)
                rv.setTextViewText(R.id.w_speed, line1)
                rv.setTextViewText(R.id.w_status, line2)
                val fl = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                rv.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 10, Intent(ctx, MainActivity::class.java), fl))
                rv.setOnClickPendingIntent(R.id.w_pause, PendingIntent.getForegroundService(ctx, 11, Intent(ctx, TorrentService::class.java).setAction(TorrentService.ACT_PAUSE), fl))
                rv.setOnClickPendingIntent(R.id.w_resume, PendingIntent.getForegroundService(ctx, 12, Intent(ctx, TorrentService::class.java).setAction(TorrentService.ACT_RESUME), fl))
                mgr.updateAppWidget(ids, rv)
            } catch (e: Exception) { }
        }
    }
}
