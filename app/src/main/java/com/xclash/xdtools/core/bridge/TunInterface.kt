package com.xclash.xdtools.core.bridge

interface TunInterface {
    fun markSocket(fd: Int)
    fun querySocketUid(protocol: Int, source: String, target: String): Int
}
