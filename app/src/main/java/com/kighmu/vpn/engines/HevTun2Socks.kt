package com.kighmu.vpn.engines

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantLock

object HevTun2Socks {
    const val TAG = "HevTun2Socks"
    private var loaded = false
    private val lock = ReentrantLock()
    @Volatile private var running = false

    fun init() {
        if (!loaded) {
            try {
                Class.forName("hev.htproxy.TProxyService")
                hev.htproxy.TProxyService.load()
                loaded = hev.htproxy.TProxyService.isAvailable
                Log.i(TAG, "Init OK loaded=$loaded")
            } catch (e: Throwable) {
                Log.e(TAG, "Init failed: ${e.message} | cause: ${e.cause?.message}")
                try {
                    System.loadLibrary("hev_jni")
                    Log.i(TAG, "hev_jni chargé directement ✅")
                } catch (e2: Throwable) {
                    Log.e(TAG, "hev_jni direct load failed: ${e2.message}")
                }
            }
        }
    }

    val isAvailable get() = loaded

    fun start(context: Context, fd: Int, socksPort: Int, vpnService: android.net.VpnService, mtu: Int = 1500, rateLimitBps: Long = 0) {
        lock.lock()
        try {
            if (running) {
                Log.i(TAG, "Stop précédent avant redémarrage")
                hev.htproxy.TProxyService.TProxyStopService()
                running = false
            }
            val config = buildConfig(socksPort, mtu, rateLimitBps)
            val configFile = File(context.cacheDir, "hev_config.yaml")
            configFile.writeText(config)
            Log.i(TAG, "Démarrage hev fd=$fd port=$socksPort")
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
            running = true
        } finally {
            lock.unlock()
        }
    }

    fun startMulti(context: Context, fd: Int, ports: List<Int>, vpnService: android.net.VpnService, mtu: Int = 1500) {
        if (ports.isEmpty()) return
        lock.lock()
        try {
            if (running) {
                hev.htproxy.TProxyService.TProxyStopService()
                running = false
            }
            val mainPort = ports.first()
            val config = buildConfigMulti(mainPort, ports, mtu)
            val configFile = File(context.cacheDir, "hev_config_multi.yaml")
            configFile.writeText(config)
            Log.i(TAG, "Démarrage hev multi-SOCKS fd=$fd ports=$ports")
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
            running = true
        } finally {
            lock.unlock()
        }
    }

    fun stop() {
        lock.lock()
        try {
            if (loaded && running) {
                running = false
                hev.htproxy.TProxyService.TProxyStopService()
                Log.i(TAG, "HevTun2Socks arrêté")
            }
        } finally {
            lock.unlock()
        }
    }

    private fun buildConfig(socksPort: Int, mtu: Int, rateLimitBps: Long = 0): String {
        val rateSection = if (rateLimitBps > 0) "\n  rate-limit-bps: $rateLimitBps" else ""
        return """
tunnel:
  mtu: $mtu
  ipv4: 198.18.0.1

socks5:
  port: $socksPort
  address: 127.0.0.1
  udp: udp

misc:
  log-level: warn${rateSection}
""".trimIndent()
    }

    private fun buildConfigMulti(mainPort: Int, ports: List<Int>, mtu: Int): String {
        return """
tunnel:
  mtu: $mtu
  ipv4: 198.18.0.1

socks5:
  port: $mainPort
  address: 127.0.0.1
  udp: udp

multi:
  ports: ${ports.joinToString(", ")}

misc:
  log-level: warn
""".trimIndent()
    }
}
