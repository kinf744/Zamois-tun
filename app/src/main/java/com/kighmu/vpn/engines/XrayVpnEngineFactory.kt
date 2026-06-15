package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.XrayVpnProfile
import com.kighmu.vpn.profiles.XrayVpnProfileRepository
import com.kighmu.vpn.utils.KighmuLogger
import java.util.concurrent.LinkedBlockingQueue

object XrayVpnEngineFactory {
    fun create(
        config: KighmuConfig,
        context: Context,
        vpnService: VpnService? = null
    ): TunnelEngine {
        val repo = XrayVpnProfileRepository(context)
        val selected = repo.getSelected()
        val profile = selected.firstOrNull() ?: repo.getAll().firstOrNull()
            ?: buildProfileFromConfig(config)
        return XrayVpnEngineWrapper(context, profile, vpnService)
    }

    private fun buildProfileFromConfig(config: KighmuConfig): XrayVpnProfile {
        val xray = config.xray
        val migrated = com.kighmu.vpn.models.XrayConfig.migrate(xray)
        val json = migrated.jsonConfig2.json.ifBlank { migrated.getActiveJson() }
        val link = migrated.linkConfig.link
        return XrayVpnProfile(
            profileName   = "Default",
            activeMode    = migrated.activeMode,
            xrayLink      = link,
            xrayLinkJson  = migrated.linkConfig.parsedJson,
            xrayJson      = json,
            protocol      = "vmess",
            serverAddress = "",
            serverPort    = 443
        )
    }
}

class XrayVpnEngineWrapper(
    private val context: Context,
    private val profile: XrayVpnProfile,
    private val vpnService: VpnService? = null
) : TunnelEngine {

    private val inner = XrayVpnEngine(context, profile, vpnService)
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
                KighmuLogger.info("XrayVpnEngineWrapper", "HevTun2Socks demarre OK")
            } else {
                KighmuLogger.error("XrayVpnEngineWrapper", "HevTun2Socks non disponible")
            }
        } catch (e: Exception) {
            KighmuLogger.error("XrayVpnEngineWrapper", "Erreur HevTun2Socks: ${e.message}")
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
