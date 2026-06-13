package com.kighmu.vpn.profiles

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class SlowDnsProfile(
    val id: String = UUID.randomUUID().toString(),
    var profileName: String = "",
    // SSH
    var sshHost: String = "",
    var sshPort: Int = 22,
    var sshUser: String = "",
    var sshPass: String = "",
    // SlowDNS
    var dnsServer: String = "8.8.8.8",
    var nameserver: String = "",
    var publicKey: String = "",
    // HTTP CONNECT proxy (fallback transport plus stable)
    var proxyHost: String = "127.0.0.1",
    var proxyPort: Int = 22,
    var customPayload: String = "",
    // Tunnels parallèles
    var tunnelCount: Int = 1,
    // État
    var isSelected: Boolean = false
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): SlowDnsProfile = Gson().fromJson(json, SlowDnsProfile::class.java)
        fun listFromJson(json: String): MutableList<SlowDnsProfile> {
            val type = object : TypeToken<MutableList<SlowDnsProfile>>() {}.type
            return Gson().fromJson(json, type) ?: mutableListOf()
        }
        fun listToJson(list: List<SlowDnsProfile>): String = Gson().toJson(list)
    }
}
