package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.delay

class ZivpnEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "ZivpnEngine"
        const val LB_PORT    = 7777
        const val BASE_UZ_PORT = 7778
        fun findFreePort(): Int {
            return try { java.net.ServerSocket(0).use { it.localPort } } catch (_: Exception) { (10000..60000).random() }
        }
    }

    private var clashPort: Int = 7890

    @Volatile private var running = false
    @Volatile private var uzConnected = false
    @Volatile private var serverConnected = false
    private var uzProcesses: MutableList<Process> = mutableListOf()
    private var lbProcess: Process? = null
    var xrayProcess: Process? = null
    private var resolvedServerIp: String = ""
    @Volatile private var authErrorCount = 0
    @Volatile private var serverErrorCount = 0

    private fun log(msg: String) {
        KighmuLogger.info(TAG, msg)
    }

    override suspend fun start(): Int {
        running = true
        serverConnected = false
        clashPort = findFreePort()
        return withContext<Int>(Dispatchers.IO) {
            val host     = config.zivpnHost.trim()
            val password = config.zivpnPassword.trim()
            val port     = config.zivpnPort.trim().ifEmpty { "6000-19999" }
            val obfs     = config.zivpnObfs.trim()

            log("Démarrage tunnel UDP")
        authErrorCount = 0
        serverErrorCount = 0
            

            if (host.isEmpty()) throw IllegalArgumentException("Host non configure")
            if (password.isEmpty()) throw IllegalArgumentException("Password non configure")

            resolvedServerIp = try {
                java.net.InetAddress.getByName(host).hostAddress ?: host
            } catch (_: Exception) { host }
            

            val clashDir = File(context.filesDir, "clash").also { it.mkdirs() }
            val uzConfigFile = File(clashDir, "uz_config.json")
            uzConfigFile.writeText(buildUzConfig(resolvedServerIp, port, password, obfs))
            

            val nativeDir = context.applicationInfo.nativeLibraryDir
            

            val libuzCore   = File(nativeDir, "libuz_core.so")
            val libloadCore = File(nativeDir, "libload_core.so")
            val libxray     = File(nativeDir, "libxray.so")

            
            
            

            if (!libuzCore.exists())
                throw IllegalStateException("Composant UDP introuvable")
            if (!libxray.exists())
                throw IllegalStateException("Composant proxy introuvable")

            launchXray(libxray, clashDir)

            serverConnected = true
            clashPort
        }
    }



    private fun launchXray(binary: File, clashDir: File) {
        try {
            xrayProcess?.destroyForcibly()
            xrayProcess = null
            // Toujours allouer un nouveau port dynamique pour éviter conflits
            clashPort = findFreePort()
            // Vérification rapide que le port est bien libre
            var waited = 0
            while (waited < 1000) {
                val portFree = try { java.net.ServerSocket(clashPort).also { it.close() }; true } catch (_: Exception) { false }
                if (portFree) break
                clashPort = findFreePort() // Rechoisir si toujours occupé
                Thread.sleep(100)
                waited += 100
            }
            binary.setExecutable(true)
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val xrayConfig = """
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "listen": "127.0.0.1",
    "port": $clashPort,
    "protocol": "socks",
    "settings": {"auth": "noauth", "udp": true}
  }],
  "outbounds": [{
    "protocol": "socks",
    "settings": {
      "servers": [{
        "address": "127.0.0.1",
        "port": $LB_PORT
      }]
    }
  }]
}
""".trimIndent()
            val xrayCfgFile = File(clashDir, "xray_zivpn.json")
            xrayCfgFile.writeText(xrayConfig)
            val pb = ProcessBuilder(
                binary.absolutePath,
                "run", "-c", xrayCfgFile.absolutePath
            ).directory(clashDir).apply {
                environment()["LD_LIBRARY_PATH"] = nativeDir
                environment()["HOME"] = clashDir.absolutePath
                environment()["XRAY_LOCATION_ASSET"] = clashDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                redirectErrorStream(true)
            }
            xrayProcess = pb.start()
            log("Proxy Secure démarré")
            Thread {
                try {
                    xrayProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                        if (line.contains("started") || line.contains("listening")) {
                            log("Secure prêt")
                        }
                    }
                    val code = xrayProcess?.waitFor() ?: -1
                    log("Secure arrêté (code=$code)")
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            Thread.sleep(1200)
            val xrayAlive = try { xrayProcess?.isAlive ?: false } catch (_: Exception) { false }
            if (!xrayAlive) {
                val code = try { xrayProcess?.exitValue() } catch (_: Exception) { -999 }
                log("Erreur Xray (code=$code)")
                throw IllegalStateException("Proxy Xray arrêté au démarrage")
            }
        } catch (e: Exception) {
            log("Erreur Xray: ${e.message}")
            throw e
        }
    }

    private fun buildUzConfig(ip: String, portRange: String, password: String, obfs: String): String {
        return """{"server":"$ip:$portRange","obfs":"$obfs","auth":"$password","socks5":{"listen":"127.0.0.1:7778"},"insecure":true,"recvwindowconn":65536,"recvwindow":262144,"disable_mtu_discovery":true,"resolver":"8.8.8.8:53","down_mbps":50,"up_mbps":10}"""
    }

    private fun buildUzConfigForCore(ip: String, portRange: String, password: String, obfs: String, uzPort: Int): String {
        return """{"server":"$ip:$portRange","obfs":"$obfs","auth":"$password","socks5":{"listen":"127.0.0.1:$uzPort"},"insecure":true,"recvwindowconn":65536,"recvwindow":262144,"disable_mtu_discovery":true,"resolver":"8.8.8.8:53","down_mbps":50,"up_mbps":10}"""
    }

    fun launchUzOnly() {
        try {
            if (vpnService == null) { KighmuLogger.error(TAG, "VpnService non disponible"); return }
            launchUzCore()
            val uzPortOk = try { java.net.Socket("127.0.0.1", BASE_UZ_PORT).also { it.close() }; true } catch (_: Exception) { false }
            val lbPortOk = try { java.net.Socket("127.0.0.1", LB_PORT).also { it.close() }; true } catch (_: Exception) { false }
            log("UZ Core lancé - uz=$uzPortOk lb=$lbPortOk")
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur launchUzOnly: ${e.message}")
        }
    }

    override fun startTun2Socks(fd: Int) {
        try {
            if (vpnService == null) { KighmuLogger.error(TAG, "VpnService non disponible"); return }
            launchUzCore()
            
            val uzPortOk    = try { java.net.Socket("127.0.0.1", BASE_UZ_PORT).also { it.close() }; true } catch (_: Exception) { false }
            val lbPortOk    = try { java.net.Socket("127.0.0.1", LB_PORT).also { it.close() }; true } catch (_: Exception) { false }
            val clashPortOk = try { java.net.Socket("127.0.0.1", clashPort).also { it.close() }; true } catch (_: Exception) { false }
            
            log("Démarrage interface TUN")
            HevTun2Socks.init()
            if (HevTun2Socks.isAvailable) {
                HevTun2Socks.start(context, fd, LB_PORT, vpnService, mtu = 8500)
                log("Interface TUN active")
            } else {
                KighmuLogger.error(TAG, "Interface TUN non disponible")
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur TUN: ${e.message}")
        }
    }

    private fun launchUzCore() {
        val portRanges = config.zivpnPort.trim()
            .ifEmpty { "6000-19999" }
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        uzProcesses.forEach { try { it.destroyForcibly() } catch (_: Exception) {} }
        uzProcesses.clear()
        portRanges.forEachIndexed { index, portRange ->
            launchSingleUzCore(index, portRange, BASE_UZ_PORT + index)
        }
        launchLbCore()
    }

    private fun launchSingleUzCore(index: Int, portRange: String, uzPort: Int) {
        try {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val uzBin = File(nativeDir, "libuz_core.so")
            val obfs = config.zivpnObfs.trim()
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            val kighmuService = vpnService as? com.kighmu.vpn.vpn.KighmuVpnService
            val physicalNet = kighmuService?.underlyingNetwork ?: cm.activeNetwork
            if (physicalNet != null) {
                cm.bindProcessToNetwork(physicalNet)
                
            } else {
                KighmuLogger.warning(TAG, "Aucun réseau physique disponible")
            }
            val uzJsonInline = buildUzConfigForCore(
                config.zivpnHost.trim(),
                portRange,
                config.zivpnPassword.trim(),
                obfs,
                uzPort
            )
            val pb = ProcessBuilder(uzBin.absolutePath, "-s", obfs, "--config", uzJsonInline)
                .directory(context.filesDir)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = nativeDir
                    environment()["HOME"] = "/data/local/tmp"
                    environment()["TMPDIR"] = "/data/local/tmp"
                    redirectErrorStream(true)
                }
            val proc = pb.start()
            uzProcesses.add(proc)
            
            Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        
                        if (line.contains("connected to server") ||
                            line.contains("ZIVPN UDP running") ||
                            line.contains("SOCKS5 server listening")) {
                            uzConnected = true
                            
                        }
                        if (line.contains("auth") && (line.contains("failed") ||
                            line.contains("error") || line.contains("invalid") ||
                            line.contains("wrong") || line.contains("denied"))) {
                            if (authErrorCount < 3) { authErrorCount++; KighmuLogger.error(TAG, "Authentication failed, wrong password") }
                        }
                        if (line.contains("connection refused") ||
                            line.contains("connect: no route") ||
                            line.contains("timeout") ||
                            line.contains("unreachable")) {
                            if (serverErrorCount < 3) { serverErrorCount++; KighmuLogger.warning(TAG, "Serveur inaccessible, vérifiez l'adresse et le port") }
                        }
                    }
                } catch (_: Exception) {}
                val code = try { proc.exitValue() } catch (_: Exception) { null }
                if (code != null) KighmuLogger.warning(TAG, "Processus UDP terminé (code=$code)")
            }.apply { isDaemon = true }.start()
            Thread.sleep(500)
            val uzAlive = try { proc.isAlive } catch (_: Exception) { false }
            
            if (!uzAlive) {
                val code = try { proc.exitValue() } catch (_: Exception) { -999 }
                KighmuLogger.warning(TAG, "Canal UDP ${index+1} arrêté (code=$code)")
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur canal UDP: ${e.message}")
        }
    }

    private fun launchLbCore() {
        try {
            Thread.sleep(1000)
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val lbBin = File(nativeDir, "libload_core.so")
            if (!lbBin.exists()) {
                KighmuLogger.warning(TAG, "Composant balanceur introuvable")
                return
            }
            try { lbProcess?.destroyForcibly() } catch (_: Exception) {}
            val lbArgs = mutableListOf(lbBin.absolutePath, "-lport", "$LB_PORT", "-tunnel")
            uzProcesses.indices.forEach { i ->
                lbArgs.add("127.0.0.1:${BASE_UZ_PORT + i}")
            }
            
            val lbPb = ProcessBuilder(lbArgs)
                .directory(context.filesDir)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = nativeDir
                    environment()["HOME"] = context.filesDir.absolutePath
                    redirectErrorStream(true)
                }
            lbProcess = lbPb.start()
            log("Balanceur UDP démarré")
            Thread {
                try {
                    lbProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                        
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            Thread.sleep(800)
            val lbAlive = try { lbProcess?.isAlive ?: false } catch (_: Exception) { false }
            val lbOk = try { java.net.Socket("127.0.0.1", LB_PORT).also { it.close() }; true } catch (_: Exception) { false }
            
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur balanceur: ${e.message}")
        }
    }


    fun softRestart() {
        val xrayAlive = try { xrayProcess?.isAlive ?: false } catch (_: Exception) { false }
        if (!xrayAlive) return // Xray mort, softRestart impossible
        try { lbProcess?.destroyForcibly(); lbProcess = null } catch (_: Exception) {}
        uzProcesses.forEach { try { it.destroyForcibly() } catch (_: Exception) {} }
        uzProcesses.clear()
        Thread.sleep(300)
        launchUzCore()
        serverConnected = true
        running = true
        log("Tunnel UDP relancé (reconnexion légère)")
    }

    override suspend fun stop() {
        running = false
        serverConnected = false
        log("Arrêt tunnel UDP")
        // Arrêt HEV
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        // Tuer tous les processus immédiatement sans attendre
        try { xrayProcess?.destroyForcibly(); xrayProcess = null } catch (_: Exception) {}
        try { lbProcess?.destroyForcibly(); lbProcess = null } catch (_: Exception) {}
        uzProcesses.forEach { try { it.destroyForcibly() } catch (_: Exception) {} }
        uzProcesses.clear()
        // Nettoyage nucléaire natif — fire and forget
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c",
            "killall -9 libuz_core.so libload_core.so libxray.so 2>/dev/null; pkill -9 -f libuz_core 2>/dev/null; pkill -9 -f libload_core 2>/dev/null"
        )) } catch (_: Exception) {}
        log("Tunnel UDP arrêté")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && serverConnected
}
