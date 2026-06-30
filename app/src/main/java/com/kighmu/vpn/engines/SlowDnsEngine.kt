package com.kighmu.vpn.engines

import android.content.Context
import com.trilead.ssh2.Connection
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.File

class SlowDnsEngine(
    private val config: KighmuConfig,
    val context: Context,
    private val vpnService: android.net.VpnService? = null,
    private val profileIndex: Int = 0
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val BASE_SOCKS_PORT = 10800

        // Pour compatibilité


        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_PREFIX = "24"
        const val MTU = 1500
    }

    private var _socksPort: Int = 0
    fun getSocksPort(): Int? = if (_socksPort > 0) _socksPort else null
    private val socksPort: Int get() {
        if (_socksPort == 0) {
            _socksPort = findFreePort(10800 + profileIndex)
        }
        return _socksPort
    }
    private var _dnsttPort: Int = 0
    private val dnsttPort: Int get() {
        if (_dnsttPort == 0) {
            // Décalage plus important pour éviter les collisions entre profils
            _dnsttPort = findFreePort(7000 + (profileIndex * 10))
        }
        return _dnsttPort
    }
    private fun isPortFree(port: Int): Boolean = try {
        java.net.ServerSocket(port).use { true }
    } catch (_: Exception) { false }
    private fun findFreePort(preferred: Int): Int {
        for (p in preferred..preferred+20) {
            if (isPortFree(p)) return p
        }
        return java.net.ServerSocket(0).use { it.localPort }
    }
    private var running = false
    @Volatile private var sshAlive = false
    @Volatile var isDegraded = false
    private var sshConnection: Connection? = null
    private var dnsttProcess: Process? = null
    private var relayPfd: android.os.ParcelFileDescriptor? = null
    private var relayInstance: com.kighmu.vpn.vpn.Tun2SocksRelay? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dns get() = config.slowDns
    private val cleanPublicKey get() = config.slowDns.publicKey
        .trim()
        .replace(" ", "")
        .replace("\n", "")
        .replace("\r", "")
        .replace("\t", "")
        .replace("(", "")
        .replace(")", "")
        .replace("'", "")
        .replace("\"", "")
        .replace("`", "")
        .replace(";", "")
        .replace("&", "")
        .replace("|", "")
        .replace("$", "")
    // Fix: lire directement depuis config.slowDns (rempli par buildConfig)
    private val sshHostVal: String get() = config.slowDns.sshHost.substringBefore(":")
    private val sshPortVal: Int    get() = config.slowDns.sshPort
    private val sshUserVal: String get() = config.slowDns.sshUser
    private val sshPassVal: String get() = config.slowDns.sshPass
    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
                        
        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (cleanPublicKey.isBlank()) throw Exception("Public Key manquante")

        // Phase 1 : démarrer dnstt seulement si pas déjà vivant
        if (dnsttProcess == null || dnsttProcess?.isAlive == false) {
            val dnsttBin = extractDnsttBinary()
            startDnsttProcess(dnsttBin)

            // Attendre que dnstt soit prêt (max 8s, check toutes les 200ms)
                        var waited = 0
            while (waited < 8000) {
                delay(200)
                waited += 200
                try {
                    val sock = java.net.Socket()
                    sock.connect(java.net.InetSocketAddress("127.0.0.1", dnsttPort), 100)
                    sock.close()
                    KighmuLogger.info(TAG, "dnstt prêt en ${waited}ms")
                    break
                } catch (_: Exception) {}
            }
        } else {
                    }

        // Phase 2 : SSH uniquement (rapide, retry possible sans relancer dnstt)
        startSsh()
        
        _socksPort
    }

    private var tun2socksProcess: Process? = null

    fun startTun2SocksOnPort(fd: Int, port: Int) {
        startTun2SocksInternal(fd, port)
    }

    override fun startTun2Socks(fd: Int) {
        startTun2SocksInternal(fd, socksPort)
    }

    private fun startTun2SocksInternal(fd: Int, targetPort: Int) {
                engineScope.launch(Dispatchers.IO) {
            try {
                // Forcer l'initialisation du JNI si nécessaire
                try { com.kighmu.vpn.engines.HevTun2Socks.init() } catch (_: Exception) {}

                // 1. Priorité 1 : HevTun2Socks (UDP natif, MTU 8500, pas de udpgw)
                if (com.kighmu.vpn.engines.HevTun2Socks.isAvailable && vpnService != null) {
                                        val t = Thread {
                        try {
                            com.kighmu.vpn.engines.HevTun2Socks.start(context, fd, targetPort, vpnService, 8500)
                                                    } catch (e: Exception) {
                            KighmuLogger.error(TAG, "Erreur HevTun2Socks: ${e.message}")
                        }
                    }
                    t.isDaemon = true
                    t.start()
                                        return@launch
                }

                // 2. Fallback : Tun2Socks JNI (UDP limité, requiert udpgw:7300)
                if (Tun2Socks.isAvailable) {
                    KighmuLogger.warning(TAG, "HevTun2Socks indisponible - fallback Tun2Socks JNI (available=${Tun2Socks.isAvailable})")
                    val t = Thread {
                        val result = Tun2Socks.runTun2Socks(
                            fd, MTU, "10.0.0.2", "255.255.255.0",
                            "127.0.0.1:$targetPort", "127.0.0.1:7300",
                            false, 3
                        )
                                            }
                    t.isDaemon = true
                    t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
                    t.start()
                                        return@launch
                }

                // 3. Dernier recours : Relay Kotlin
                KighmuLogger.warning(TAG, "HevTun2Socks=false Tun2Socks=false → Relay Kotlin port=$targetPort ⚠️")
                relayPfd?.close()
                relayPfd = android.os.ParcelFileDescriptor.fromFd(fd)
                relayInstance = com.kighmu.vpn.vpn.Tun2SocksRelay(relayPfd!!.fileDescriptor, "127.0.0.1", targetPort)
                relayInstance!!.start()
                            } catch (e: Exception) {
                KighmuLogger.error(TAG, "tun2socks error: ${e.message}")
            }
        }
    }

    private fun extractDnsttBinary(): File {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binFile = File(nativeDir, "libdnstt.so")
                if (!binFile.exists()) throw Exception("libdnstt.so introuvable dans $nativeDir")
        return binFile
    }

    // Démarrer seulement dnstt sans SSH - pour XraySlowDnsEngine
    suspend fun startDnsttOnly(): Int {
        running = true
        val dns = config.slowDns
        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (cleanPublicKey.isBlank()) throw Exception("Public Key manquante")
        val dnsttBin = extractDnsttBinary()
        startDnsttProcess(dnsttBin)
        // Attendre que dnstt ouvre le listener TCP
                delay(1000)
        // Vérifier que dnstt est toujours vivant
        if (dnsttProcess?.isAlive == false) throw Exception("dnstt mort au démarrage")
                return dnsttPort
    }


    private fun startDnsttProcess(bin: File) {
        // DNSTT ne supporte pas le flag -mtu en ligne de commande. 
        // La stabilité sera gérée par les buffers Xray et Tun2SocksRelay.
        val cmd = listOf(
            bin.absolutePath,
            "-udp", "${dns.dnsServer}:${dns.dnsPort}",
            "-pubkey", cleanPublicKey,
            dns.nameserver,
            "127.0.0.1:$dnsttPort"
        )


        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["HOME"]   = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.directory(context.filesDir)

        val process = pb.start()
        dnsttProcess = process

        Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val lower = line.lowercase()
                    val skip = lower.contains("begin stream") ||
                        lower.contains("opening stream") ||
                        lower.contains("handle: session") ||
                        lower.contains("closing stream") ||
                        lower.contains("stream timeout") ||
                        lower.contains("retransmit") ||
                        lower.contains("recv window") ||
                        lower.contains("send window") ||
                        lower.contains("keepalive") ||
                        lower.contains("end stream") ||
                        lower.contains("network is unreachable") ||
                        lower.contains("sendto:") ||
                        lower.contains("write udp") ||
                        lower.contains("read udp") ||
                        lower.contains("accepted") ||
                        lower.contains("connection reset") ||
                        lower.contains("broken pipe") ||
                        lower.contains("copy stream") ||
                        lower.contains("copy local") ||
                        lower.contains("eof") ||
                        lower.contains("i/o timeout") ||
                        lower.contains("use of closed") ||
                        lower.contains("poll") ||
                        lower.contains("debug") ||
                        lower.contains("trace") ||
                        line.isBlank()
                    if (running && !skip) KighmuLogger.info(TAG, "dnstt: $line")
                }
            } catch (e: Exception) {
                if (running) KighmuLogger.warning(TAG, "dnstt stdout: ${e.message}")
            }
        }.start()

        Thread.sleep(500)
        try {
            val exitVal = process.exitValue()
            throw Exception("dnstt crashed (exit=$exitVal)")
        } catch (_: IllegalThreadStateException) {
                    }
    }

    private fun startSsh() {
        // dnstt expose le flux SSH brut sur 127.0.0.1:7000
        // trilead se connecte directement comme si c'etait le vrai serveur SSH
        
        // Bridge banniere : lit SSH-2.0-xxx puis relaie tout a Trilead
        val bannerProxyPort = run {
            val ss = java.net.ServerSocket(0); ss.reuseAddress = true; val p = ss.localPort; ss.close(); p
        }
        val bannerLatch = java.util.concurrent.CountDownLatch(1)
        var capturedBanner = ""
        Thread {
            try {
                val proxyServer = java.net.ServerSocket(bannerProxyPort)
                bannerLatch.countDown()
                val trileadSock = proxyServer.accept()
                proxyServer.close()
                // Protéger le socket trilead contre le tunnel VPN
                try { vpnService?.protect(trileadSock) } catch (_: Exception) {}
                val realSock = java.net.Socket("127.0.0.1", dnsttPort)
                // Protéger le socket dnstt contre le tunnel VPN
                try { vpnService?.protect(realSock) } catch (_: Exception) {}
                realSock.soTimeout = 0 // pas de timeout: bridge doit vivre tant que SSH vit
                val realIn = realSock.getInputStream()
                // Lire la ligne SSH-2.0-xxx (version)
                val versionBytes = StringBuilder()
                var b: Int
                while (realIn.read().also { b = it } != -1) {
                    versionBytes.append(b.toChar())
                    if (versionBytes.endsWith("\n")) break
                }
                capturedBanner = versionBytes.toString().trim()
                val trileadOut = trileadSock.getOutputStream()
                trileadOut.write(versionBytes.toString().toByteArray())
                trileadOut.flush()

                try { realSock.tcpNoDelay = true } catch (_: Exception) {}
                try { trileadSock.tcpNoDelay = true } catch (_: Exception) {}
                val t1 = Thread { pipeBanner(realIn, trileadSock.getOutputStream()) }
                val t2 = Thread { pipeBanner(trileadSock.getInputStream(), realSock.getOutputStream()) }
                t1.isDaemon = true; t2.isDaemon = true
                t1.start(); t2.start()
            } catch (e: Exception) {
                bannerLatch.countDown()
                KighmuLogger.warning(TAG, "BannerProxy error: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
        bannerLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        val conn = Connection("127.0.0.1", bannerProxyPort)

        // ── Compression zlib : réduit volume DNS de 40-60% ─────────────────
        // conn.setCompression(true) // désactivé: surcharge CPU sous charge intensive

        // ── Timeouts réduits : détection rapide des pannes ─────────────────
        conn.connect(null, 5000, 6000)
        if (capturedBanner.isNotEmpty()) KighmuLogger.info(TAG, capturedBanner)
        
        val authenticated = conn.authenticateWithPassword(sshUserVal, sshPassVal)
        if (!authenticated) throw Exception("SSH auth echoue pour ${sshUserVal}")
                // ── Lire banner SSH via reflection (AuthenticationManager.banner) ────
        try {
            val amField = conn.javaClass.getDeclaredField("am")
            amField.isAccessible = true
            val am = amField.get(conn)
            if (am != null) {
                val bannerField = am.javaClass.getDeclaredField("banner")
                bannerField.isAccessible = true
                val banner = bannerField.get(am) as? String
                if (!banner.isNullOrBlank()) {
                    banner.lines()
                        .map { it.trimEnd() }
                        .filter { it.isNotEmpty() }
                        .forEach { line -> KighmuLogger.info(TAG, line) }
                }
            }
        } catch (_: Exception) {}

        // ── Capture banner/MOTD post-auth ────────────────────────────────────
        try {
            val sess = conn.openSession()
            sess.startShell()
            val motdBuilder = StringBuilder()
            val motdIn = sess.stdout
            val deadline = System.currentTimeMillis() + 3000L
            val promptRegex = Regex("""[\$#>]\s*$""")
            val ansiRegex = Regex("""\[[0-9;]*[A-Za-z]|\][^]*|\(B|=|
""")
            while (System.currentTimeMillis() < deadline) {
                if (motdIn.available() > 0) {
                    val buf = ByteArray(motdIn.available())
                    val n = motdIn.read(buf)
                    if (n > 0) {
                        val chunk = String(buf, 0, n, Charsets.UTF_8)
                        motdBuilder.append(chunk)
                        // Stopper dès qu'on détecte un prompt shell
                        val clean = ansiRegex.replace(motdBuilder.toString(), "")
                        if (promptRegex.containsMatchIn(clean)) break
                    }
                } else {
                    Thread.sleep(80)
                }
            }
            sess.close()
            // Nettoyer les séquences ANSI/escape et le prompt final
            val cleaned = ansiRegex.replace(motdBuilder.toString(), "")
            val motd = cleaned
                .lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty() &&
                    !promptRegex.containsMatchIn(line) &&
                    !line.startsWith("[?") &&
                    !line.contains("@") // exclure ligne prompt user@host
                }
            if (motd.isNotEmpty()) {
                motd.forEach { line -> KighmuLogger.info(TAG, line) }
            }
        } catch (_: Exception) {}

        // ── SOCKS5 proxy local port libre garanti ───────────────────────────
        // Utiliser le port déjà calculé dans socksPort getter (évite race condition)
        if (_socksPort == 0) _socksPort = findFreePort(10800 + profileIndex)
        conn.createDynamicPortForwarder(java.net.InetSocketAddress("127.0.0.1", _socksPort))
        
        // ── Keep-alive toutes les 20s avec détection de mort ─────────────────
        engineScope.launch {
            var keepAliveRunning = true
            while (running && keepAliveRunning) {
                delay(8_000)
                if (!running) { keepAliveRunning = false; continue }
                try {
                    val ok = withTimeoutOrNull(5_000) { conn.sendIgnorePacket(); true } ?: false
                    if (!ok) {
                        KighmuLogger.warning(TAG, "Tunnel SSH inactif")
                        sshAlive = false
                        keepAliveRunning = false
                    }
                } catch (e: Exception) {
                    KighmuLogger.warning(TAG, "Tunnel SSH déconnecté")
                    sshAlive = false
                    keepAliveRunning = false
                }
            }
        }

        sshConnection = conn
        sshAlive = true

        // ── Health check dnstt indépendant du SSH ─────────────────────────────
        engineScope.launch {
            var dnsttFailCount = 0
            while (running) {
                delay(15_000)
                if (!running) break
                val alive = try {
                    val s = java.net.Socket()
                    s.connect(java.net.InetSocketAddress("127.0.0.1", dnsttPort), 1000)
                    s.close()
                    true
                } catch (_: Exception) { false }
                if (!alive) {
                    dnsttFailCount++
                    KighmuLogger.warning(TAG, "dnstt instable ($dnsttFailCount/2)")
                    if (dnsttFailCount >= 2) {
                        KighmuLogger.warning(TAG, "dnstt arrêté, reconnexion...")
                        isDegraded = true
                        break
                    }
                } else {
                    dnsttFailCount = 0
                }
            }
        }
    }

    // Arrêter seulement SSH - garder dnstt vivant pour retry rapide

    private fun pipeBanner(inp: java.io.InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(65536)
        try {
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n)
                if (inp.available() == 0) out.flush()
            }
        } catch (_: Exception) {}
    }
    fun stopDnsttOnly() {
        try { dnsttProcess?.destroyForcibly(); dnsttProcess?.destroy() } catch (_: Exception) {}
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k ${dnsttPort}/tcp 2>/dev/null")) } catch (_: Exception) {}
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k ${dnsttPort}/udp 2>/dev/null")) } catch (_: Exception) {}
        dnsttProcess = null
        _dnsttPort = 0  // Force nouveau port UDP au prochain démarrage
            }

    fun stopSshOnly() {
        sshAlive = false
        try { sshConnection?.close() } catch (_: Exception) {}
        sshConnection = null
        _socksPort = 0
            }

    override suspend fun stop() {
        running = false
        sshAlive = false
        // HevTun2Socks géré globalement par MultiSlowDnsEngine
        try { if (Tun2Socks.isAvailable) Tun2Socks.terminateTun2Socks() } catch (_: Exception) {}
        try { tun2socksProcess?.destroyForcibly() } catch (_: Exception) {}
        tun2socksProcess = null
        try { sshConnection?.close() } catch (_: Exception) {}
        sshConnection = null
        try {
            dnsttProcess?.destroyForcibly()
            dnsttProcess?.destroy()
        } catch (_: Exception) {}
        
        // Nettoyage complet: TCP + UDP pour libérer dnstt sans mode avion
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill -9 \$(lsof -ti:$dnsttPort) 2>/dev/null")) } catch (_: Exception) {}
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k ${dnsttPort}/tcp 2>/dev/null")) } catch (_: Exception) {}
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k ${dnsttPort}/udp 2>/dev/null")) } catch (_: Exception) {}
        // Attendre libération noyau des sockets UDP (critique pour éviter mode avion)
        
        dnsttProcess = null
        try { relayInstance?.stop() } catch (_: Exception) {}
        relayInstance = null
        try { relayPfd?.close() } catch (_: Exception) {}
        relayPfd = null
        engineScope.cancel()
        
        // Délai de grâce optimisé (500ms) pour libérer les sockets noyau sans ralentir l'UI
        
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshAlive
}
