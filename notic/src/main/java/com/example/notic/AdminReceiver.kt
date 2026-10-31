package com.example.notic

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

// While active, Android will not let Notic be uninstalled by mistake.
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "If you turn this off, Notic can be uninstalled."
}
