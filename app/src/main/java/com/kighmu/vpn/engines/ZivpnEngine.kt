package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

    private val LB_PORT: Int get() = BASE_LB_PORT + (engineIndex * PORT_SPACING)
    private val BASE_UZ_PORT: Int get() = BASE_UZ_PORT_START + (engineIndex * PORT_SPACING)

    @Volatile private var running = false
    @Volatile private var uzConnected = false
    @Volatile private var serverConnected = false
    private var uzProcesses: MutableList<Process> = mutableListOf()
    private var lbProcess: Process? = null
    private var balancerThread: Thread? = null
    private var balancerServerSocket: ServerSocket? = null
    private var resolvedServerIp: String = ""
    @Volatile private var authErrorCount = 0
    @Volatile private var serverErrorCount = 0

    private fun log(msg: String) { KighmuLogger.info(TAG, msg) }

    override suspend fun start(): Int {
        running = true
        serverConnected = false
        return withContext<Int>(Dispatchers.IO) {
            val host     = config.zivpnHost.trim()
            val password = config.zivpnPassword.trim()
            log("Demarrage tunnel UDP")
            authErrorCount = 0
            serverErrorCount = 0
            if (host.isEmpty()) throw IllegalArgumentException("Host non configure")
            if (password.isEmpty()) throw IllegalArgumentException("Password non configure")
            resolvedServerIp = try {
                java.net.InetAddress.getByName(host).hostAddress ?: host
            } catch (_: Exception) { host }
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val libuzCore = File(nativeDir, "libuz_core.so")
            if (!libuzCore.exists()) throw IllegalStateException("Composant UDP introuvable")
            serverConnected = true
            LB_PORT
        }
    }

    private fun buildUzConfigForCore(ip: String, portRange: String, password: String, obfs: String, uzPort: Int): String {
        return """{"server":"$ip:$portRange","obfs":"$obfs","auth":"$password","socks5":{"listen":"127.0.0.1:$uzPort"},"insecure":true,"recvwindowconn":65536,"recvwindow":262144,"disable_mtu_discovery":true,"down_mbps":50,"up_mbps":10}"""
    }

    fun launchUzOnly() {
        try {
            if (vpnService == null) { KighmuLogger.error(TAG, "VpnService non disponible"); return }
            launchUzCore()
            val uzPortOk = try { Socket("127.0.0.1", BASE_UZ_PORT).also { it.close() }; true } catch (_: Exception) { false }
            val lbPortOk = try { Socket("127.0.0.1", LB_PORT).also { it.close() }; true } catch (_: Exception) { false }
            log("UZ Core lance - uz=$uzPortOk lb=$lbPortOk")
        } catch (e: Exception) { KighmuLogger.error(TAG, "Erreur launchUzOnly: ${e.message}") }
    }

    override fun startTun2Socks(fd: Int) {
        try {
            if (vpnService == null) { KighmuLogger.error(TAG, "VpnService non disponible"); return }
            launchUzCore()
            val uzPortOk = try { Socket("127.0.0.1", BASE_UZ_PORT).also { it.close() }; true } catch (_: Exception) { false }
            val lbPortOk = try { Socket("127.0.0.1", LB_PORT).also { it.close() }; true } catch (_: Exception) { false }
            log("Demarrage interface TUN — uz=$uzPortOk lb=$lbPortOk")
            HevTun2Socks.init()
            if (HevTun2Socks.isAvailable) {
                HevTun2Socks.start(context, fd, LB_PORT, vpnService, mtu = 1400)
                log("Interface TUN active -> LB_PORT=$LB_PORT")
            } else {
                KighmuLogger.error(TAG, "Interface TUN non disponible")
            }
        } catch (e: Exception) { KighmuLogger.error(TAG, "Erreur TUN: ${e.message}") }
    }

    private fun launchUzCore() {
        val portRanges = config.zivpnPort.trim()
            .ifEmpty { "6000-19999" }
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
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
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val kighmuService = vpnService as? com.kighmu.vpn.vpn.KighmuVpnService
            val physicalNet = kighmuService?.underlyingNetwork ?: cm.activeNetwork
            if (physicalNet != null) cm.bindProcessToNetwork(physicalNet)
            else KighmuLogger.warning(TAG, "Aucun reseau physique disponible")
            val uzJsonInline = buildUzConfigForCore(
                config.zivpnHost.trim(), portRange, config.zivpnPassword.trim(), obfs, uzPort)
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
                        if (line.contains("connected to server") || line.contains("ZIVPN UDP running") ||
                            line.contains("SOCKS5 server listening")) { uzConnected = true }
                        if (line.contains("auth") && (line.contains("failed") || line.contains("error") ||
                            line.contains("invalid") || line.contains("wrong") || line.contains("denied"))) {
                            if (authErrorCount < 3) { authErrorCount++; KighmuLogger.error(TAG, "Authentication failed, wrong password") }
                        }
                        if (line.contains("connection refused") || line.contains("connect: no route") ||
                            line.contains("timeout") || line.contains("unreachable")) {
                            if (serverErrorCount < 3) { serverErrorCount++; KighmuLogger.warning(TAG, "Serveur inaccessible") }
                        }
                    }
                } catch (_: Exception) {}
                val code = try { proc.exitValue() } catch (_: Exception) { null }
                if (code != null) KighmuLogger.warning(TAG, "Processus UDP termine (code=$code)")
            }.apply { isDaemon = true }.start()
            var uzWaited = 0
            while (uzWaited < 2000) {
                val alive = try { proc.isAlive } catch (_: Exception) { false }
                if (!alive) break
                val portUp = try { Socket("127.0.0.1", uzPort).also { it.close() }; true } catch (_: Exception) { false }
                if (portUp) break
                Thread.sleep(50); uzWaited += 50
            }
            val uzAlive = try { proc.isAlive } catch (_: Exception) { false }
            if (!uzAlive) {
                val code = try { proc.exitValue() } catch (_: Exception) { -999 }
                KighmuLogger.warning(TAG, "Canal UDP ${index+1} arrete (code=$code)")
            }
        } catch (e: Exception) { KighmuLogger.error(TAG, "Erreur canal UDP: ${e.message}") }
    }

    private fun launchLbCore() {
        try {
            Thread.sleep(200)
            stopKotlinBalancer()
            val upstreamCount = uzProcesses.size
            if (upstreamCount == 0) { KighmuLogger.warning(TAG, "Aucun upstream uz_core disponible"); return }
            val upstreams = (0 until upstreamCount).map { i -> Pair("127.0.0.1", BASE_UZ_PORT + i) }
            val counter = AtomicInteger(0)
            val executor = Executors.newCachedThreadPool()
            val ss = ServerSocket(LB_PORT, 128, java.net.InetAddress.getByName("127.0.0.1"))
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
                                val upstream = Socket(upHost, upPort)
                                upstream.tcpNoDelay = true; client.tcpNoDelay = true
                                val t1 = Thread {
                                    try { relay(client.inputStream, upstream.outputStream) } catch (_: Exception) {}
                                    try { upstream.close() } catch (_: Exception) {}
                                }.apply { isDaemon = true }
                                val t2 = Thread {
                                    try { relay(upstream.inputStream, client.outputStream) } catch (_: Exception) {}
                                    try { client.close() } catch (_: Exception) {}
                                }.apply { isDaemon = true }
                                t1.start(); t2.start()
                            } catch (_: Exception) { try { client.close() } catch (_: Exception) {} }
                        }
                    } catch (_: Exception) { break }
                }
                executor.shutdownNow()
                log("Balanceur Kotlin arrete")
            }.apply { isDaemon = true; name = "kighmu-lb" }
            balancerThread!!.start()
            var lbWaited = 0; var lbOk = false
            while (lbWaited < 1000) {
                lbOk = try { Socket("127.0.0.1", LB_PORT).also { it.close() }; true } catch (_: Exception) { false }
                if (lbOk) break
                Thread.sleep(30); lbWaited += 30
            }
            if (lbOk) log("Balanceur Kotlin operationnel en ${lbWaited}ms")
            else KighmuLogger.warning(TAG, "Balanceur Kotlin non pret")
        } catch (e: Exception) { KighmuLogger.error(TAG, "Erreur balanceur Kotlin: ${e.message}") }
    }

    private fun relay(input: InputStream, output: OutputStream) {
        val buf = ByteArray(8192); var n: Int
        while (input.read(buf).also { n = it } != -1) { output.write(buf, 0, n); output.flush() }
    }

    private fun stopKotlinBalancer() {
        try { balancerServerSocket?.close(); balancerServerSocket = null } catch (_: Exception) {}
        try { balancerThread?.interrupt(); balancerThread = null } catch (_: Exception) {}
    }

    fun softRestart() {
        try { lbProcess?.destroyForcibly(); lbProcess = null } catch (_: Exception) {}
        stopKotlinBalancer()
        uzProcesses.forEach { try { it.destroyForcibly() } catch (_: Exception) {} }
        uzProcesses.clear()
        Thread.sleep(100)
        launchUzCore()
        serverConnected = true; running = true
        log("Tunnel UDP relance (reconnexion legere)")
    }

    override suspend fun stop() {
        running = false; serverConnected = false
        log("Arret tunnel UDP")
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        try { lbProcess?.destroyForcibly(); lbProcess = null } catch (_: Exception) {}
        stopKotlinBalancer()
        uzProcesses.forEach { try { it.destroyForcibly() } catch (_: Exception) {} }
        uzProcesses.clear()
        try {
            val killProc = Runtime.getRuntime().exec(arrayOf("sh", "-c",
                "killall -9 libuz_core.so 2>/dev/null; pkill -9 -f libuz_core 2>/dev/null"))
            killProc.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
        log("Tunnel UDP arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && serverConnected
}
