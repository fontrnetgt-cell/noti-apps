package com.example.notic

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

fun hideIcon(c: Context) {
    val alias = ComponentName(c, "com.example.notic.LauncherAlias")
    c.packageManager.setComponentEnabledSetting(
        alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
}
