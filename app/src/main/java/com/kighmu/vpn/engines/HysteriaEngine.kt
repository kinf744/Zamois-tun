package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class HysteriaEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService?,
    private val assignedSocksPort: Int = 0, // Port assigné par MultiHysteriaEngine
    private val profileIndex: Int = 0
) : TunnelEngine {

    companion object {
        const val TAG = "HysteriaEngine"
        fun getFreePort(): Int = try { java.net.ServerSocket(0).use { it.localPort } } catch (_: Exception) { 10800 } // Fallback port
    }

    private val hConfig = config.hysteria
    @Volatile private var running = false
    @Volatile private var hysteriaProcess: Process? = null
    @Volatile private var tun2socksProcess: Process? = null
    @Volatile private var socksPort: Int = 0
    @Volatile private var serverConnected = false
    @Volatile private var vpnPfd: ParcelFileDescriptor? = null

    init {
        socksPort = if (assignedSocksPort > 0) assignedSocksPort else getFreePort()
    }

    private fun log(msg: String) {
        KighmuLogger.info(TAG, msg)
        try {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()).format(java.util.Date())
            val line = "[$ts] [HYSTERIA] $msg\n"
            val f = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS),
                "kighmu_close.txt")
            f.appendText(line)
        } catch (_: Exception) {}
    }

    override suspend fun start(): Int {
        running = true
        serverConnected = false
        return withContext(Dispatchers.IO) {
            // Le port SOCKS est déjà alloué dynamiquement dans l'init block
            val ip = try {
                java.net.InetAddress.getByName(hConfig.serverAddress).hostAddress
                    ?: hConfig.serverAddress
            } catch (_: Exception) { hConfig.serverAddress }

            val portHopping = if (hConfig.portHopping.isNotBlank())
                hConfig.portHopping else "20000-50000"
            val server = "$ip:$portHopping"
            log("Démarrage Hysteria: $server")

            val configFile = writeConfig(server)
            val binary = extractBinary("libhysteria.so")
                ?: throw Exception("libhysteria.so introuvable")

            try { hysteriaProcess?.destroy() } catch (_: Exception) {}
            hysteriaProcess = null

            startHysteriaProcess(binary, configFile)

            // Attendre connexion serveur via log
            repeat(30) {
                if (!serverConnected) {
                    try { hysteriaProcess?.exitValue(); return@repeat } catch (_: Exception) {}
                    Thread.sleep(500)
                }
            }

            if (!serverConnected) throw Exception("Hysteria: connexion serveur impossible")
            log("Hysteria prêt sur port $socksPort ✅")
            socksPort
        }
    }

    private fun writeConfig(server: String): File {
        val file = File(context.filesDir, "hysteria_config_$profileIndex.json")
        val config = """{
  "server": "$server",
  "obfs": "${hConfig.obfsPassword}",
  "auth_str": "${hConfig.authPassword}",
  "up_mbps": ${hConfig.uploadMbps},
  "down_mbps": ${hConfig.downloadMbps},
  "retry": 3,
  "retry_interval": 1,
  "socks5": {
    "listen": "127.0.0.1:$socksPort"
  },
  "insecure": true,
  "recv_window_conn": 4194304,
  "recv_window": 16777216
}"""
        file.writeText(config)
        log("Config écrite: $server")
        return file
    }

    private fun extractBinary(name: String): File? {
        val bin = File(context.applicationInfo.nativeLibraryDir, name)
        if (bin.exists()) { bin.setExecutable(true); return bin }
        log("$name introuvable dans ${context.applicationInfo.nativeLibraryDir}")
        return null
    }

    private fun startHysteriaProcess(binary: File, configFile: File) {
        val cmd = arrayOf(binary.absolutePath, "client",
            "--config", configFile.absolutePath)
        val pb = ProcessBuilder(*cmd)
        pb.environment()["HOME"] = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.redirectErrorStream(true)
        hysteriaProcess = pb.start()
        log("Hysteria PID démarré")

        Thread {
            try {
                var loggedConnected = false
                hysteriaProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    if (running) {
                        val lineLower = line.lowercase()
                        when {
                            (lineLower.contains("connected") ||
                             (lineLower.contains("socks5") && line.contains("127.0.0.1:")) ||
                             (lineLower.contains("udp") && (lineLower.contains("session") || lineLower.contains("running"))))
                            && !loggedConnected -> {
                                serverConnected = true
                                loggedConnected = true
                                Regex("""127\.0\.0\.1:(\d+)""").find(line)
                                    ?.groupValues?.get(1)?.toIntOrNull()?.let {
                                        if (it > 0) socksPort = it
                                    }
                                log("Hysteria connecté ✅")
                            }
                            lineLower.contains("error") || lineLower.contains("fatal") ->
                                log("Hysteria erreur: $line")
                        }
                    }
                }
                val code = hysteriaProcess?.waitFor() ?: -1
                log("Hysteria exit: $code")
                serverConnected = false
            } catch (e: Exception) { log("thread: ${e.message}") }
        }.start()
    }

    override fun startTun2Socks(fd: Int) {
        try {
            if (vpnService == null) {
                log("ERREUR: VpnService est null, impossible de démarrer HevTun2Socks")
                return
            }
            log("Démarrage HevTun2Socks (Principe Build #736) fd=$fd port=$socksPort")
            HevTun2Socks.init()
            if (HevTun2Socks.isAvailable) {
                HevTun2Socks.start(context, fd, socksPort, vpnService, mtu = 8500)
                log("HevTun2Socks démarré avec succès ✅")
            } else {
                log("ERREUR: HevTun2Socks non disponible (libtun2socks.so non chargée)")
            }
        } catch (e: Exception) {
            log("Erreur HevTun2Socks: ${e.message}")
        }
    }

    override suspend fun stop() {
        running = false
        serverConnected = false
        log("Arrêt forcé de Hysteria et tun2socks...")
        
        try {
            HevTun2Socks.stop()
            log("HevTun2Socks arrêté")
        } catch (_: Exception) {}
        
        try {
            tun2socksProcess?.let { p ->
                p.inputStream?.close()
                p.errorStream?.close()
                p.outputStream?.close()
                p.destroyForcibly()
            }
        } catch (_: Exception) {}
        
        try {
            hysteriaProcess?.let { p ->
                p.inputStream?.close()
                p.errorStream?.close()
                p.outputStream?.close()
                p.destroyForcibly()
            }
        } catch (_: Exception) {}
        
        tun2socksProcess = null
        try { vpnPfd?.close() } catch (_: Exception) {}
        vpnPfd = null
        hysteriaProcess = null
        
        log("Hysteria arrêté ✅")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && serverConnected
}
