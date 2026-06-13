package com.xclash.xdtools.core.bridge

import kotlinx.coroutines.CompletableDeferred
import android.util.Log
import android.os.Build
import java.io.File

object Bridge {

    init {
        try {
            Log.e("Bridge", ">>> CLINIT: loadLibrary clash")
            System.loadLibrary("clash")
            Log.e("Bridge", ">>> CLINIT: loadLibrary bridge")
            System.loadLibrary("bridge")
            Log.e("Bridge", ">>> CLINIT: libs OK - init native")
            val app = ClAh6JNSv6.JegAS39ipj4a.JegAS39ipj4a()
            val clashDir = File(app.filesDir, "clash").also { it.mkdirs() }
            val versionName = try {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) { "unknown" }
            Log.e("Bridge", ">>> CLINIT: nativeInit home=${clashDir.absolutePath} ver=$versionName")
            nativeInit(clashDir.absolutePath, versionName, Build.VERSION.SDK_INT)
            Log.e("Bridge", ">>> CLINIT: nativeInit OK")
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
