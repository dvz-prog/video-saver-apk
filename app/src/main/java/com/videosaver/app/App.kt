package com.videosaver.app

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CountDownLatch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // a primeira inicialização extrai o Python/yt-dlp e pode levar alguns segundos
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
            } catch (e: Exception) {
                initError = e.message ?: e.toString()
            } finally {
                ready.countDown()
            }
        }.start()
    }

    companion object {
        val ready = CountDownLatch(1)
        @Volatile var initError: String? = null
    }
}
