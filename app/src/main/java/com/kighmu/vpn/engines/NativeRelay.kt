package com.kighmu.vpn.engines

object NativeRelay {
    private var loaded = false
    init {
        try { System.loadLibrary("kighmu_relay"); loaded = true } catch (_: Throwable) {}
    }
    val isAvailable get() = loaded
    @JvmStatic external fun relay(fd1: Int, fd2: Int)
}
