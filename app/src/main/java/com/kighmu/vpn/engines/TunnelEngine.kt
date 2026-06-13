package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.models.TunnelMode

/**
 * Abstract tunnel engine interface.
 * Each mode implements this to start/stop its underlying transport.
 * Returns the local SOCKS5/HTTP proxy port that the VPN interface routes traffic through.
 */
interface TunnelEngine {
    /** Start the engine. Returns the local proxy port. */
    suspend fun start(): Int

    /** Stop the engine and clean up resources. */
    suspend fun stop()

    /** Send raw packet data into the tunnel. */
    suspend fun sendData(data: ByteArray, length: Int)

    /** Receive raw packet data from the tunnel. Returns null when done. */
    suspend fun receiveData(): ByteArray?

    /** Is the tunnel currently running? */
    fun isRunning(): Boolean

    /** Start tun2socks routing with given TUN fd */
    fun startTun2Socks(fd: Int) {}
}
