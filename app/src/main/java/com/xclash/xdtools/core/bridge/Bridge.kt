package com.xclash.xdtools.core.bridge

import kotlinx.coroutines.CompletableDeferred
import android.util.Log

object Bridge {

    init {
        try {
            Log.e("Bridge", ">>> CLINIT: loadLibrary bridge")
            System.loadLibrary("bridge")
            Log.e("Bridge", ">>> CLINIT: OK")
        } catch (e: Throwable) {
            Log.e("Bridge", ">>> CLINIT ERREUR: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun isLoaded() = true
    fun load(): Boolean = true
    fun loadOnMainThread() {}

    @JvmStatic external fun nativeInit(home: String, versionName: String, sdkVersion: Int)
    @JvmStatic external fun nativeLoad(completable: CompletableDeferred<Unit>, home: String)
    @JvmStatic external fun nativeStartTun(
        fd: Int, stack: String, gateway: String,
        portal: String, dns: String, cb: TunInterface
    )
    @JvmStatic external fun nativeStopTun()
    @JvmStatic external fun nativeReset()
    @JvmStatic external fun nativeCoreVersion(): String
}
