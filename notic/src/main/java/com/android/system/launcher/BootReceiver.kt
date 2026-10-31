package com.android.system.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun stamp(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

fun Context.note(key: String, value: String) =
    getSharedPreferences("n", Context.MODE_PRIVATE).edit().putString(key, value).apply()

// Starts Notic by itself after the phone restarts (after the first unlock) or the app is updated.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.note("dbg_boot", "${stamp()} ${i.action?.substringAfterLast('.')}")
        ReviveJob.schedule(c)
        try {
            c.startForegroundService(Intent(c, CameraService::class.java))
        } catch (e: Exception) {
            c.note("dbg_err", "${stamp()} boot: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
