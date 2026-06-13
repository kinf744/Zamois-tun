package com.github.kr328.clash.core.bridge
interface FetchCallback {
    fun report(status: String)
    fun complete(error: String?)
}
