package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.profiles.XrayDnsProfile
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.*
import java.net.*

class XrayDnsEngine(
    private val context: Context,
    private val profile: XrayDnsProfile,
    private val vpnService: VpnService? = null,
    private val instanceId: Int = 0
) {
    companion object {
        const val TAG = "XrayDnsEngine"
        fun getFreePort(): Int = try { ServerSocket(0).use { it.localPort } } catch (_: Exception) { 10808 }
    }

    private var _socksPort: Int = 0
    private val LOCAL_SOCKS_PORT: Int get() {
        if (_socksPort == 0) _socksPort = getFreePort()
        return _socksPort
    }

    private var _dnsttPort: Int = 0
    private val DNSTT_PORT: Int get() {
        if (_dnsttPort == 0) _dnsttPort = getFreePort()
        return _dnsttPort
    }

    private var running = false
    private var xrayProcess: Process? = null
    private var dnsttProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getSocksPort(): Int = _socksPort

    private val cleanPublicKey: String get() = profile.publicKey
        .trim()
        .replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "")
        .replace("(", "").replace(")", "").replace("'", "").replace("\"", "")
        .replace("`", "").replace(";", "").replace("&", "").replace("|", "")
        .replace("$", "")

    suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Demarrage XrayDnsEngine (Mode 5)...")
        withContext(Dispatchers.IO) {
            if (profile.nameserver.isBlank()) throw Exception("Nameserver manquant")
            if (cleanPublicKey.isBlank()) throw Exception("Public Key manquante")

            startDnsttProcess()

            var dnsttReady = false
            var waited = 0
            while (waited < 8000) {
                delay(200); waited += 200
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress("127.0.0.1", DNSTT_PORT), 100)
                    s.close(); dnsttReady = true
                    KighmuLogger.info(TAG, "dnstt pret en ${waited}ms sur port ${DNSTT_PORT}")
                    break
                } catch (_: Exception) {}
            }
            if (!dnsttReady) throw Exception("dnstt n'a pas demarre dans les temps")

            val configFile = writeXrayConfig()
            val binary = extractXrayBinary() ?: throw Exception("libxray.so introuvable")
            startXrayProcess(binary, configFile)

            var xrayReady = false
            repeat(25) {
                if (!xrayReady) {
                    delay(200)
                    try {
                        val s = Socket()
                        s.connect(InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT), 200)
                        s.close(); xrayReady = true
                    } catch (_: Exception) {}
                }
            }
            if (!xrayReady) throw Exception("Xray DNS n'a pas demarre dans les temps")
        }
        KighmuLogger.info(TAG, "XrayDnsEngine pret sur port ${LOCAL_SOCKS_PORT} ✅")
        return LOCAL_SOCKS_PORT
    }

    private fun startDnsttProcess() {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val bin = File(nativeDir, "libdnstt.so")
        if (!bin.exists()) throw Exception("libdnstt.so introuvable dans ${nativeDir}")

        val cmd = listOf(
            bin.absolutePath,
            "-udp", "${profile.dnsServer}:${profile.dnsPort}",
            "-pubkey", cleanPublicKey,
            profile.nameserver,
            "127.0.0.1:${DNSTT_PORT}"
        )

        KighmuLogger.info(TAG, "dnstt cmd: ${cmd.joinToString(separator = " ")}")
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["HOME"]   = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.directory(context.filesDir)
        val process = pb.start()
        dnsttProcess = process

        Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val skip = line.contains("begin stream") || line.contains("opening stream") ||
                        line.contains("closing stream") || line.contains("keepalive") ||
                        line.contains("retransmit") || line.contains("EOF") ||
                        line.contains("connection reset") || line.contains("broken pipe") ||
                        line.contains("accepted") || line.contains("copy stream") ||
                        line.contains("end stream") || line.contains("session") || line.contains("MTU") || line.contains("fingerprint")
                    if (running && !skip) KighmuLogger.info(TAG, "dnstt: ${line}")
                }
            } catch (e: Exception) {
                if (running) KighmuLogger.error(TAG, "dnstt stdout: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()

        Thread.sleep(500)
        try {
            val exitVal = process.exitValue()
            throw Exception("dnstt crashed (exit=${exitVal})")
        } catch (_: IllegalThreadStateException) {}
    }

    private fun writeXrayConfig(): File {
        var jsonConfig = run {
            val candidate = profile.xrayJsonConfig.trim()
            if (candidate.startsWith("{")) candidate
            else buildXrayConfigFromProfile()
        }

        try {
            val obj = org.json.JSONObject(jsonConfig)
            val inbounds = obj.optJSONArray("inbounds")
            val cleanedInbounds = org.json.JSONArray()
            var hasSocks = false

            if (inbounds != null) {
                for (i in 0 until inbounds.length()) {
                    val inbound = inbounds.getJSONObject(i)
                    val proto = inbound.optString("protocol")
                    val listenAddr = inbound.optString("listen", "127.0.0.1")
                    val inPort = inbound.optString("port", "0").toIntOrNull() ?: 0
                    if (listenAddr == "0.0.0.0") inbound.put("listen", "127.0.0.1")
                    if (proto == "socks") {
                        inbound.put("port", LOCAL_SOCKS_PORT); inbound.put("listen", "127.0.0.1")
                        hasSocks = true
                    }
                    cleanedInbounds.put(inbound)
                }
            }
            if (!hasSocks) {
                val socks = org.json.JSONObject()
                socks.put("listen", "127.0.0.1")
                socks.put("port", LOCAL_SOCKS_PORT)
                socks.put("protocol", "socks")
                socks.put("settings", org.json.JSONObject().put("udp", true))
                cleanedInbounds.put(socks)
            }
            obj.put("inbounds", cleanedInbounds)

            val outbounds = obj.optJSONArray("outbounds")
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.getJSONObject(i)
                    val proto = ob.optString("protocol")
                    val tag = ob.optString("tag", "")
                    if (proto != "freedom" && proto != "blackhole" && proto != "socks" && tag != "direct") {
                        val settings = ob.optJSONObject("settings")
                        val vnext = settings?.optJSONArray("vnext")
                        if (vnext != null && vnext.length() > 0) {
                            val server = vnext.getJSONObject(0)
                            server.put("address", "127.0.0.1")
                            server.put("port", DNSTT_PORT)
                        }
                        val ss = ob.optJSONObject("streamSettings")
                        if (ss != null) {
                            ss.put("security", "none")
                            ss.remove("tlsSettings")
                            ss.remove("realitySettings")
                            ob.put("streamSettings", ss)
                        }
                    }
                }
                obj.put("outbounds", outbounds)
            }

            val routing = obj.optJSONObject("routing")
            if (routing != null) {
                val rules = routing.optJSONArray("rules")
                if (rules != null) {
                    val cleaned = org.json.JSONArray()
                    for (i in 0 until rules.length()) {
                        val rule = rules.getJSONObject(i)
                        val ip = rule.optJSONArray("ip")?.toString() ?: ""
                        val domain = rule.optJSONArray("domain")?.toString() ?: ""
                        if (!ip.contains("geoip:") && !domain.contains("geosite:")) cleaned.put(rule)
                    }
                    routing.put("rules", cleaned)
                    obj.put("routing", routing)
                }
            }
            jsonConfig = obj.toString(2)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur normalisation config: ${e.message}")
        }

        val fileName = if (instanceId == 0) "xraydns_config.json" else "xraydns_config_${instanceId}.json"
        val file = File(context.filesDir, fileName)
        file.writeText(jsonConfig)
        return file
    }

    // XrayDnsEngine: dnstt fait le tunnel vers le serveur reel.
    // Xray se connecte a dnstt en local (127.0.0.1:DNSTT_PORT) sans TLS ni Reality.
    // Le transport indique comment Xray parle a dnstt localement.
    private fun buildStreamSettings(transport: String): String {
        val net     = transport.lowercase()
        val path    = profile.wsPath.ifBlank { "/" }
        val host    = profile.wsHost.ifBlank { "127.0.0.1" }
        val grpcSvc = profile.wsPath.ifBlank { "/" }

        return when (net) {
            "ws" ->
                """{"network":"ws","security":"none","wsSettings":{"path":"$path","headers":{"Host":"$host"}}}"""
            "grpc" ->
                """{"network":"grpc","security":"none","grpcSettings":{"serviceName":"$grpcSvc","multiMode":false}}"""
            "xhttp", "splithttp" ->
                """{"network":"xhttp","security":"none","xhttpSettings":{"path":"$path","host":"$host","mode":"auto"}}"""
            "h2", "http" ->
                """{"network":"h2","security":"none","httpSettings":{"path":"$path","host":["$host"]}}"""
            "httpupgrade" ->
                """{"network":"httpupgrade","security":"none","httpupgradeSettings":{"path":"$path","host":"$host"}}"""
            "kcp", "mkcp" ->
                """{"network":"kcp","security":"none","kcpSettings":{"mtu":1350,"tti":20,"uplinkCapacity":5,"downlinkCapacity":20,"congestion":false,"readBufferSize":2,"writeBufferSize":2,"header":{"type":"none"}}}"""
            else ->
                """{"network":"tcp","security":"none"}"""
        }
    }

    private fun buildXrayConfigFromProfile(): String {
        val proto     = profile.protocol
        val uuid      = profile.uuid
        val dnsPort   = DNSTT_PORT
        val socksPort = LOCAL_SOCKS_PORT
        val stream    = buildStreamSettings(profile.transport)

        val outbound = when (proto) {
            "vmess" -> """{"protocol":"vmess","settings":{"vnext":[{"address":"127.0.0.1","port":$dnsPort,"users":[{"id":"$uuid","alterId":0,"security":"auto"}]}]},"streamSettings":$stream}"""
            "trojan" -> """{"protocol":"trojan","settings":{"servers":[{"address":"127.0.0.1","port":$dnsPort,"password":"$uuid"}]},"streamSettings":$stream}"""
            else -> """{"protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":$dnsPort,"users":[{"id":"$uuid","encryption":"none"}]}]},"streamSettings":$stream}"""
        }
        return """{"log":{"loglevel":"warning"},"inbounds":[{"port":$socksPort,"listen":"127.0.0.1","protocol":"socks","settings":{"udp":true}}],"outbounds":[$outbound,{"protocol":"freedom","tag":"direct"}],"routing":{"rules":[]}}"""
    }

    private fun extractXrayBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libxray = File(nativeDir, "libxray.so")
        if (libxray.exists()) { libxray.setExecutable(true); return libxray }
        val abi = android.os.Build.SUPPORTED_ABIS[0]
        val target = File(context.filesDir, "xray_dns")
        return try {
            context.assets.open("xray/${abi}/xray").use { inp ->
                target.outputStream().use { out -> inp.copyTo(out) }
            }
            target.setExecutable(true)
            target
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Xray binary introuvable: ${e.message}")
            null
        }
    }

    private fun startXrayProcess(binary: File, configFile: File) {
        val cmd = arrayOf(binary.absolutePath, "run", "-c", configFile.absolutePath)
        xrayProcess = Runtime.getRuntime().exec(cmd)
        val proc = xrayProcess!!

        fun processLine(line: String) {
            if (line.isBlank() || line.length > 500) return
            val lower = line.lowercase()
            when {
                lower.contains("started") && lower.contains("xray") ->
                    KighmuLogger.info(TAG, "Xray DNS demarre ✅")
                (lower.contains("error") || lower.contains("fatal"))
                && !lower.contains("warning") && !lower.contains("deprecated")
                && !lower.contains("connection reset") && !lower.contains("broken pipe")
                && !lower.contains("EOF") && !lower.contains("use of closed")
                && !lower.contains("failed to dial") ->
                    KighmuLogger.error(TAG, "XrayDns: ${line.take(150)}")
            }
        }

        Thread { try { proc.errorStream.bufferedReader().forEachLine { processLine(it) } } catch (_: Exception) {} }.also { it.isDaemon = true }.start()
        Thread { try { proc.inputStream.bufferedReader().forEachLine { processLine(it) } } catch (_: Exception) {} }.also { it.isDaemon = true }.start()
    }

    fun startTun2Socks(fd: Int) {
        engineScope.launch(Dispatchers.IO) {
            try {
                HevTun2Socks.init()
                if (HevTun2Socks.isAvailable) {
                    val t = Thread {
                        try {
                            vpnService?.let { HevTun2Socks.start(context, fd, LOCAL_SOCKS_PORT, it, 1400) }
                            KighmuLogger.info(TAG, "HevTun2Socks demarre ✅")
                        } catch (e: Exception) {
                            KighmuLogger.error(TAG, "HevTun2Socks erreur: ${e.message}")
                        }
                    }
                    t.isDaemon = true; t.start()
                    return@launch
                }
                if (Tun2Socks.isAvailable) {
                    val t = Thread {
                        Tun2Socks.runTun2Socks(
                            fd, 1500, "10.0.0.2", "255.255.255.0",
                            "127.0.0.1:${LOCAL_SOCKS_PORT}", "127.0.0.1:7300", false, 3
                        )
                    }
                    t.isDaemon = true
                    t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
                    t.start()
                    return@launch
                }
                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                val relay = com.kighmu.vpn.vpn.Tun2SocksRelay(pfd.fileDescriptor, "127.0.0.1", LOCAL_SOCKS_PORT)
                relay.start()
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "Erreur startTun2Socks: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        KighmuLogger.info(TAG, "Arret XrayDnsEngine...")
        try {
            xrayProcess?.let { p ->
                p.inputStream?.close(); p.errorStream?.close()
                p.outputStream?.close(); p.destroyForcibly()
            }
        } catch (_: Exception) {}
        xrayProcess = null
        try { dnsttProcess?.destroyForcibly(); dnsttProcess?.destroy() } catch (_: Exception) {}
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k ${_dnsttPort}/tcp 2>/dev/null")) } catch (_: Exception) {}
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k ${_dnsttPort}/udp 2>/dev/null")) } catch (_: Exception) {}
        dnsttProcess = null
        _socksPort = 0
        _dnsttPort = 0
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        engineScope.cancel()
        KighmuLogger.info(TAG, "XrayDnsEngine arrete ✅")
    }

    fun isRunning() = running
}
