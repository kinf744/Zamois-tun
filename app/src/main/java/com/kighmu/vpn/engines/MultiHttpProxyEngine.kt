package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.HttpProxyProfileRepository
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*

class MultiHttpProxyEngine(
    private val baseConfig: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "MultiHttpProxy"
        const val MAX_RETRIES = 20
        const val RETRY_DELAY_MS = 2000L
        const val SESSION_TIMEOUT_MS = 30000L
        const val MTU = 8500
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val engines = mutableListOf<HttpProxyEngine>()
    private var socksBalancer: SocksBalancer? = null
    private var activePorts = listOf<Int>()

    override suspend fun start(): Int {
        val repo = HttpProxyProfileRepository(context)
        val selected = repo.getSelected()

        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil HTTP Proxy sélectionné → config par défaut")
            val engine = HttpProxyEngine(baseConfig, context)
            synchronized(engines) { engines.add(engine) }
            val port = engine.start()
            activePorts = listOf(port)
            return port
        }

        KighmuLogger.info(TAG, "Nettoyage engines précédents (${engines.size})...")
        synchronized(engines) {
            engines.forEach { e -> try { runBlocking { e.stop() } } catch (_: Exception) {} }
            engines.clear()
        }
        socksBalancer?.stop()
        socksBalancer = null
        delay(300)

        KighmuLogger.info(TAG, "=== STEP 1: Connexion SÉQUENTIELLE ${selected.size} profil(s) ===")
        val successPorts = mutableListOf<Int>()

        selected.forEachIndexed { idx, profile ->
            KighmuLogger.info(TAG, "Profil[${idx + 1}/${selected.size}] démarrage: ${profile.profileName}")
            val cfg = baseConfig.copy(
                httpProxy = baseConfig.httpProxy.copy(
                    sshHost       = profile.sshHost,
                    sshPort       = profile.sshPort,
                    sshUser       = profile.sshUser,
                    sshPass       = profile.sshPass,
                    proxyHost     = profile.proxyHost,
                    proxyPort     = profile.proxyPort,
                    customPayload = profile.customPayload
                )
            )

            var port = -1
            var attempt = 0
            while (attempt < MAX_RETRIES && port <= 0) {
                attempt++
                KighmuLogger.info(TAG, "Profil[${idx + 1}] tentative $attempt/$MAX_RETRIES...")
                val engine = HttpProxyEngine(cfg, context)
                try {
                    port = withTimeoutOrNull(SESSION_TIMEOUT_MS) { engine.start() } ?: -1
                    if (port > 0) {
                        synchronized(engines) { engines.add(engine) }
                        KighmuLogger.info(TAG, "Profil[${idx + 1}] CONNECTÉ ✓ port=$port")
                    } else {
                        try { engine.stop() } catch (_: Exception) {}
                        if (attempt < MAX_RETRIES) {
                            KighmuLogger.warning(TAG, "Profil[${idx + 1}] échec $attempt — retry ${RETRY_DELAY_MS}ms")
                            delay(RETRY_DELAY_MS)
                        }
                    }
                } catch (e: Exception) {
                    try { engine.stop() } catch (_: Exception) {}
                    KighmuLogger.error(TAG, "Profil[${idx + 1}] exception $attempt: ${e.message}")
                    if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
                }
            }

            if (port > 0) successPorts.add(port)
            else KighmuLogger.error(TAG, "Profil[${idx + 1}] ÉCHEC définitif ✗")
        }

        KighmuLogger.info(TAG, "=== STEP 2: ${successPorts.size}/${selected.size} connectés ===")
        if (successPorts.isEmpty()) throw Exception("Aucun profil HTTP Proxy connecté")

        activePorts = successPorts

        if (successPorts.size > 1) {
            KighmuLogger.info(TAG, "=== STEP 3: SocksBalancer sur ${successPorts.size} ports ===")
            val balancer = SocksBalancer(successPorts)
            balancer.start()
            socksBalancer = balancer
            KighmuLogger.info(TAG, "Balancer actif port ${SocksBalancer.BALANCER_PORT}")
        }

        val finalPort = if (successPorts.size > 1) SocksBalancer.BALANCER_PORT else successPorts.first()
        KighmuLogger.info(TAG, "=== HTTP Proxy prêt port=$finalPort ${successPorts.size} tunnel(s) ===")
        return finalPort
    }

    override fun startTun2Socks(fd: Int) {
        HevTun2Socks.init()
        val svc = vpnService ?: run {
            KighmuLogger.error(TAG, "VpnService null")
            return
        }
        if (HevTun2Socks.isAvailable) {
            val targetPort = if (activePorts.size > 1) SocksBalancer.BALANCER_PORT
                             else activePorts.firstOrNull() ?: return
            KighmuLogger.info(TAG, "hev HTTP Proxy port=$targetPort mtu=$MTU")
            HevTun2Socks.start(context, fd, targetPort, svc, mtu = MTU)
        } else {
            KighmuLogger.error(TAG, "HevTun2Socks non disponible")
        }
    }

    override suspend fun stop() {
        KighmuLogger.info(TAG, "Arrêt MultiHttpProxyEngine...")
        try { HevTun2Socks.stop() }                         catch (_: Exception) {}
        try { socksBalancer?.stop(); socksBalancer = null }  catch (_: Exception) {}
        synchronized(engines) {
            engines.forEach { try { runBlocking { it.stop() } } catch (_: Exception) {} }
            engines.clear()
        }
        scope.cancel()
        KighmuLogger.info(TAG, "MultiHttpProxyEngine arrêté ✅")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning(): Boolean = engines.any { it.isRunning() }
}
