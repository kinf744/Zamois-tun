package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.ProfileRepository
import com.kighmu.vpn.profiles.SlowDnsProfile
import com.kighmu.vpn.utils.KighmuLogger
import com.kighmu.vpn.engines.SocksBalancer
import kotlinx.coroutines.*

class MultiSlowDnsEngine(
    private val baseConfig: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "MultiSlowDnsEngine"
        const val SESSION_TIMEOUT_MS = 15000L // 15s: kex SSH via dnstt peut prendre 8-12s
    }

    private val engines = mutableListOf<SlowDnsEngine>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activePort = 10800
    private var tunFd: Int = -1
    private var socksBalancer: SocksBalancer? = null
    @Volatile private var replacingCount = 0

    override suspend fun start(): Int {
        val repo = ProfileRepository(context)
        val selected = repo.getSelected()

        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil sélectionné → config par défaut")
            val engine = SlowDnsEngine(baseConfig, context, vpnService, 0)
            synchronized(engines) { engines.add(engine) }
            return engine.start()
        }

        // Nettoyer les engines précédents avant de relancer (évite "address already in use")
        KighmuLogger.info(TAG, "Nettoyage engines précédents (${engines.size})...")
        synchronized(engines) {
            engines.forEach { e ->
                // Utilisation de runBlocking pour forcer l'arrêt synchrone avant de continuer
                try { runBlocking { e.stop() } } catch (_: Exception) {}
            }
            engines.clear()
        }
        
        // Principe du build #736 : Nettoyage préventif agressif des ports SOCKS et DNSTT
        try {
            val portsToKill = (10800..10810).joinToString(" ") { "$it/tcp" } + " " +
                             (7000..7010).joinToString(" ") { "$it/tcp" } + " 10900/tcp"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k $portsToKill")).waitFor()
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f dnstt")).waitFor()
        } catch (_: Exception) {}

        socksBalancer?.stop()
        socksBalancer = null
        
        // Attendre la libération des ressources noyau (500ms)
        delay(500)

        // Calcul du total de flux (profils × tunnelCount chacun)
        val totalFlux = selected.sumOf { it.tunnelCount.coerceIn(1, 4) }
        KighmuLogger.info(TAG, "=== STEP 1: Connexion séquentielle $totalFlux flux (${selected.size} profil(s)) ===")

        // STEP 1 : Connexion séquentielle avec retry agressif par session
        val results = mutableListOf<Pair<Int, Int>>()
        var globalIdx = 0
        selected.forEach { profile ->
            val count = profile.tunnelCount.coerceIn(1, 4)
            KighmuLogger.info(TAG, "Profil '${profile.profileName}' → $count flux parallèles")
            repeat(count) { fluxIdx ->
                val sessionLabel = "${profile.profileName}[${fluxIdx+1}/$count]"
                KighmuLogger.info(TAG, "Session[${globalIdx+1}/$totalFlux] démarrage: $sessionLabel")
                // Retry agressif : nouvel engine à chaque tentative (ports propres)
                // La session DOIT se connecter avant de passer à la suivante
                val MAX_RETRIES = 30
                val RETRY_DELAY_MS = 300L
                var port = -1
                var attempt = 0
                var activeEngine = SlowDnsEngine(buildConfig(profile), context, vpnService, globalIdx)
                synchronized(engines) { engines.add(activeEngine) }
                while (attempt < MAX_RETRIES && port <= 0) {
                    attempt++
                    if (attempt > 1) {
                        KighmuLogger.warning(TAG, "Session[${globalIdx+1}] retry $attempt/$MAX_RETRIES dans ${RETRY_DELAY_MS}ms...")
                        // Tuer seulement SSH, garder dnstt vivant pour retry rapide
                        try { activeEngine.stopSshOnly() } catch (_: Exception) {}
                        delay(RETRY_DELAY_MS)
                    }
                    port = try {
                        KighmuLogger.info(TAG, "Session[${globalIdx+1}] tentative $attempt/$MAX_RETRIES: $sessionLabel")
                        withTimeoutOrNull(SESSION_TIMEOUT_MS) { activeEngine.start() } ?: -1
                    } catch (e: Exception) {
                        KighmuLogger.error(TAG, "Session[${globalIdx+1}] tentative $attempt FAILED: ${e.message}")
                        -1
                    }
                }

                if (port > 0) {
                    KighmuLogger.info(TAG, "Session[${globalIdx+1}] CONNECTÉE ✓ port=$port (tentative $attempt) flux=$sessionLabel")
                } else {
                    KighmuLogger.error(TAG, "Session[${globalIdx+1}] ABANDON après $MAX_RETRIES tentatives ✗ flux=$sessionLabel")
                }
                results.add(Pair(globalIdx, port))
                globalIdx++
            }
        }

        // STEP 2 : Résultats
                val successPorts = results.filter { it.second > 0 }.map { it.second }
        val failedCount = results.count { it.second <= 0 }

                if (failedCount > 0) {
            KighmuLogger.warning(TAG, "$failedCount session(s) ont échoué")
        }

        if (successPorts.isEmpty()) {
            throw Exception("Aucune session SlowDNS connectée sur ${selected.size} tentatives")
        }

        // STEP 3 : Démarrer le balancer sur tous les ports SOCKS connectés
        val connectedPorts = successPorts.ifEmpty { listOf(SlowDnsEngine.BASE_SOCKS_PORT) }

        KighmuLogger.info(TAG, "Ports SOCKS actifs: $connectedPorts")

        // ── DIAGNOSTIC: Vérifier chaque port SOCKS avant de démarrer le balancer ──
                connectedPorts.forEach { port ->
            try {
                val sock = java.net.Socket()
                sock.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
                // Test handshake SOCKS5
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()
                out.write(byteArrayOf(5, 1, 0)) // SOCKS5 hello
                out.flush()
                val buf = ByteArray(2)
                val read = inp.read(buf)
                sock.close()
                if (read == 2 && buf[0] == 5.toByte()) {
                    KighmuLogger.info(TAG, "Port $port: SOCKS5 OK ✅ (réponse: ${buf[0]},${buf[1]})")
                } else {
                    KighmuLogger.warning(TAG, "Port $port: TCP OK mais SOCKS5 invalide ⚠️ (lu=$read)")
                }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "Port $port: INACCESSIBLE ❌ (${e.message})")
            }
        }

        // ── DIAGNOSTIC: Interface réseau active ──
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            KighmuLogger.warning(TAG, "Interfaces réseau: ${e.message}")
        }

        // ── DIAGNOSTIC: HevTun2Socks disponibilité ──
        KighmuLogger.info(TAG, "HevTun2Socks disponible: ${HevTun2Socks.isAvailable}")
        KighmuLogger.info(TAG, "VpnService null: ${vpnService == null}")

        val balancer = SocksBalancer(connectedPorts, vpnService, maxBytesPerSec = 358400L)
        balancer.start()
        socksBalancer = balancer
        activePort = SocksBalancer.BALANCER_PORT

        // ── DIAGNOSTIC: Vérifier que le balancer répond ──
        delay(200)
        try {
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress("127.0.0.1", activePort), 1000)
                        sock.close()
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Balancer port $activePort: INACCESSIBLE ❌ (${e.message})")
        }

        KighmuLogger.info(TAG, "VPN prêt: ${successPorts.size} tunnels actifs port=$activePort")

        // Surveiller les sessions en background
        monitorSessions(selected)

        return activePort
    }

    private fun monitorSessions(profiles: List<SlowDnsProfile>) {
        scope.launch {
            while (isActive) {
                delay(5000)
                val alive = engines.count { it.isRunning() }
                val total = engines.size
                // Warm replacement: démarrer le nouveau tunnel AVANT de tuer l'ancien
                engines.forEachIndexed { idx, engine ->
                    val degraded = engine.isDegraded && isActive
                    val dead = !engine.isRunning() && isActive
                    if ((dead || degraded) && isActive) {
                        if (degraded && !dead) {
                            KighmuLogger.warning(TAG, "Session[$idx] dégradée - reconnexion préventive...")
                        } else {
                            KighmuLogger.warning(TAG, "Session[$idx] morte - warm replacement...")
                        }
                        scope.launch {
                            replacingCount++
                            try {
                                val profile = if (idx < profiles.size) profiles[idx] else return@launch
                                // 1. Créer et démarrer le nouveau tunnel d'abord
                                val newEngine = SlowDnsEngine(buildConfig(profile), context, vpnService, idx)
                                val port = withTimeoutOrNull(SESSION_TIMEOUT_MS * 5) { newEngine.start() } ?: -1
                                if (port > 0) {
                                    // 2. Nouveau tunnel prêt → basculer dans le balancer immédiatement
                                    synchronized(engines) { engines[idx] = newEngine }
                                    val alivePorts = synchronized(engines) {
                                        engines.filter { it.isRunning() }.mapNotNull { it.getSocksPort() }
                                    }
                                    if (alivePorts.isNotEmpty()) socksBalancer?.updatePorts(alivePorts)
                                    KighmuLogger.info(TAG, "Session[$idx] warm replacement OK port=$port ✓")
                                    // 3. Tuer l'ancien tunnel seulement après bascule
                                    engine.isDegraded = false
                                    try { engine.stop() } catch (_: Exception) {}
                                } else {
                                    // Nouveau tunnel échoué → garder l'ancien si encore vivant
                                    KighmuLogger.error(TAG, "Session[$idx] warm replacement échoué - conservation ancien tunnel")
                                    try { newEngine.stop() } catch (_: Exception) {}
                                    // Retry: forcer libération UDP pour éviter blocage sans mode avion
                                    try { engine.stop() } catch (_: Exception) {}
                                }
                            } catch (e: Exception) {
                                KighmuLogger.error(TAG, "Session[$idx] erreur warm replacement: ${e.message}")
                            } finally {
                                replacingCount--
                            }
                        }
                    }
                }
                // Si toutes les sessions sont mortes → redémarrage complet sans mode avion
                if (alive == 0 && total > 0 && replacingCount == 0) {
                    KighmuLogger.error(TAG, "Toutes les sessions tombées - redémarrage complet...")
                    // Extraire la liste hors du synchronized pour éviter suspend dans section critique
                    val toStop = synchronized(engines) { engines.toList().also { engines.clear() } }
                    toStop.forEach { e -> try { e.stop() } catch (_: Exception) {} }
                    delay(1000) // laisser le noyau libérer les sockets UDP
                    try { start() } catch (e: Exception) {
                        KighmuLogger.error(TAG, "Echec redémarrage complet: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    override fun startTun2Socks(fd: Int) {
        tunFd = fd
        // Protéger le descripteur de fichier au niveau orchestrateur
        try {
            vpnService?.protect(fd)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur protection FD: ${e.message}")
        }
        
        // Démarrer tun2socks directement sur le port du balancer
        // Le balancer distribue le trafic sur tous les tunnels actifs
        val balancerPort = SocksBalancer.BALANCER_PORT
        val tunnelCount = engines.count { it.isRunning() }
                try {
            try { HevTun2Socks.stop() } catch (_: Exception) {}
            try { HevTun2Socks.init() } catch (_: Exception) {}
            if (HevTun2Socks.isAvailable && vpnService != null) {
                val activePorts = engines.filter { it.isRunning() }.mapNotNull { it.getSocksPort() }
                                Thread {
                    try {
                        HevTun2Socks.start(context, fd, balancerPort, vpnService, 8500)
                        KighmuLogger.info(TAG, "HevTun2Socks démarré ✅ port=$balancerPort")
                    } catch (e: Exception) {
                        KighmuLogger.error(TAG, "HevTun2Socks erreur: ${e.message}")
                    }
                }.also { it.isDaemon = true }.start()
            } else {
                // Fallback: HevTun2Socks non disponible - utiliser Tun2Socks JNI ou Relay Kotlin
                KighmuLogger.warning(TAG, "HevTun2Socks non disponible (isAvailable=false) → fallback engine")
                val firstEngine = engines.firstOrNull { it.isRunning() } ?: engines.firstOrNull()
                if (firstEngine != null) {
                    KighmuLogger.warning(TAG, "Fallback → startTun2SocksOnPort fd=$fd port=$balancerPort")
                    firstEngine.startTun2SocksOnPort(fd, balancerPort)
                } else {
                    KighmuLogger.error(TAG, "Aucune session disponible pour tun2socks!")
                }
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "startTun2Socks erreur: ${e.message}")
        }
    }

    override suspend fun stop() {
        KighmuLogger.info(TAG, "Arrêt de ${engines.size} session(s)...")
        
        // 1. Arrêter le SocksBalancer d'abord
        try {
            socksBalancer?.stop()
            socksBalancer = null
        } catch (_: Exception) {}

        // 2. Arrêter HevTun2Socks JNI globalement
        try {
            com.kighmu.vpn.engines.HevTun2Socks.stop()
        } catch (_: Exception) {}

        // 3. Arrêter chaque moteur individuellement (SSH, DNSTT)
        engines.forEach { engine ->
            try { engine.stop() } catch (_: Exception) {}
        }
        engines.clear()
        
        // 4. Annuler les jobs de monitoring
        scope.cancel()
        
        KighmuLogger.info(TAG, "Toutes les ressources MultiSlowDNS libérées")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {
        engines.firstOrNull { it.isRunning() }?.sendData(data, length)
    }

    override suspend fun receiveData(): ByteArray? {
        return engines.firstOrNull { it.isRunning() }?.receiveData()
    }

    override fun isRunning(): Boolean = engines.any { it.isRunning() }

    private fun buildConfig(p: SlowDnsProfile): KighmuConfig {
        return baseConfig.copy(
            sshCredentials = baseConfig.sshCredentials.copy(
                host = p.sshHost, port = p.sshPort,
                username = p.sshUser, password = p.sshPass
            ),
            slowDns = baseConfig.slowDns.copy(
                sshHost = p.sshHost,
                sshPort = p.sshPort,
                sshUser = p.sshUser,
                sshPass = p.sshPass,
                dnsServer = p.dnsServer,
                nameserver = p.nameserver,
                publicKey = p.publicKey.trim().replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "")
            )
        )
    }
}
