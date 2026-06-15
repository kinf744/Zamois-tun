package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.profiles.XrayVpnProfile
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.concurrent.LinkedBlockingQueue

class XrayVpnEngine(
    private val context: Context,
    private val profile: XrayVpnProfile,
    private val vpnService: VpnService? = null,
    private val instanceId: Int = 0
) {
    companion object {
        const val TAG = "XrayVpnEngine"
        fun getFreePort(): Int = try { ServerSocket(0).use { it.localPort } } catch (_: Exception) { 10808 }
    }

    private var _socksPort: Int = 0
    private val LOCAL_SOCKS_PORT: Int get() {
        if (_socksPort == 0) _socksPort = getFreePort()
        return _socksPort
    }

    private var running = false
    private var xrayProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getSocksPort(): Int = _socksPort

    suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Demarrage XrayVpnEngine (Mode 4)...")
        withContext(Dispatchers.IO) {
            val configFile = writeXrayConfig()
            val binary = extractXrayBinary() ?: throw Exception("libxray.so introuvable")
            startXrayProcess(binary, configFile)
            var ready = false
            repeat(25) {
                if (!ready) {
                    delay(200)
                    try {
                        val s = Socket()
                        s.connect(InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT), 200)
                        s.close()
                        ready = true
                    } catch (_: Exception) {}
                }
            }
            if (!ready) throw Exception("XrayVpnEngine: Xray n'a pas demarre dans les temps")
        }
        KighmuLogger.info(TAG, "XrayVpnEngine pret sur port ${LOCAL_SOCKS_PORT} ✅")
        return LOCAL_SOCKS_PORT
    }

    private fun writeXrayConfig(): File {
        var jsonConfig = profile.getActiveJson().ifBlank { buildXrayConfigFromProfile() }
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
                        if (inPort in 10800..10810) {
                            _socksPort = inPort
                            inbound.put("listen", "127.0.0.1")
                        } else {
                            inbound.put("port", LOCAL_SOCKS_PORT)
                            inbound.put("listen", "127.0.0.1")
                        }
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
                    val ss = ob.optJSONObject("streamSettings")
                    val tls = ss?.optJSONObject("tlsSettings")
                    if (tls != null) {
                        tls.remove("allowInsecure")
                        ss.put("tlsSettings", tls)
                        ob.put("streamSettings", ss)
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
        val fileName = if (instanceId == 0) "xrayvpn_config.json" else "xrayvpn_config_${instanceId}.json"
        val file = File(context.filesDir, fileName)
        file.writeText(jsonConfig)
        return file
    }

    private fun buildTlsPart(): String {
        if (!profile.tls && profile.publicKey.isBlank()) return ""
        return if (profile.publicKey.isNotBlank()) {
            // Reality
            val fp = profile.fingerprint.ifBlank { "chrome" }
            val sid = profile.shortId.ifBlank { "0000000000000000" }
            """"security":"reality","realitySettings":{"serverName":"${profile.sni.ifBlank{profile.serverAddress}}","fingerprint":"$fp","publicKey":"${profile.publicKey}","shortId":"$sid"}"""
        } else {
            // TLS standard
            val sni = profile.sni.ifBlank { profile.serverAddress }
            val insecure = if (profile.allowInsecure) "true" else "false"
            """"security":"tls","tlsSettings":{"serverName":"$sni","allowInsecure":$insecure,"fingerprint":"${profile.fingerprint.ifBlank{"chrome"}}"}"""
        }
    }

    private fun buildStreamSettings(transport: String): String {
        val net      = transport.lowercase()
        val tlsPart  = buildTlsPart()
        val security = when {
            tlsPart.contains("reality") -> "reality"
            tlsPart.contains("tls")     -> "tls"
            else                        -> "none"
        }
        val path    = profile.wsPath.ifBlank { "/" }
        val host    = profile.wsHost.ifBlank { profile.serverAddress }
        val grpcSvc = profile.grpcServiceName.ifBlank { profile.wsPath.ifBlank { "/" } }
        val kcpHdr  = profile.kcpHeader.ifBlank { "none" }

        val networkPart = when (net) {
            "ws" ->
                """"network":"ws","wsSettings":{"path":"$path","headers":{"Host":"$host"}}"""
            "grpc" ->
                """"network":"grpc","grpcSettings":{"serviceName":"$grpcSvc","multiMode":false}"""
            "xhttp", "splithttp" ->
                """"network":"xhttp","xhttpSettings":{"path":"$path","host":"$host","mode":"auto"}"""
            "h2", "http" ->
                """"network":"h2","httpSettings":{"path":"$path","host":["$host"]}"""
            "httpupgrade" ->
                """"network":"httpupgrade","httpupgradeSettings":{"path":"$path","host":"$host"}"""
            "kcp", "mkcp" ->
                """"network":"kcp","kcpSettings":{"mtu":1350,"tti":20,"uplinkCapacity":5,"downlinkCapacity":20,"congestion":false,"readBufferSize":2,"writeBufferSize":2,"header":{"type":"$kcpHdr"}}"""
            else ->
                """"network":"tcp","tcpSettings":{}"""
        }

        return if (tlsPart.isNotBlank()) {
            """{$networkPart,"security":"$security",${tlsPart.substringAfter('"security":"$security",')}"""
        } else {
            """{$networkPart,"security":"none"}"""
        }
    }

    private fun buildXrayConfigFromProfile(): String {
        val proto     = profile.protocol
        val uuid      = profile.uuid
        val socksPort = LOCAL_SOCKS_PORT
        val host      = profile.serverAddress
        val port      = profile.serverPort
        val stream    = buildStreamSettings(profile.transport)

        val outbound = when (proto) {
            "vmess" -> """{"protocol":"vmess","settings":{"vnext":[{"address":"$host","port":$port,"users":[{"id":"$uuid","alterId":0,"security":"auto"}]}]},"streamSettings":$stream}"""
            "trojan" -> """{"protocol":"trojan","settings":{"servers":[{"address":"$host","port":$port,"password":"$uuid"}]},"streamSettings":$stream}"""
            else -> """{"protocol":"vless","settings":{"vnext":[{"address":"$host","port":$port,"users":[{"id":"$uuid","encryption":"none"}]}]},"streamSettings":$stream}"""
        }
        return """{"log":{"loglevel":"warning"},"inbounds":[{"port":$socksPort,"listen":"127.0.0.1","protocol":"socks","settings":{"udp":true}}],"outbounds":[$outbound,{"protocol":"freedom","tag":"direct"}],"routing":{"rules":[]}}"""
    }

        private fun extractXrayBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libxray = File(nativeDir, "libxray.so")
        if (libxray.exists()) { libxray.setExecutable(true); return libxray }
        val abi = android.os.Build.SUPPORTED_ABIS[0]
        val target = File(context.filesDir, "xray_vpn")
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
                    KighmuLogger.info(TAG, "Xray VPN demarre ✅")
                (lower.contains("error") || lower.contains("fatal"))
                && !lower.contains("warning") && !lower.contains("deprecated")
                && !lower.contains("connection reset") && !lower.contains("broken pipe")
                && !lower.contains("EOF") && !lower.contains("use of closed")
                && !lower.contains("read/write on closed") && !lower.contains("failed to dial") ->
                    KighmuLogger.error(TAG, "Xray: ${line.take(150)}")
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
        try {
            xrayProcess?.let { p ->
                p.inputStream?.close(); p.errorStream?.close()
                p.outputStream?.close(); p.destroyForcibly()
            }
        } catch (_: Exception) {}
        xrayProcess = null
        _socksPort = 0
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        engineScope.cancel()
        KighmuLogger.info(TAG, "XrayVpnEngine arrete ✅")
    }

    fun isRunning() = running
}
