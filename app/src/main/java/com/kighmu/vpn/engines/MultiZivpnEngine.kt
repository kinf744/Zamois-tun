package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.ZivpnProfileRepository
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*

/**
 * MultiZivpnEngine — ZIVPN UDP multi-profil
 *
 * Connexion SEQUENTIELLE avec retry (20 tentatives max par profil).
 * Tous les tunnels reussis sont equilibres via SocksBalancer + HEV JNI natif.
 * Si aucun profil n'est selectionne -> fallback sur la config unique (ZivpnEngine).
 */
class MultiZivpnEngine(
    private val baseConfig: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "MultiZivpn"
        const val MAX_RETRIES = 20
        const val RETRY_DELAY_MS = 2000L
        const val SESSION_TIMEOUT_MS = 35000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val engines = mutableListOf<ZivpnEngine>()
    private var socksBalancer: SocksBalancer? = null
    private var activePorts = listOf<Int>()

    override suspend fun start(): Int {
        val repo = ZivpnProfileRepository(context)
        val selected = repo.getSelected()

        // Fallback : aucun profil selectionne -> engine unique
        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil ZIVPN selectionne -> config par defaut")
            val engine = ZivpnEngine(baseConfig, context, vpnService)
            synchronized(engines) { engines.add(engine) }
            val port = engine.start()
            activePorts = listOf(port)
            return port
        }

        // Nettoyage
        KighmuLogger.info(TAG, "Nettoyage engines precedents (${engines.size})...")
        synchronized(engines) {
            engines.forEach { e -> try { runBlocking { e.stop() } } catch (_: Exception) {} }
            engines.clear()
        }
        socksBalancer?.stop()
        socksBalancer = null
        delay(500)

        KighmuLogger.info(TAG, "=== STEP 1: Connexion SEQUENTIELLE ${selected.size} profil(s) ZIVPN UDP ===")

        val successPorts = mutableListOf<Int>()

        selected.forEachIndexed { idx, profile ->
            KighmuLogger.info(TAG, "Profil[${idx + 1}/${selected.size}] demarrage: ${profile.profileName}")

            val cfg = baseConfig.copy(
                zivpnHost     = profile.serverAddress,
                zivpnPort     = profile.serverPort,
                zivpnPassword = profile.password,
                zivpnObfs     = profile.obfs
            )

            var port = -1
            var attempt = 0

            while (attempt < MAX_RETRIES && port <= 0) {
                attempt++
                KighmuLogger.info(TAG, "Profil[${idx + 1}] tentative $attempt/$MAX_RETRIES...")
                val engine = ZivpnEngine(cfg, context, vpnService, idx)
                try {
                    port = withTimeoutOrNull(SESSION_TIMEOUT_MS) { engine.start() } ?: -1
                    if (port > 0) {
                        synchronized(engines) { engines.add(engine) }
                        KighmuLogger.info(TAG, "Profil[${idx + 1}] CONNECTE tentative=$attempt port=$port")
                    } else {
                        try { engine.stop() } catch (_: Exception) {}
                        if (attempt < MAX_RETRIES) {
                            KighmuLogger.warning(TAG, "Profil[${idx + 1}] echec tentative $attempt - retry dans ${RETRY_DELAY_MS}ms")
                            delay(RETRY_DELAY_MS)
                        }
                    }
                } catch (e: Exception) {
                    try { engine.stop() } catch (_: Exception) {}
                    KighmuLogger.error(TAG, "Profil[${idx + 1}] exception tentative $attempt: ${e.message}")
                    if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
                }
            }

            if (port > 0) {
                successPorts.add(port)
            } else {
                KighmuLogger.error(TAG, "Profil[${idx + 1}] ECHEC definitif apres $MAX_RETRIES tentatives")
            }
        }

        KighmuLogger.info(TAG, "=== STEP 2: ${successPorts.size}/${selected.size} profils ZIVPN connectes ===")

        if (successPorts.isEmpty()) {
            throw Exception("Aucun profil ZIVPN connecte apres $MAX_RETRIES tentatives chacun")
        }

        activePorts = successPorts

        // Balancer si plusieurs tunnels reussis
        if (successPorts.size > 1) {
            KighmuLogger.info(TAG, "=== STEP 3: Demarrage SocksBalancer sur ${successPorts.size} ports ===")
            val balancer = SocksBalancer(successPorts)
            balancer.start()
            socksBalancer = balancer
            KighmuLogger.info(TAG, "Balancer actif sur port ${SocksBalancer.BALANCER_PORT}")
        }

        val finalPort = if (successPorts.size > 1) SocksBalancer.BALANCER_PORT else successPorts.first()
        KighmuLogger.info(TAG, "=== ZIVPN pret - port=$finalPort, ${successPorts.size} tunnel(s) actif(s) ===")
        return finalPort
    }

    override fun startTun2Socks(fd: Int) {
        // Lancer uniquement UZ Core sur chaque engine (sans HEV)
        synchronized(engines) {
            engines.forEach { engine ->
                try { engine.launchUzOnly() } catch (e: Exception) {
                    KighmuLogger.error(TAG, "Erreur launchUzOnly engine: ${e.message}")
                }
            }
        }
        // HEV pointe sur le balancer ou le port unique
        HevTun2Socks.init()
        val svc = vpnService ?: run {
            KighmuLogger.error(TAG, "VpnService null - impossible de demarrer HevTun2Socks")
            return
        }
        if (HevTun2Socks.isAvailable) {
            val targetPort = if (activePorts.size > 1) SocksBalancer.BALANCER_PORT
                             else activePorts.firstOrNull() ?: return
            KighmuLogger.info(TAG, "hev ZIVPN -> port=$targetPort (${activePorts.size} tunnel(s))")
            HevTun2Socks.start(context, fd, targetPort, svc)
        } else {
            KighmuLogger.error(TAG, "HevTun2Socks non disponible")
        }
    }

    override suspend fun stop() {
        KighmuLogger.info(TAG, "Arret MultiZivpnEngine...")
        // 1. HEV en premier
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        // 2. Balancer
        try { socksBalancer?.stop(); socksBalancer = null } catch (_: Exception) {}
        // 3. Stopper chaque engine
        synchronized(engines) {
            engines.forEach { engine ->
                try {
                    engine.xrayProcess?.destroyForcibly()
                    runBlocking { engine.stop() }
                } catch (_: Exception) {}
            }
            engines.clear()
        }
        // 4. Nettoyage nucleaire natif
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c",
            "killall -9 libuz_core.so libxray.so 2>/dev/null; pkill -9 -f libuz_core 2>/dev/null"
        )) } catch (_: Exception) {}
        scope.cancel()
        KighmuLogger.info(TAG, "MultiZivpnEngine arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning(): Boolean = engines.any { it.isRunning() }
}
