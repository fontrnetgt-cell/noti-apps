package com.example.notic

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

// Dial *#*#120052#*#* in the Phone app to bring the Notic icon back.
// Works with the stock Android dialer; the code is unique to your build so it cannot be guessed. Some phone makers' own dialer apps don't send this signal.
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        setIconVisible(c, true)
        c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun setIconVisible(c: Context, visible: Boolean) {
    val alias = ComponentName(c, "com.example.notic.LauncherAlias")
    c.packageManager.setComponentEnabledSetting(
        alias,
        if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP
    )
}
