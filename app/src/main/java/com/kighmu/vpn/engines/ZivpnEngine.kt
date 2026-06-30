package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.delay
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

class ZivpnEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService? = null,
    private val engineIndex: Int = 0
) : TunnelEngine {

    companion object {
        const val TAG = "ZivpnEngine"
        private const val BASE_LB_PORT = 7777
        private const val BASE_UZ_PORT_START = 7778
        private const val PORT_SPACING = 100
        fun findFreePort(): Int {
            return try { java.net.ServerSocket(0).use { it.localPort } } catch (_: Exception) { (10000..60000).random() }
        }
    }

    // Ports dynamiques par instance pour éviter collisions multi-profil ZIVPN
    private val LB_PORT: Int get() = BASE_LB_PORT + (engineIndex * PORT_SPACING)
    private val BASE_UZ_PORT: Int get() = BASE_UZ_PORT_START + (engineIndex * PORT_SPACING)

    private var clashPort: Int = 7890

    @Volatile private var running = false
    @Volatile private var uzConnected = false
    @Volatile private var serverConnected = false
    private var uzProcesses: MutableList<Process> = mutableListOf()
    private var lbProcess: Process? = null
    var xrayProcess: Process? = null
    private var balancerThread: Thread? = null
    private var balancerServerSocket: ServerSocket? = null
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
            // Attente active : Xray prêt dès que le port répond (max 3s)
            var xrayReady = false
            var xrayWaited = 0
            while (xrayWaited < 3000) {
                val alive = try { xrayProcess?.isAlive ?: false } catch (_: Exception) { false }
                if (!alive) break
                val portUp = try {
                    java.net.Socket("127.0.0.1", clashPort).also { it.close() }; true
                } catch (_: Exception) { false }
                if (portUp) { xrayReady = true; break }
                Thread.sleep(50)
                xrayWaited += 50
            }
            val xrayAlive = try { xrayProcess?.isAlive ?: false } catch (_: Exception) { false }
            if (!xrayAlive || !xrayReady) {
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
        return """{"server":"$ip:$portRange","obfs":"$obfs","auth":"$password","socks5":{"listen":"127.0.0.1:7778"},"insecure":true,"recvwindowconn":65536,"recvwindow":262144,"disable_mtu_discovery":true,"down_mbps":50,"up_mbps":10}"""
    }

    private fun buildUzConfigForCore(ip: String, portRange: String, password: String, obfs: String, uzPort: Int): String {
        return """{"server":"$ip:$portRange","obfs":"$obfs","auth":"$password","socks5":{"listen":"127.0.0.1:$uzPort"},"insecure":true,"recvwindowconn":65536,"recvwindow":262144,"disable_mtu_discovery":true,"down_mbps":50,"up_mbps":10}"""
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
            // Attente active uz_core (max 2s)
            var uzWaited = 0
            while (uzWaited < 2000) {
                val alive = try { proc.isAlive } catch (_: Exception) { false }
                if (!alive) break
                val portUp = try {
                    java.net.Socket("127.0.0.1", uzPort).also { it.close() }; true
                } catch (_: Exception) { false }
                if (portUp) break
                Thread.sleep(50)
                uzWaited += 50
            }
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
            Thread.sleep(200)
            stopKotlinBalancer()

            val upstreamCount = uzProcesses.size
            if (upstreamCount == 0) {
                KighmuLogger.warning(TAG, "Aucun upstream uz_core disponible")
                return
            }

            val upstreams = (0 until upstreamCount).map { i ->
                Pair("127.0.0.1", BASE_UZ_PORT + i)
            }
            val counter = java.util.concurrent.atomic.AtomicInteger(0)
            val executor = java.util.concurrent.Executors.newCachedThreadPool()

            val ss = java.net.ServerSocket(LB_PORT, 128,
                java.net.InetAddress.getByName("127.0.0.1"))
            ss.reuseAddress = true
            balancerServerSocket = ss

            balancerThread = Thread {
                log("Balanceur Kotlin sur port $LB_PORT (${upstreams.size} upstreams)")
                while (!Thread.currentThread().isInterrupted && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        executor.submit {
                            val idx = counter.getAndIncrement() % upstreams.size
                            val (upHost, upPort) = upstreams[idx]
                            try {
                                val upstream = java.net.Socket(upHost, upPort)
                                upstream.tcpNoDelay = true
                                client.tcpNoDelay = true
                                val t1 = Thread {
                                    try { relay(client.inputStream, upstream.outputStream) } catch (_: Exception) {}
                                    try { upstream.close() } catch (_: Exception) {}
                                }.apply { isDaemon = true }
                                val t2 = Thread {
                                    try { relay(upstream.inputStream, client.outputStream) } catch (_: Exception) {}
                                    try { client.close() } catch (_: Exception) {}
                                }.apply { isDaemon = true }
                                t1.start(); t2.start()
                            } catch (_: Exception) {
                                try { client.close() } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) { break }
                }
                executor.shutdownNow()
                log("Balanceur Kotlin arrêté")
            }.apply { isDaemon = true; name = "kighmu-lb" }
            balancerThread!!.start()

            // Attente active balanceur (max 1s)
            var lbWaited = 0
            var lbOk = false
            while (lbWaited < 1000) {
                lbOk = try {
                    java.net.Socket("127.0.0.1", LB_PORT).also { it.close() }; true
                } catch (_: Exception) { false }
                if (lbOk) break
                Thread.sleep(30)
                lbWaited += 30
            }
            if (lbOk) log("Balanceur Kotlin opérationnel en ${lbWaited}ms")
            else KighmuLogger.warning(TAG, "Balanceur Kotlin non prêt")

        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur balanceur Kotlin: ${e.message}")
        }
    }

    private fun relay(input: java.io.InputStream, output: java.io.OutputStream) {
        val buf = ByteArray(8192)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            output.write(buf, 0, n)
            output.flush()
        }
    }

    private fun stopKotlinBalancer() {
        try { balancerServerSocket?.close(); balancerServerSocket = null } catch (_: Exception) {}
        try { balancerThread?.interrupt(); balancerThread = null } catch (_: Exception) {}
    }



    fun softRestart() {
        val xrayAlive = try { xrayProcess?.isAlive ?: false } catch (_: Exception) { false }
        if (!xrayAlive) return // Xray mort, softRestart impossible
        try { lbProcess?.destroyForcibly(); lbProcess = null } catch (_: Exception) {}
        stopKotlinBalancer()
        uzProcesses.forEach { try { it.destroyForcibly() } catch (_: Exception) {} }
        uzProcesses.clear()
        Thread.sleep(100)
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
        stopKotlinBalancer()
        uzProcesses.forEach { try { it.destroyForcibly() } catch (_: Exception) {} }
        uzProcesses.clear()
        // Nettoyage nucléaire natif — fire and forget
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c",
            "killall -9 libuz_core.so libxray.so 2>/dev/null; pkill -9 -f libuz_core 2>/dev/null"
        )) } catch (_: Exception) {}
        log("Tunnel UDP arrêté")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && serverConnected
}
