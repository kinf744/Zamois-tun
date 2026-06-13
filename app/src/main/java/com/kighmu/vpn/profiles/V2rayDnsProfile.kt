package com.kighmu.vpn.profiles

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class V2rayDnsProfile(
    val id: String = UUID.randomUUID().toString(),
    var profileName: String = "",

    // Xray/V2Ray config
    var xrayLink: String = "",
    var xrayJsonConfig: String = "",
    var protocol: String = "vmess",
    var serverAddress: String = "",
    var serverPort: Int = 443,
    var uuid: String = "",
    var encryption: String = "auto",
    var transport: String = "ws",
    var wsPath: String = "/",
    var wsHost: String = "",
    var tls: Boolean = true,
    var sni: String = "",
    var allowInsecure: Boolean = false,

    // SlowDNS config
    var dnsServer: String = "8.8.8.8",
    var dnsPort: Int = 53,
    var nameserver: String = "",
    var publicKey: String = "",

    // Tunnels parallèles
    var tunnelCount: Int = 1,
    // État
    var isSelected: Boolean = false
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): V2rayDnsProfile =
            Gson().fromJson(json, V2rayDnsProfile::class.java)

        fun listFromJson(json: String): MutableList<V2rayDnsProfile> {
            val type = object : TypeToken<MutableList<V2rayDnsProfile>>() {}.type
            return Gson().fromJson(json, type) ?: mutableListOf()
        }

        fun listToJson(list: List<V2rayDnsProfile>): String =
            Gson().toJson(list)
    }
}
