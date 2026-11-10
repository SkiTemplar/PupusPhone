package com.pupus.phone.guard

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Periodic accessibility guard. After HyperOS kills the process (SwipeUpClean) nothing starts it
 * again until the user opens the app, so the service stays "crashed". A periodic job survives
 * that kill (it is not a force stop): the system starts the process and the guard rebinds.
 */
class GuardJobService : JobService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        AccessibilityGuard.check(applicationContext, "job")
        // The rebind writes twice, one second apart: keep the process alive until then.
        main.postDelayed({ jobFinished(params, false) }, FINISH_DELAY_MS)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        main.removeCallbacksAndMessages(null)
        return false
    }

    companion object {
        private const val TAG = "PupusGuard"
        private const val JOB_ID = 4201
        private const val FINISH_DELAY_MS = 3_000L

        /** The minimum period Android allows for a periodic job. */
        private const val PERIOD_MS = 15 * 60 * 1000L

        /** Idempotent: keeps an existing schedule. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, GuardJobService::class.java))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "Guard job not scheduled")
        }
    }
}
