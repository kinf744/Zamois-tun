package com.github.kr328.clash.core.bridge

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import java.io.File

@Keep
object Bridge {

    external fun nativeReset()
    external fun nativeForceGc()
    external fun nativeSuspend(suspend: Boolean)
    external fun nativeQueryTunnelState(): String
    external fun nativeQueryTrafficNow(): Long
    external fun nativeQueryTrafficTotal(): Long
    external fun nativeNotifyDnsChanged(dnsList: String)
    external fun nativeNotifyTimeZoneChanged(name: String, offset: Int)
    external fun nativeNotifyInstalledAppChanged(uidList: String)
    external fun nativeStartTun(fd: Int, stack: String, gateway: String, portal: String, dns: String, cb: TunInterface)
    external fun nativeStopTun()
    external fun nativeStartHttp(listenAt: String): String?
    external fun nativeStopHttp()
    external fun nativeQueryGroupNames(excludeNotSelectable: Boolean): String
    external fun nativeQueryGroup(name: String, sort: String): String?
    external fun nativeHealthCheck(completable: CompletableDeferred<Unit>, name: String)
    external fun nativeHealthCheckAll()
    external fun nativePatchSelector(selector: String, name: String): Boolean
    external fun nativeFetchAndValid(completable: FetchCallback, path: String, url: String, force: Boolean)
    external fun nativeLoad(completable: CompletableDeferred<Unit>, path: String)
    external fun nativeQueryProviders(): String
    external fun nativeUpdateProvider(completable: CompletableDeferred<Unit>, type: String, name: String)
    external fun nativeReadOverride(slot: Int): String
    external fun nativeWriteOverride(slot: Int, content: String)
    external fun nativeClearOverride(slot: Int)
    external fun nativeQueryConfiguration(): String
    external fun nativeSubscribeLogcat(callback: LogcatInterface)
    external fun nativeCoreVersion(): String

    private external fun nativeInit(home: String, versionName: String, sdkVersion: Int)

    var initialized = false
        private set

    fun initIfNeeded(context: android.content.Context) {
        if (initialized) return
        try {
            System.loadLibrary("bridge")
            ParcelFileDescriptor.open(
                File(context.packageCodePath),
                ParcelFileDescriptor.MODE_READ_ONLY
            ).detachFd()
            val home = context.filesDir.resolve("clash").apply { mkdirs() }.absolutePath
            val versionName = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            Log.d("Bridge", "nativeInit home=$home")
            nativeInit(home, versionName, Build.VERSION.SDK_INT)
            initialized = true
            Log.d("Bridge", "nativeInit OK")
        } catch (e: Throwable) {
            Log.e("Bridge", "initIfNeeded ERREUR: ${e.message}")
        }
    }
}
