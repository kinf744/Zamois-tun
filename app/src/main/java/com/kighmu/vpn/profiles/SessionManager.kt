package com.kighmu.vpn.profiles

import android.content.Context
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*

class SessionManager(private val context: Context) {
    private val sessions = mutableListOf<SlowDnsSession>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var basePort = 10900
    val TAG = "SessionManager"

    suspend fun startSessions(profiles: List<SlowDnsProfile>): List<SlowDnsSession> {
        stopAll()
        sessions.clear()
        profiles.forEachIndexed { idx, profile ->
            val port = basePort + idx
            val session = SlowDnsSession(profile, port, context)
            sessions.add(session)
            scope.launch { session.start() }
        }
        // Attendre que les sessions démarrent (max 15s)
        withTimeoutOrNull(15000) {
            while (sessions.none { it.state == SessionState.CONNECTED }) {
                delay(500)
            }
        }
        KighmuLogger.info(TAG, "${getConnected().size}/${sessions.size} sessions connectées")
        return sessions
    }

    fun getConnected(): List<SlowDnsSession> = sessions.filter { it.state == SessionState.CONNECTED }

    fun getActivePorts(): List<Int> = getConnected().map { it.localPort }

    fun getBestPort(): Int? = getActivePorts().firstOrNull()

    fun stopAll() {
        sessions.forEach { it.stop() }
        sessions.clear()
    }

    fun isAnyConnected() = sessions.any { it.state == SessionState.CONNECTED }
}
