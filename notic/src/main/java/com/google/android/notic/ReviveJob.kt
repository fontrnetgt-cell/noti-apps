package com.google.android.notic

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent

// Backup: Android itself re-creates this job after a restart and runs it about every 15 minutes.
// If Notic's service is not running, it starts it again.
class ReviveJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        note("dbg_job", stamp())
        try {
            startForegroundService(Intent(this, CameraService::class.java))
        } catch (e: Exception) {
            note("dbg_err", "${stamp()} job: ${e.javaClass.simpleName}: ${e.message}")
        }
        return false
    }

    override fun onStopJob(p: JobParameters) = false

    companion object {
        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(42) != null) return
            js.schedule(
                JobInfo.Builder(42, ComponentName(c, ReviveJob::class.java))
                    .setPersisted(true)
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
            )
        }
    }
}
