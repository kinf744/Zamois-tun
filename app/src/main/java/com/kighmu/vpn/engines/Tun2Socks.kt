package com.kighmu.vpn.engines

object Tun2Socks {
    val isAvailable get() = dev.epro.tun2socks.Tun2Socks.isAvailable

    fun runTun2Socks(tunFd: Int, mtu: Int, netifAddr: String, netmask: String,
                     socksAddr: String, udpgwAddr: String,
                     udpgwTransparentDns: Boolean, loglevel: Int = 3): Int {
        return try {
            dev.epro.tun2socks.Tun2Socks.runTun2Socks(
                tunFd, mtu, netifAddr, netmask, socksAddr, udpgwAddr, udpgwTransparentDns, loglevel)
        } catch (_: Exception) { -1 }
    }

    fun terminateTun2Socks() {
        try { dev.epro.tun2socks.Tun2Socks.terminateTun2Socks() } catch (_: Throwable) {}
    }
}
