package dev.epro.tun2socks

import android.util.Log

object Tun2Socks {
    private var loaded = false

    init {
        try {
            System.loadLibrary("tun2socks_ssc")
            loaded = true
        } catch (e: Throwable) {
            Log.e("Tun2Socks_SSC", "Load failed: ${e.message}")
        }
    }

    val isAvailable get() = loaded

    external fun runTun2Socks(
        tunFd: Int, mtu: Int,
        netifAddr: String, netmask: String,
        socksAddr: String, udpgwAddr: String,
        udpgwTransparentDns: Boolean, loglevel: Int
    ): Int

    external fun terminateTun2Socks(): Int

    @JvmStatic
    fun logTun2Socks(@Suppress("UNUSED_PARAMETER") level: String, channel: String, message: String) {
        Log.d("Tun2Socks_SSC", "[$channel] $message")
    }
}
