package hev.htproxy

import android.util.Log

class TProxyService {
    companion object {
        private var loaded = false
        const val TAG = "TProxyService"

        fun load() {
            if (!loaded) {
                try {
                    // hev_jni.cpp contient les wrappers JNI
                    // qui appellent hev_socks5_tunnel_main_from_str
                    // depuis libtun2socks.so (chargé automatiquement via linkage)
                    System.loadLibrary("hev_jni")
                    loaded = true
                    Log.i(TAG, "hev_jni chargé ✅")
                } catch (e: Throwable) {
                    Log.e(TAG, "Load failed: ${e.message}")
                }
            }
        }

        val isAvailable get() = loaded

        @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic external fun TProxyStopService()
        @JvmStatic external fun TProxyGetStats(): LongArray?
    }
}
