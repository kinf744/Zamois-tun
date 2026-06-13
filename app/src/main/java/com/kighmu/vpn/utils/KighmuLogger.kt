package com.kighmu.vpn.utils

import android.util.Log
import com.kighmu.vpn.models.LogEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Central logger for KIGHMU VPN.
 * Keeps last 500 log entries in memory and exposes a Flow for UI.
 */

fun String.sanitizeGlobal(): String {
    return this
        .replace(Regex("""(\\d{1,3}\\.){3}\\d{1,3}"""), "[IP]")
        .replace(Regex(""":\\d{2,5}\b"""), ":[PORT]")
        .replace(Regex("/data/app/~~[a-zA-Z0-9_-]+/"), "/data/app/[MASKED]/")
}


object KighmuLogger {

    private val _logFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = 100)
    val logFlow = _logFlow.asSharedFlow()

    private val logBuffer = ConcurrentLinkedDeque<LogEntry>()
    private const val MAX_ENTRIES = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun sanitize(message: String): String {
        var msg = message
        // Ne pas masquer les balises HTML (couleurs serveurs)
        if (msg.contains("<font") || msg.contains("<b>")) {
            // On sanitize quand même les mots de passe à l'intérieur si présents
            msg = msg.replace(Regex("""(auth_str|password|obfs)[:\s"=]+\S+"""), "$1: ***")
            return msg
        }
        
        msg = msg.replace(Regex("""(auth_str|password|obfs)[:\s"=]+\S+"""), "$1: ***")
        msg = msg.replace(Regex("""-pubkey\s+[A-Fa-f0-9]+"""), "-pubkey ***")
        msg = msg.replace(Regex("""ghp_[A-Za-z0-9]+"""), "***")
        // Reformater la version SSH du serveur avec couleur selon l'OS
        val sshVerRegex = Regex("""SSH-2\.0-([\w_.]+)\s*(.*)""")
        val sshMatch = sshVerRegex.find(msg)
        if (sshMatch != null) {
            val version = sshMatch.groupValues[1]
            val os = sshMatch.groupValues[2].trim()
            val osLower = os.lowercase()
            val osColor = when {
                osLower.contains("ubuntu")  -> "#E95420"
                osLower.contains("debian")  -> "#A80030"
                osLower.contains("centos")  -> "#932279"
                osLower.contains("fedora")  -> "#3C6EB4"
                osLower.contains("alpine")  -> "#0D597F"
                osLower.contains("arch")    -> "#1793D1"
                osLower.contains("freebsd") -> "#AB2B28"
                osLower.contains("windows") -> "#00A4EF"
                else                        -> "#AAAAAA"
            }
            msg = if (os.isNotEmpty())
                "<b><font color=\"#4FC3F7\">Server version:</font></b> " +
                "<font color=\"#FFFFFF\">$version</font> " +
                "<font color=\"$osColor\">($os)</font>"
            else
                "<b><font color=\"#4FC3F7\">Server version:</font></b> " +
                "<font color=\"#FFFFFF\">$version</font>"
        }
        msg = msg.replace(Regex("""/data/app/[^/]+/[^/]+/lib/[^/]+/"""), "lib/")
        msg = msg.replace(Regex("""/data/user/0/[^\s/]+/"""), "data/")
        msg = msg.replace(Regex("""127[.]0[.]0[.]1:([0-9]{5,})""")) { m ->
            val port = m.groupValues[1].toIntOrNull() ?: 0
            if (port > 10000) "127.0.0.1:****" else m.value
        }
        msg = msg.replace(Regex("""[\w.-]+[.]ggff[.]net"""), "***.ggff.net")
        if (msg.contains("tun2socks cmd:") || msg.startsWith("tun2socks:")) {
            val soIdx = msg.indexOf(".so")
            if (soIdx > 0) msg = msg.substring(0, soIdx + 3) + " [...]"
        }
        return msg
    }

    fun log(message: String, level: LogEntry.LogLevel = LogEntry.LogLevel.INFO, tag: String = "KIGHMU") {
        // Filtrer les logs debug trop verbeux et les erreurs de connexion répétitives ("sales")
        val msgLower = message.lowercase()
        if (msgLower.contains("[debu]") || msgLower.contains("socks5 tcp") ||
            msgLower.contains("tcp eof") || msgLower.contains("tcp request") ||
            msgLower.contains("action:proxy") || msgLower.contains("action: proxy") ||
            msgLower.contains("econnrefused") || msgLower.contains("connection refused") ||
            msgLower.contains("relay error port") || msgLower.contains("failed to connect to /127.0.0.1") ||
            msgLower.contains("tn_fd_link") || msgLower.contains("start_iface")) return
            
        val safeMessage = sanitize(message)
        val entry = LogEntry(System.currentTimeMillis(), level, safeMessage, tag)
        logBuffer.addLast(entry)
        while (logBuffer.size > MAX_ENTRIES) logBuffer.pollFirst()
        _logFlow.tryEmit(entry)

        // Also log to Android logcat
        when (level) {
            LogEntry.LogLevel.DEBUG   -> Log.d(tag, safeMessage)
            LogEntry.LogLevel.INFO    -> Log.i(tag, safeMessage)
            LogEntry.LogLevel.WARNING -> Log.w(tag, safeMessage)
            LogEntry.LogLevel.ERROR   -> Log.e(tag, safeMessage)
        }

    }

    fun info(tag: String, message: String)    = log(message, LogEntry.LogLevel.INFO, tag)
    fun error(tag: String, message: String)   = log(message, LogEntry.LogLevel.ERROR, tag)
    fun warning(tag: String, message: String) = log(message, LogEntry.LogLevel.WARNING, tag)
    fun debug(tag: String, message: String)   = log(message, LogEntry.LogLevel.DEBUG, tag)

    fun getRecentLogs(count: Int = 100): List<LogEntry> =
        logBuffer.toList().takeLast(count)

    fun clearLogs() = logBuffer.clear()

    fun formatEntry(entry: LogEntry): String {
        val time = dateFormat.format(Date(entry.timestamp))
        val levelStr = entry.level.name.padEnd(7)
        return "[$time] $levelStr [${entry.tag}] ${entry.message}"
    }

    fun getAllLogsText(): String =
        logBuffer.joinToString("\n") { formatEntry(it) }
}
