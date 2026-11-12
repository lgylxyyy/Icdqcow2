package org.icdqcow2.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.icdqcow2.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 批量任务的**前台服务**（用户 2026-11-12 拍板 P2③：后台任务 + 通知）—— 参考实现。
 *
 * **为什么必须是前台服务**：批量压缩/释放是几十分钟级的写盘任务。普通后台
 * 进程会被系统回收（用户切走应用几分钟后，任务就没了）；前台服务 + 通知
 * 是系统认可的「用户明确知道在跑」的长任务形态 —— 息屏、切换应用都继续跑。
 *
 * **通知里有什么**：当前文件 + 整体进度 + 一个「停止队列」按钮。
 * 停止是「当前项做完就停」，不打断正在写的那一项（见 [BatchEngine]）。
 *
 * **唤醒锁**：写盘时手机进 doze 会限 IO（可能把几十分钟的任务拖成几小时，
 * 甚至中途被杀）。持一个 PARTIAL_WAKE_LOCK 把它按住，任务结束立刻放。
 */
class BatchService : Service() {

    companion object {
        /** 通知里「停止」按钮的动作。 */
        const val ACTION_STOP = "org.icdqcow2.action.BATCH_STOP"

        private const val CHANNEL_ID = "batch_tasks"
        private const val NOTIF_ID = 4207

        /** 启动服务（跑批量前调一次）。 */
        fun start(ctx: Context) {
            val i = Intent(ctx, BatchService::class.java)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (t: Throwable) {
                // 某些定制系统会禁前台服务；任务照样能跑，只是没有通知
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        BatchEngine.attach(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) BatchEngine.requestStop()
        // 5 秒内必须 startForeground，否则系统会判 ANR 并杀掉进程
        startForeground(NOTIF_ID, buildNotification(BatchEngine.state.value))
        acquireWakeLock()
        scope.launch {
            BatchEngine.state.collect { st ->
                if (!st.running) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                notify(buildNotification(st))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            BatchEngine.tr("batch.title", "批量任务"),
            NotificationManager.IMPORTANCE_LOW,   // 不发声、不打断：进度类通知
        )
        ch.description = BatchEngine.tr("batch.notifChannel", "批量压缩 / 批量释放空间的进度")
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(st: BatchEngine.State): Notification {
        val cur = st.items.getOrNull(st.current)
        val title = cur?.let { fileNameOf(it.path) }
            ?: BatchEngine.tr("batch.title", "批量任务")
        val text = if (cur != null) {
            "${st.phase.ifEmpty { BatchEngine.tr("batch.working", "处理中") }} · ${st.current + 1}/${st.items.size}"
        } else {
            BatchEngine.tr("batch.preparing", "准备中")
        }
        val pct = (st.overall * 100).toInt().coerceIn(0, 100)
        val open = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, false)
            .setContentIntent(open)
            .addAction(0, BatchEngine.tr("batch.stop", "停止队列（当前项做完就停）"), stop)
            .build()
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, n)
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Icdqcow2:batch").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)   // 最多 4 小时，防呆上限
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}

/** 取文件名（通知标题用）。 */
private fun fileNameOf(path: String): String = path.substringAfterLast('/')
