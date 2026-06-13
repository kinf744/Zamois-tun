package com.github.kr328.clash.core.bridge

interface TunInterface {
    fun markSocket(fd: Int)
    fun querySocketUid(protocol: Int, source: String, target: String): Int
}
