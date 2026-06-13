package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.V2rayDnsProfileRepository
import com.kighmu.vpn.profiles.V2rayDnsProfile
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*

/**
 * MultiXraySlowDnsEngine - V2Ray + SlowDNS multi-profil
 * Principe calqué sur MultiSlowDnsEngine :
 *  - STEP 1 : tous les dnstt démarrent séquentiellement avec retry agressif
 *  - STEP 2 : tous les Xray démarrent en PARALLÈLE sur leurs dnstt respectifs
 *  - STEP 3 : balancer sur tous les ports SOCKS réussis
 */
class MultiXraySlowDnsEngine(
    private val baseConfig: KighmuConfig,
    private val context: Context,
    private val vpnService: android.net.VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "MultiXraySlo"
        const val MAX_RETRIES = 30
        const val RETRY_DELAY_MS = 800L
        const val DNSTT_TIMEOUT_MS = 10000L
        const val XRAY_TIMEOUT_MS  = 8000L
    }

    private data class FluxConfig(
        val label: String,
        val dnsCfg: KighmuConfig,
        val xrayCfg: KighmuConfig
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dnsttEngines = mutableListOf<SlowDnsEngine>()
    private val xrayEngines  = mutableListOf<XrayEngine>()
    private var activePorts  = listOf<Int>()
    private var socksBalancer: SocksBalancer? = null
    @Volatile private var replacingCount = 0
    private val fluxConfigs = mutableListOf<FluxConfig>()

    override suspend fun start(): Int {
        val repo     = V2rayDnsProfileRepository(context)
        val selected = repo.getSelected()

        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil V2ray+DNS sélectionné -> config par défaut")
            val dnstt = SlowDnsEngine(baseConfig, context, null, 0)
            synchronized(dnsttEngines) { dnsttEngines.add(dnstt) }
            val port = dnstt.startDnsttOnly()
            val xray = XrayEngine(baseConfig, context, port, 0, vpnService)
            synchronized(xrayEngines) { xrayEngines.add(xray) }
            activePorts = listOf(port)
            return xray.start()
        }

        KighmuLogger.info(TAG, "Nettoyage engines précédents (${dnsttEngines.size} dnstt, ${xrayEngines.size} xray)...")
        synchronized(xrayEngines) {
            xrayEngines.forEach { try { runBlocking { it.stop() } } catch (_: Exception) {} }
            xrayEngines.clear()
        }
        synchronized(dnsttEngines) {
            dnsttEngines.forEach { try { runBlocking { it.stop() } } catch (_: Exception) {} }
            dnsttEngines.clear()
        }
        synchronized(fluxConfigs) { fluxConfigs.clear() }
        socksBalancer?.stop()
        socksBalancer = null

        try {
            val portsToKill = (10800..10810).joinToString(" ") { "$it/tcp" } + " " +
                              (7000..7020).joinToString(" ") { "$it/tcp" } + " 10900/tcp"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k $portsToKill")).waitFor()
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f dnstt")).waitFor()
        } catch (_: Exception) {}
        delay(500)

        val allFluxConfigs = mutableListOf<FluxConfig>()
        selected.forEach { profile ->
            val count = profile.tunnelCount.coerceIn(1, 4)
            val dnsCfg = baseConfig.copy(
                slowDns = baseConfig.slowDns.copy(
                    dnsServer  = profile.dnsServer,
                    dnsPort    = profile.dnsPort,
                    nameserver = profile.nameserver,
                    publicKey  = profile.publicKey
                )
            )
            val xrayCfg = baseConfig.copy(
                xray = baseConfig.xray.copy(
                    jsonConfig    = profile.xrayJsonConfig,
                    protocol      = profile.protocol,
                    serverAddress = profile.serverAddress,
                    serverPort    = profile.serverPort,
                    uuid          = profile.uuid,
                    encryption    = profile.encryption,
                    transport     = profile.transport,
                    wsPath        = profile.wsPath,
                    wsHost        = profile.wsHost,
                    tls           = profile.tls,
                    sni           = profile.sni,
                    allowInsecure = profile.allowInsecure
                )
            )
            repeat(count) { fluxIdx ->
                allFluxConfigs.add(FluxConfig("${profile.profileName}[${fluxIdx+1}/$count]", dnsCfg, xrayCfg))
            }
        }
        synchronized(fluxConfigs) { fluxConfigs.addAll(allFluxConfigs) }

        val totalFlux = allFluxConfigs.size
        KighmuLogger.info(TAG, "=== STEP 1: $totalFlux flux dnstt séquentiels (${selected.size} profil(s)) ===")

        data class DnsttResult(val idx: Int, val port: Int, val flux: FluxConfig, val engine: SlowDnsEngine)
        val dnsttResults = mutableListOf<DnsttResult>()

        allFluxConfigs.forEachIndexed { idx, flux ->
            KighmuLogger.info(TAG, "Flux[${idx+1}/$totalFlux] dnstt démarrage: ${flux.label}")
            var port    = -1
            var attempt = 0
            val engine  = SlowDnsEngine(flux.dnsCfg, context, null, idx)

            while (attempt < MAX_RETRIES && port <= 0) {
                attempt++
                if (attempt > 1) {
                    KighmuLogger.warning(TAG, "Flux[${idx+1}] dnstt retry $attempt/$MAX_RETRIES dans ${RETRY_DELAY_MS}ms...")
                    try { engine.stopDnsttOnly() } catch (_: Exception) {}
                    delay(RETRY_DELAY_MS)
                }
                port = try {
                    withTimeoutOrNull(DNSTT_TIMEOUT_MS) { engine.startDnsttOnly() } ?: -1
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "Flux[${idx+1}] dnstt exception tentative $attempt: ${e.message}")
                    -1
                }
            }

            if (port > 0) {
                synchronized(dnsttEngines) { dnsttEngines.add(engine) }
                dnsttResults.add(DnsttResult(idx, port, flux, engine))
                KighmuLogger.info(TAG, "Flux[${idx+1}] dnstt OK port=$port (tentative $attempt)")
            } else {
                try { engine.stop() } catch (_: Exception) {}
                KighmuLogger.error(TAG, "Flux[${idx+1}] dnstt ABANDON après $MAX_RETRIES tentatives ✗")
            }
        }

        KighmuLogger.info(TAG, "=== STEP 2: ${dnsttResults.size}/$totalFlux dnstt connectés - démarrage Xray en parallèle ===")
        if (dnsttResults.isEmpty()) throw Exception("Aucun flux dnstt connecté après tentatives")

        val xraySocksPorts = mutableListOf<Int>()
        val xrayJobs = dnsttResults.map { result ->
            scope.async {
                KighmuLogger.info(TAG, "XrayEngine[${result.idx}] démarrage sur dnsttPort=${result.port}")
                val xrayEngine = XrayEngine(result.flux.xrayCfg, context, result.port, result.idx, vpnService)
                try {
                    val socksPort = withTimeoutOrNull(XRAY_TIMEOUT_MS) { xrayEngine.start() } ?: -1
                    if (socksPort > 0) {
                        KighmuLogger.info(TAG, "XrayEngine[${result.idx}] CONNECTÉ socksPort=$socksPort")
                        Pair(xrayEngine, socksPort)
                    } else {
                        KighmuLogger.error(TAG, "XrayEngine[${result.idx}] ÉCHEC port=$socksPort")
                        try { xrayEngine.stop() } catch (_: Exception) {}
                        null
                    }
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "XrayEngine[${result.idx}] exception: ${e.message}")
                    try { xrayEngine.stop() } catch (_: Exception) {}
                    null
                }
            }
        }

        xrayJobs.awaitAll().filterNotNull().forEach { (engine, port) ->
            synchronized(xrayEngines) { xrayEngines.add(engine) }
            xraySocksPorts.add(port)
        }

        if (xraySocksPorts.isEmpty()) throw Exception("Aucun XrayEngine démarré avec succès")

        val balancer = SocksBalancer(xraySocksPorts, vpnService)
        balancer.start()
        socksBalancer = balancer
        activePorts   = xraySocksPorts
        val finalPort = SocksBalancer.BALANCER_PORT

        KighmuLogger.info(TAG, "=== V2ray+DNS prêt - balancer=$finalPort, ${xraySocksPorts.size} tunnel(s) actif(s) ===")
        monitorSessions()
        return finalPort
    }

    private fun monitorSessions() {
        scope.launch {
            while (isActive) {
                delay(3000)
                val deadIndexes = synchronized(xrayEngines) {
                    xrayEngines.mapIndexedNotNull { idx, e -> if (!e.isRunning()) idx else null }
                }
                deadIndexes.forEach { idx ->
                    KighmuLogger.warning(TAG, "XrayEngine[$idx] mort - warm replacement...")
                    scope.launch {
                        replacingCount++
                        try {
                            val flux = synchronized(fluxConfigs) { fluxConfigs.getOrNull(idx) } ?: return@launch
                            val newDnstt = SlowDnsEngine(flux.dnsCfg, context, null, idx)
                            val newDnsttPort = withTimeoutOrNull(DNSTT_TIMEOUT_MS) {
                                newDnstt.startDnsttOnly()
                            } ?: -1
                            if (newDnsttPort <= 0) {
                                KighmuLogger.error(TAG, "XrayEngine[$idx] dnstt replacement échec")
                                try { newDnstt.stop() } catch (_: Exception) {}
                                return@launch
                            }
                            val newXray = XrayEngine(flux.xrayCfg, context, newDnsttPort, idx, vpnService)
                            val newPort = withTimeoutOrNull(XRAY_TIMEOUT_MS) { newXray.start() } ?: -1
                            if (newPort > 0) {
                                val oldXray = synchronized(xrayEngines) {
                                    xrayEngines.getOrNull(idx)?.also { xrayEngines[idx] = newXray }
                                }
                                synchronized(dnsttEngines) {
                                    if (idx < dnsttEngines.size) dnsttEngines[idx] = newDnstt
                                }
                                val alivePorts = synchronized(xrayEngines) {
                                    xrayEngines.mapIndexedNotNull { i, e ->
                                        if (e.isRunning()) activePorts.getOrNull(i) else null
                                    }
                                }
                                if (alivePorts.isNotEmpty()) socksBalancer?.updatePorts(alivePorts)
                                KighmuLogger.info(TAG, "XrayEngine[$idx] warm replacement OK port=$newPort")
                                try { oldXray?.stop() } catch (_: Exception) {}
                            } else {
                                KighmuLogger.error(TAG, "XrayEngine[$idx] warm replacement échec")
                                try { newXray.stop() } catch (_: Exception) {}
                                try { newDnstt.stop() } catch (_: Exception) {}
                            }
                        } catch (e: Exception) {
                            KighmuLogger.error(TAG, "XrayEngine[$idx] erreur replacement: ${e.message}")
                        } finally {
                            replacingCount--
                        }
                    }
                }
                val alive = synchronized(xrayEngines) { xrayEngines.count { it.isRunning() } }
                if (alive == 0 && replacingCount == 0 && synchronized(xrayEngines) { xrayEngines.isNotEmpty() }) {
                    KighmuLogger.error(TAG, "Tous les XrayEngines morts - redémarrage complet...")
                    synchronized(xrayEngines) {
                        xrayEngines.forEach { try { runBlocking { it.stop() } } catch (_: Exception) {} }
                        xrayEngines.clear()
                    }
                    synchronized(dnsttEngines) {
                        dnsttEngines.forEach { try { runBlocking { it.stop() } } catch (_: Exception) {} }
                        dnsttEngines.clear()
                    }
                    delay(1000)
                    try { start() } catch (e: Exception) {
                        KighmuLogger.error(TAG, "Échec redémarrage complet: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    override fun startTun2Socks(fd: Int) {
        HevTun2Socks.init()
        val svc = vpnService ?: run {
            KighmuLogger.error(TAG, "VpnService null - impossible de démarrer HevTun2Socks")
            xrayEngines.firstOrNull()?.startTun2Socks(fd)
            return
        }
        if (HevTun2Socks.isAvailable) {
            val targetPort = SocksBalancer.BALANCER_PORT
            KighmuLogger.info(TAG, "hev V2ray+DNS -> balancer:$targetPort (${activePorts.size} tunnel(s))")
            HevTun2Socks.start(context, fd, targetPort, svc)
        } else {
            xrayEngines.firstOrNull()?.startTun2Socks(fd)
        }
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning(): Boolean = synchronized(xrayEngines) { xrayEngines.any { it.isRunning() } }

    override suspend fun stop() {
        KighmuLogger.info(TAG, "Arrêt MultiXraySlowDnsEngine...")
        scope.cancel()
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        try { socksBalancer?.stop(); socksBalancer = null } catch (_: Exception) {}
        synchronized(xrayEngines) {
            xrayEngines.forEach { try { runBlocking { it.stop() } } catch (_: Exception) {} }
            xrayEngines.clear()
        }
        synchronized(dnsttEngines) {
            dnsttEngines.forEach { try { runBlocking { it.stop() } } catch (_: Exception) {} }
            dnsttEngines.clear()
        }
        synchronized(fluxConfigs) { fluxConfigs.clear() }
        KighmuLogger.info(TAG, "MultiXraySlowDnsEngine arrêté.")
    }
}
