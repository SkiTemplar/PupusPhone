package com.pupus.phone.api

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Fixed-size worker pool for NanoHTTPD (the default runner spawns one thread per
 * connection). Connections beyond the pool and its queue are closed immediately.
 */
class BoundedAsyncRunner(workers: Int, queued: Int) : NanoHTTPD.AsyncRunner {
    private val running: MutableSet<NanoHTTPD.ClientHandler> = ConcurrentHashMap.newKeySet()
    private val pool = ThreadPoolExecutor(
        workers, workers, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, ArrayBlockingQueue(queued),
    ) { runnable -> Thread(runnable, "PupusApi-worker").apply { isDaemon = true } }

    override fun exec(handler: NanoHTTPD.ClientHandler) {
        running.add(handler)
        try {
            pool.execute(handler)
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Device API busy; dropping connection", e)
            running.remove(handler)
            handler.close()
        }
    }

    override fun closed(handler: NanoHTTPD.ClientHandler) {
        running.remove(handler)
    }

    override fun closeAll() {
        running.toList().forEach { it.close() }
        running.clear()
    }

    private companion object {
        const val TAG = "PupusApi"
        const val KEEP_ALIVE_SECONDS = 30L
    }
}
