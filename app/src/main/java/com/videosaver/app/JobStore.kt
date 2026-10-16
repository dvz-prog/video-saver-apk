package com.videosaver.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

class Job(val id: String, val url: String, val mode: String, val playlist: Boolean) {
    @Volatile var status: String = "running" // running | done | error
    @Volatile var percent: Float = 0f
    @Volatile var text: String = "Iniciando…"
    @Volatile var file: String? = null
    @Volatile var cancelled: Boolean = false
}

object JobStore {
    private val jobs = LinkedHashMap<String, Job>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun add(job: Job) {
        jobs[job.id] = job
        while (jobs.size > 20) {
            val old = jobs.entries.firstOrNull { it.value.status != "running" }?.key ?: break
            jobs.remove(old)
        }
        changed()
    }

    @Synchronized fun get(id: String): Job? = jobs[id]

    @Synchronized fun snapshot(): List<Job> = jobs.values.toList().reversed()

    @Synchronized
    fun clearFinished() {
        jobs.values.removeAll { it.status != "running" }
        changed()
    }

    fun changed() {
        main.post { listeners.forEach { it() } }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
}
