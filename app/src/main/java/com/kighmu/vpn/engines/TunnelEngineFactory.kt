package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.models.TunnelMode
import com.kighmu.vpn.utils.KighmuLogger


object TunnelEngineFactory {
    private const val TAG = "TunnelEngineFactory"

    fun create(config: KighmuConfig, context: Context, vpnService: android.net.VpnService? = null, @Suppress("UNUSED_PARAMETER") profileIndex: Int = 0): TunnelEngine {
        val mode = config.tunnelMode
        KighmuLogger.info(TAG, "=== Creation engine: ${mode.label} ===")
        return when (mode) {
            TunnelMode.SLOW_DNS      -> MultiSlowDnsEngine(config, context, vpnService)
            TunnelMode.HTTP_PROXY    -> MultiHttpProxyEngine(config, context, vpnService)
            TunnelMode.SSH_SSL_TLS   -> SshSslEngine(config, context)
            TunnelMode.V2RAY_XRAY    -> XrayVpnEngineFactory.create(config, context, vpnService)
            TunnelMode.V2RAY_SLOWDNS -> XrayDnsEngineFactory.create(config, context, vpnService)
            TunnelMode.HYSTERIA_UDP  -> MultiHysteriaEngine(config, context, vpnService)
            TunnelMode.ZIVPN_UDP     -> MultiZivpnEngine(config, context, vpnService)
        }
    }
}
