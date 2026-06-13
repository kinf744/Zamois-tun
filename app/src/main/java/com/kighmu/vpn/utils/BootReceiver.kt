package com.kighmu.vpn.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kighmu.vpn.config.ConfigManager
import com.kighmu.vpn.vpn.KighmuVpnService

/**
 * Receives BOOT_COMPLETED and restarts VPN if it was active before reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            val prefs = context.getSharedPreferences("kighmu_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", false)

            if (autoStart) {
                KighmuLogger.info("BootReceiver", "Auto-starting VPN on boot")
                val vpnIntent = Intent(context, KighmuVpnService::class.java)
                    .apply { action = KighmuVpnService.ACTION_START }
                context.startForegroundService(vpnIntent)
            }
        }
    }
}
