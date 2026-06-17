package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.XrayDnsProfile
import com.kighmu.vpn.profiles.XrayDnsProfileRepository
import com.kighmu.vpn.utils.KighmuLogger
import java.util.concurrent.LinkedBlockingQueue

object XrayDnsEngineFactory {
    fun create(
        config: KighmuConfig,
        context: Context,
        vpnService: VpnService? = null
    ): TunnelEngine {
        val repo     = XrayDnsProfileRepository(context)
        val selected = repo.getSelected()
        val all      = repo.getAll()

        // Multi-profil ou tunnelCount > 1 -> MultiXraySlowDnsEngine
        val needsMulti = selected.size > 1 ||
            (selected.size == 1 && selected[0].tunnelCount > 1) ||
            (selected.isEmpty() && all.size > 1)

        if (needsMulti) {
            KighmuLogger.info("XrayDnsEngineFactory", "Mode multi-profil -> MultiXraySlowDnsEngine (${selected.size} profils selectionnes)")
            return MultiXraySlowDnsEngine(config, context, vpnService)
        }

        // Single profil
        val profile = selected.firstOrNull() ?: all.firstOrNull()
            ?: buildProfileFromConfig(config)
        KighmuLogger.info("XrayDnsEngineFactory", "Mode single-profil -> XrayDnsEngineWrapper (${profile.profileName})")
        return XrayDnsEngineWrapper(context, profile, vpnService)
    }

    private fun buildProfileFromConfig(config: KighmuConfig): XrayDnsProfile {
        val v2dns = config.v2dns
        val xray  = config.xray
        return XrayDnsProfile(
            profileName    = "Default",
            xrayLink       = xray.linkConfig.link,
            xrayJsonConfig = v2dns.jsonConfig.ifBlank { xray.v2dnsJsonConfig },
            protocol       = "vmess",
            dnsServer      = "8.8.8.8",
            dnsPort        = 53,
            nameserver     = "",
            publicKey      = "",
            tunnelCount    = 1
        )
    }
}

class XrayDnsEngineWrapper(
    private val context: Context,
    private val profile: XrayDnsProfile,
    private val vpnService: VpnService? = null
) : TunnelEngine {

    private val inner = XrayDnsEngine(context, profile, vpnService)
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)
    private var running = false

    override suspend fun start(): Int {
        running = true
        return inner.start()
    }

    override fun startTun2Socks(fd: Int) {
        try {
            HevTun2Socks.init()
            if (HevTun2Socks.isAvailable && vpnService != null) {
                HevTun2Socks.start(context, fd, inner.getSocksPort(), vpnService, mtu = 8500)
                KighmuLogger.info("XrayDnsEngineWrapper", "HevTun2Socks demarre OK")
            } else {
                KighmuLogger.error("XrayDnsEngineWrapper", "HevTun2Socks non disponible")
            }
        } catch (e: Exception) {
            KighmuLogger.error("XrayDnsEngineWrapper", "Erreur HevTun2Socks: ${e.message}")
        }
    }

    override suspend fun stop() {
        running = false
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        inner.stop()
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? =
        receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    override fun isRunning() = running
}
