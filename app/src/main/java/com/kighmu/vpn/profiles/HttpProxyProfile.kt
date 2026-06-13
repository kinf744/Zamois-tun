package com.kighmu.vpn.profiles

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Profil HTTP Proxy + Payload pour le multi-profil.
 * Chaque profil contient les informations SSH + proxy + payload.
 */
data class HttpProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    var profileName: String = "",
    // SSH
    var sshHost: String = "",
    var sshPort: Int = 22,
    var sshUser: String = "",
    var sshPass: String = "",
    // Proxy
    var proxyHost: String = "",
    var proxyPort: Int = 8080,
    var customPayload: String = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Keep-Alive[crlf]Upgrade: websocket[crlf][crlf]",
    // État
    var isSelected: Boolean = false
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): HttpProxyProfile =
            Gson().fromJson(json, HttpProxyProfile::class.java)

        fun listFromJson(json: String): MutableList<HttpProxyProfile> {
            val type = object : TypeToken<MutableList<HttpProxyProfile>>() {}.type
            return Gson().fromJson(json, type) ?: mutableListOf()
        }

        fun listToJson(list: List<HttpProxyProfile>): String = Gson().toJson(list)
    }
}
