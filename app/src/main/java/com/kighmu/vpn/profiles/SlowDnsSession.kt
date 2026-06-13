package com.kighmu.vpn.profiles

import android.content.Context
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.File

enum class SessionState { IDLE, CONNECTING, CONNECTED, FAILED, STOPPED }

class SlowDnsSession(
    val profile: SlowDnsProfile,
    val localPort: Int,
    private val context: Context
) {
    var state: SessionState = SessionState.IDLE
    private var dnsttProcess: Process? = null
    private var sshProcess: Process? = null
    private var running = false
    val TAG = "Session[${profile.profileName}:$localPort]"

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        running = true
        state = SessionState.CONNECTING
        try {
            // Démarrer dnstt-client
            val dnsttBin = File(context.applicationInfo.nativeLibraryDir, "libdnstt.so")
            if (!dnsttBin.exists()) throw Exception("libdnstt.so introuvable")
            dnsttBin.setExecutable(true)

            val dnsttCmd = arrayOf(
                dnsttBin.absolutePath,
                "-udp", "${profile.dnsServer}:53",
                "-pubkey", profile.publicKey,
                profile.nameserver,
                "127.0.0.1:${localPort + 100}"
            )
            val pb = ProcessBuilder(*dnsttCmd).redirectErrorStream(true)
            dnsttProcess = pb.start()

            // Lire output dnstt
            Thread {
                try {
                    dnsttProcess!!.inputStream.bufferedReader().forEachLine { line ->
                        if (running) KighmuLogger.info(TAG, "dnstt: $line")
                    }
                } catch (_: Exception) {}
            }.start()

            delay(2000)

            // Vérifier que dnstt tourne
            try { dnsttProcess!!.exitValue(); throw Exception("dnstt crashed") }
            catch (_: IllegalThreadStateException) {}

            // SSH via trilead est géré par SlowDnsEngine existant
            // On utilise le port dnstt directement (port=$localPort, user=${profile.sshUser})
            state = SessionState.CONNECTED
            KighmuLogger.info(TAG, "Session démarrée port=$localPort")
            true
        } catch (e: Exception) {
            state = SessionState.FAILED
            KighmuLogger.error(TAG, "Échec: ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        state = SessionState.STOPPED
        try { dnsttProcess?.destroyForcibly() } catch (_: Exception) {}
        try { sshProcess?.destroyForcibly() } catch (_: Exception) {}
        dnsttProcess = null
        sshProcess = null
        KighmuLogger.info(TAG, "Session arrêtée")
    }
}
