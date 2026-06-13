package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.HysteriaProfileRepository
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*

/**
 * MultiHysteriaEngine — Hysteria UDP multi-profil
 *
 * Connexion SEQUENTIELLE avec retry (20 tentatives max par profil).
 * Tous les tunnels reussis sont equilibres via SocksBalancer + HEV JNI natif.
 * Si aucun profil n'est selectionne -> fallback sur la config unique (HysteriaEngine).
 */
class MultiHysteriaEngine(
    private val baseConfig: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "MultiHysteria"
        const val MAX_RETRIES = 20
        const val RETRY_DELAY_MS = 2000L
        const val SESSION_TIMEOUT_MS = 35000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val engines = mutableListOf<HysteriaEngine>()
    private var socksBalancer: SocksBalancer? = null
    private var activePorts = listOf<Int>()

    override suspend fun start(): Int {
        val repo = HysteriaProfileRepository(context)
        val selected = repo.getSelected()

        // Fallback : aucun profil selectionne -> engine unique
        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil Hysteria selectionne -> config par defaut")
            val engine = HysteriaEngine(baseConfig, context, vpnService, HysteriaEngine.getFreePort(), 0)
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

        KighmuLogger.info(TAG, "=== STEP 1: Connexion SEQUENTIELLE ${selected.size} profil(s) Hysteria UDP ===")

        val successPorts = mutableListOf<Int>()

        selected.forEachIndexed { idx, profile ->
            KighmuLogger.info(TAG, "Profil[${idx + 1}/${selected.size}] demarrage: ${profile.profileName}")

            val cfg = baseConfig.copy(
                hysteria = baseConfig.hysteria.copy(
                    serverAddress = profile.serverAddress,
                    authPassword = profile.authPassword,
                    uploadMbps = profile.uploadMbps,
                    downloadMbps = profile.downloadMbps,
                    obfsPassword = profile.obfsPassword,
                    portHopping = profile.portHopping
                )
            )

            var port = -1
            var attempt = 0

            while (attempt < MAX_RETRIES && port <= 0) {
                attempt++
                KighmuLogger.info(TAG, "Profil[${idx + 1}] tentative $attempt/$MAX_RETRIES...")
                val assignedPort = HysteriaEngine.getFreePort()
                KighmuLogger.info(TAG, "Profil[${idx + 1}] port SOCKS assigne: $assignedPort")
                val engine = HysteriaEngine(cfg, context, vpnService, assignedPort, idx)
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

        KighmuLogger.info(TAG, "=== STEP 2: ${successPorts.size}/${selected.size} profils Hysteria connectes ===")

        if (successPorts.isEmpty()) {
            throw Exception("Aucun profil Hysteria connecte apres $MAX_RETRIES tentatives chacun")
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
        KighmuLogger.info(TAG, "=== Hysteria pret - port=$finalPort, ${successPorts.size} tunnel(s) actif(s) ===")
        return finalPort
    }

    override fun startTun2Socks(fd: Int) {
        HevTun2Socks.init()
        val svc = vpnService ?: run {
            KighmuLogger.error(TAG, "VpnService null - impossible de demarrer HevTun2Socks")
            return
        }
        if (HevTun2Socks.isAvailable) {
            // Toujours pointer HEV sur un seul port SOCKS5 :
            // - si balancer actif → son port unique (qui distribue en round-robin)
            // - sinon → le port direct du premier tunnel
            val targetPort = if (activePorts.size > 1) SocksBalancer.BALANCER_PORT
                             else activePorts.firstOrNull() ?: return
            KighmuLogger.info(TAG, "hev Hysteria → port=$targetPort (${activePorts.size} tunnel(s))")
            HevTun2Socks.start(context, fd, targetPort, svc)
        } else {
            KighmuLogger.error(TAG, "HevTun2Socks non disponible")
        }
    }

    override suspend fun stop() {
        KighmuLogger.info(TAG, "Arret MultiHysteriaEngine...")
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        try { socksBalancer?.stop(); socksBalancer = null } catch (_: Exception) {}
        synchronized(engines) {
            engines.forEach { try { runBlocking { it.stop() } } catch (_: Exception) {} }
            engines.clear()
        }
        scope.cancel()
        KighmuLogger.info(TAG, "MultiHysteriaEngine arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning(): Boolean = engines.any { it.isRunning() }
}
