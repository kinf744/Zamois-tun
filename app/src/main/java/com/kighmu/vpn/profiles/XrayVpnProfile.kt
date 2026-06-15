package com.kighmu.vpn.profiles

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class XrayVpnProfile(
    val id: String = UUID.randomUUID().toString(),
    var profileName: String = "",

    // Mode actif : "link" | "json"
    var activeMode: String = "json",

    // Mode lien
    var xrayLink: String = "",
    var xrayLinkJson: String = "",

    // Mode JSON direct
    var xrayJson: String = "",

    // Champs parsés depuis le lien
    var protocol: String = "vmess",
    var serverAddress: String = "",
    var serverPort: Int = 443,
    var uuid: String = "",
    var encryption: String = "auto",

    // Transport : ws | tcp | xhttp | grpc | h2 | kcp | httpupgrade | splithttp
    var transport: String = "ws",
    var wsPath: String = "/",
    var wsHost: String = "",

    // TLS / Reality
    var tls: Boolean = true,
    var sni: String = "",
    var fingerprint: String = "chrome",
    var allowInsecure: Boolean = false,

    // Reality specifique
    var publicKey: String = "",
    var shortId: String = "",

    // gRPC
    var grpcServiceName: String = "",

    // kCP
    var kcpSeed: String = "",
    var kcpHeader: String = "none",

    // VLESS flow (xtls-rprx-vision)
    var flow: String = "",

    // Etat
    var isSelected: Boolean = false
) {
    fun getActiveJson(): String = when (activeMode) {
        "link" -> xrayLinkJson.ifBlank { xrayJson }
        else    -> xrayJson.ifBlank { DEFAULT_JSON }
    }

    fun toJson(): String = Gson().toJson(this)

    companion object {
        const val DEFAULT_JSON = """{
  "log": { "loglevel": "warning" },
  "inbounds": [{ "port": 10808, "protocol": "socks", "settings": { "udp": true } }],
  "outbounds": [{
    "protocol": "vmess",
    "settings": { "vnext": [{ "address": "example.com", "port": 443,
      "users": [{ "id": "your-uuid-here", "alterId": 0 }] }] },
    "streamSettings": { "network": "ws", "security": "tls",
      "wsSettings": { "path": "/" },
      "tlsSettings": { "serverName": "example.com" } }
  }, { "protocol": "freedom", "tag": "direct" }],
  "routing": { "rules": [] }
}"""

        fun fromJson(json: String): XrayVpnProfile =
            Gson().fromJson(json, XrayVpnProfile::class.java)

        fun listFromJson(json: String): MutableList<XrayVpnProfile> {
            val type = object : TypeToken<MutableList<XrayVpnProfile>>() {}.type
            return Gson().fromJson(json, type) ?: mutableListOf()
        }

        fun listToJson(list: List<XrayVpnProfile>): String = Gson().toJson(list)
    }
}
