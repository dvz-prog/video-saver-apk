package com.videosaver.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Serviço em primeiro plano: mantém o download rodando com o app fechado e mostra a notificação com progresso. */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.videosaver.app.START"
        const val ACTION_CANCEL = "com.videosaver.app.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_ID = "id"
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val NOTIF_ID = 1
    }

    private val pool = Executors.newFixedThreadPool(2)
    private val active = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastNotify = 0L
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Downloads em andamento", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Downloads concluídos", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIF_ID,
            progressNotification("Preparando…", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val i = intent
        if (i == null) {
            stopSelfClean()
            return START_NOT_STICKY
        }
        when (i.action) {
            ACTION_START -> {
                val job = Job(
                    id = System.currentTimeMillis().toString(36) + (0..999).random(),
                    url = i.getStringExtra(EXTRA_URL) ?: "",
                    mode = i.getStringExtra(EXTRA_MODE) ?: "best",
                    playlist = i.getBooleanExtra(EXTRA_PLAYLIST, false)
                )
                JobStore.add(job)
                active.incrementAndGet()
                pool.execute {
                    try {
                        runJob(job)
                    } finally {
                        if (active.decrementAndGet() == 0) main.post { stopSelfClean() }
                    }
                }
            }
            ACTION_CANCEL -> {
                val id = i.getStringExtra(EXTRA_ID)
                if (id != null) {
                    JobStore.get(id)?.cancelled = true
                    try {
                        YoutubeDL.getInstance().destroyProcessById(id)
                    } catch (_: Exception) {
                    }
                }
                if (active.get() == 0) stopSelfClean()
            }
            else -> if (active.get() == 0) stopSelfClean()
        }
        return START_NOT_STICKY
    }

    private fun stopSelfClean() {
        if (active.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun runJob(job: Job) {
        val tmp = File(cacheDir, "dl_" + job.id).apply { mkdirs() }
        try {
            update(job, "Preparando o yt-dlp…", 0f)
            App.ready.await()
            App.initError?.let { throw IllegalStateException("Falha ao iniciar o yt-dlp: $it") }
            if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(job.url)) {
                throw IllegalArgumentException("Link inválido.")
            }

            val req = YoutubeDLRequest(job.url)
            req.addOption(if (job.playlist) "--yes-playlist" else "--no-playlist")
            req.addOption("--windows-filenames")
            req.addOption("--concurrent-fragments", "4")
            req.addOption("--cache-dir", File(cacheDir, "ytdlp").absolutePath)
            req.addOption("-o", tmp.absolutePath + "/%(title).150B [%(id)s].%(ext)s")
            when (job.mode) {
                "audio" -> {
                    req.addOption("-f", "ba/b")
                    req.addOption("-x")
                    req.addOption("--audio-format", "mp3")
                    req.addOption("--audio-quality", "0")
                }
                "1080", "720", "480" -> {
                    val h = job.mode
                    req.addOption("-f", "bv*[height<=$h]+ba/b[height<=$h]")
                    req.addOption("-S", "res,vcodec:h264,acodec:m4a")
                    req.addOption("--merge-output-format", "mp4")
                }
                else -> {
                    req.addOption("-f", "bv*+ba/b")
                    req.addOption("-S", "res,vcodec:h264,acodec:m4a")
                    req.addOption("--merge-output-format", "mp4")
                }
            }

            YoutubeDL.getInstance().execute(req, job.id) { progress, eta, _ ->
                val txt = if (progress < 0f) "Baixando…"
                else if (eta > 0) "%.0f%% · faltam %ds".format(progress, eta)
                else "%.0f%%".format(progress)
                update(job, txt, progress)
            }

            update(job, "Salvando o arquivo…", 100f)
            val files = tmp.listFiles()
                ?.filter {
                    it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                        !it.name.endsWith(".temp")
                }
                ?.sortedBy { it.name }
                ?: emptyList()
            if (files.isEmpty()) throw IllegalStateException("Nenhum arquivo foi gerado.")
            var saved = ""
            for (f in files) saved = Storage.save(this, f)

            job.file = saved
            job.percent = 100f
            job.text = "Concluído"
            job.status = "done"
            JobStore.changed()
            notifyDone(saved)
        } catch (e: Exception) {
            job.status = "error"
            job.text = if (job.cancelled) "Cancelado" else cleanError(e)
            JobStore.changed()
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun cleanError(e: Exception): String {
        val msg = e.message ?: e.toString()
        return (msg.lines().lastOrNull { it.contains("ERROR") } ?: msg).take(300)
    }

    private fun update(job: Job, text: String, percent: Float) {
        job.text = text
        job.percent = if (percent < 0f) 0f else percent
        JobStore.changed()
        val now = System.currentTimeMillis()
        if (now - lastNotify > 700) {
            lastNotify = now
            nm.notify(
                NOTIF_ID,
                progressNotification("${active.get()} download(s) · $text", job.percent.toInt(), percent <= 0f)
            )
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun progressNotification(text: String, percent: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(0xFFE60000.toInt()) // vermelho
            .setContentTitle("Video Saver")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setProgress(100, percent, indeterminate)
            .build()

    private fun notifyDone(file: String) {
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download concluído")
            .setContentText(file)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 100, n)
    }

    override fun onDestroy() {
        pool.shutdown()
        super.onDestroy()
    }
}
