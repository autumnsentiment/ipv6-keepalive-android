package com.example.ipv6keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action ||
            "android.intent.action.QUICKBOOT_POWERON" == intent.action ||
            "com.htc.intent.action.QUICKBOOT_POWERON" == intent.action) {

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val autoStart = prefs.getBoolean("auto_start", true)
            if (autoStart) {
                val serviceIntent = Intent(context, KeepAliveService::class.java).apply {
                    action = KeepAliveService.ACTION_START
                    putExtra("target", prefs.getString("target", "2001:4860:4860::8888"))
                    putExtra("interval", prefs.getInt("interval", 30))
                    putExtra("gateway", prefs.getString("gateway", ""))
                    putExtra("wifiRenewEnabled", prefs.getBoolean("wifi_renew_enabled", false))
                    putExtra("wifiRenewIntervalMin", prefs.getInt("wifi_renew_interval_min", 120))
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
