package com.kighmu.vpn.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.widget.*
import com.kighmu.vpn.profiles.XrayVpnProfile
import org.json.JSONObject
import android.util.Base64
import java.net.URI

object XrayVpnProfileEditDialog {

    fun show(
        context: Context,
        profile: XrayVpnProfile? = null,
        onSave: (XrayVpnProfile) -> Unit
    ) {
        val isEdit = profile != null
        val p = profile ?: XrayVpnProfile()

        val scroll = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        scroll.addView(layout)

        fun section(title: String) = TextView(context).apply {
            text = title
            setTextColor(0xFF4FC3F7.toInt())
            textSize = 12f
            setPadding(0, 16, 0, 4)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            layout.addView(this)
        }

        fun field(hint: String, value: String, numeric: Boolean = false) = EditText(context).apply {
            this.hint = hint
            setText(value)
            setTextColor(0xFF000000.toInt())
            setHintTextColor(0xFF888888.toInt())
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 }
            layout.addView(this)
        }

        section("PROFIL")
        val etName = field("Nom du profil", p.profileName)

        section("MODE D'INPUT")
        val rgMode = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 }
            layout.addView(this)
        }
        val rbLink = RadioButton(context).apply {
            text = "Lien (vmess/vless/trojan)"
            id = 1001
            setTextColor(0xFFFFFFFF.toInt())
        }
        val rbJson = RadioButton(context).apply {
            text = "JSON direct"
            id = 1002
            setTextColor(0xFFFFFFFF.toInt())
        }
        rgMode.addView(rbLink)
        rgMode.addView(rbJson)

        section("LIEN V2RAY / XRAY")
        val etLink = EditText(context).apply {
            hint = "vmess:// vless:// trojan://"
            setText(p.xrayLink)
            setTextColor(0xFF000000.toInt())
            setHintTextColor(0xFF888888.toInt())
            isSingleLine = false
            setLines(3)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 }
            layout.addView(this)
        }

        section("JSON XRAY DIRECT")
        val etJson = EditText(context).apply {
            hint = "Coller votre config JSON Xray ici"
            setText(if (p.xrayJson.isNotBlank()) p.xrayJson else XrayVpnProfile.DEFAULT_JSON)
            setTextColor(0xFF000000.toInt())
            setHintTextColor(0xFF888888.toInt())
            isSingleLine = false
            setLines(6)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 }
            layout.addView(this)
        }

        fun applyModeVisibility(mode: String) {
            etLink.visibility = if (mode == "link") android.view.View.VISIBLE else android.view.View.GONE
            etJson.visibility = if (mode == "json") android.view.View.VISIBLE else android.view.View.GONE
        }

        rgMode.setOnCheckedChangeListener { _, id ->
            applyModeVisibility(if (id == 1001) "link" else "json")
        }

        if (p.activeMode == "link") { rgMode.check(1001); applyModeVisibility("link") }
        else { rgMode.check(1002); applyModeVisibility("json") }

        AlertDialog.Builder(context)
            .setTitle(if (isEdit) "Modifier Xray VPN" else "Nouveau Xray VPN")
            .setView(scroll)
            .setPositiveButton("Sauvegarder") { _, _ ->
                val activeMode = if (rgMode.checkedRadioButtonId == 1001) "link" else "json"
                val link = etLink.text.toString().trim()
                val json = etJson.text.toString().trim()
                var updated = p.copy(
                    profileName = etName.text.toString().ifEmpty { "Xray VPN" },
                    activeMode  = activeMode,
                    xrayLink    = link,
                    xrayJson    = json
                )
                if (activeMode == "link" && link.isNotBlank()) {
                    updated = parseLinkIntoProfile(link, updated)
                }
                onSave(updated)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun parseLinkIntoProfile(link: String, p: XrayVpnProfile): XrayVpnProfile {
        return try {
            when {
                link.startsWith("vmess://") -> {
                    val b64 = link.removePrefix("vmess://")
                    val json = String(Base64.decode(b64, Base64.DEFAULT))
                    val obj = JSONObject(json)
                    val transport = obj.optString("net", "tcp")
                    val tls = obj.optString("tls", "")
                    val sni = obj.optString("sni", obj.optString("add", ""))
                    val path = java.net.URLDecoder.decode(obj.optString("path", "/"), "UTF-8")
                    val wsHost = obj.optString("host", obj.optString("add", ""))
                    val security = if (tls == "tls") "tls" else "none"
                    val parsedJson = buildVmessJson(obj, transport, security, sni, path, wsHost)
                    p.copy(
                        protocol = "vmess",
                        serverAddress = obj.optString("add", ""),
                        serverPort = obj.optInt("port", 443),
                        uuid = obj.optString("id", ""),
                        encryption = obj.optString("scy", "auto"),
                        transport = transport,
                        wsPath = path,
                        wsHost = wsHost,
                        tls = tls == "tls",
                        sni = sni,
                        xrayLinkJson = parsedJson
                    )
                }
                link.startsWith("vless://") || link.startsWith("trojan://") -> {
                    val uri = URI(link)
                    val proto = if (link.startsWith("vless://")) "vless" else "trojan"
                    val params = (uri.rawQuery ?: "").split("&").associate {
                        val idx = it.indexOf("=")
                        if (idx < 0) it to "" else it.substring(0, idx) to
                            java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
                    }
                    val transport = params["type"] ?: "tcp"
                    val security  = params["security"] ?: "none"
                    val sni       = params["sni"] ?: params["host"] ?: uri.host ?: ""
                    val path      = params["path"] ?: params["serviceName"] ?: "/"
                    val wsHost    = params["host"] ?: sni
                    val fp        = params["fp"] ?: "chrome"
                    val pbk       = params["pbk"] ?: ""
                    val sid       = params["sid"] ?: ""
                    val flow      = params["flow"] ?: ""
                    val parsedJson = buildVlessOrTrojanJson(
                        proto, uri.userInfo ?: "", uri.host ?: "",
                        uri.port.takeIf { it > 0 } ?: 443,
                        transport, security, sni, path, wsHost, fp, pbk, sid, flow
                    )
                    p.copy(
                        protocol = proto,
                        uuid = uri.userInfo ?: "",
                        serverAddress = uri.host ?: "",
                        serverPort = uri.port.takeIf { it > 0 } ?: 443,
                        transport = transport,
                        tls = security == "tls" || security == "reality",
                        sni = sni, wsPath = path, wsHost = wsHost,
                        fingerprint = fp, publicKey = pbk, shortId = sid, flow = flow,
                        allowInsecure = params["allowInsecure"] == "1",
                        xrayLinkJson = parsedJson
                    )
                }
                else -> p
            }
        } catch (e: Exception) { p }
    }

    private fun streamSettings(
        transport: String, security: String, sni: String,
        path: String, host: String, fp: String = "chrome",
        pbk: String = "", sid: String = ""
    ): String {
        val tlsPart = when (security) {
            "tls"     -> ""","tlsSettings":{"serverName":"$sni","fingerprint":"$fp"}"""
            "reality" -> ""","realitySettings":{"serverName":"$sni","fingerprint":"$fp","publicKey":"$pbk","shortId":"$sid"}"""
            else      -> ""
        }
        val net = when (transport) { "mkcp" -> "kcp"; "raw" -> "tcp"; else -> transport }
        val netSettings = when (transport) {
            "ws"          -> ""","wsSettings":{"path":"$path","headers":{"Host":"$host"}}"""
            "xhttp"       -> ""","xhttpSettings":{"path":"$path","host":"$host","mode":"stream-up","scMaxConcurrentPosts":16,"scMinPostsIntervalMs":10,"scMaxEachPostBytes":1000000,"noSSEHeader":true,"xPaddingBytes":"100-1000"}"""
            "splithttp"   -> ""","splithttpSettings":{"path":"$path","host":"$host","mode":"stream-up","scMaxConcurrentPosts":16,"scMinPostsIntervalMs":10,"scMaxEachPostBytes":1000000}"""
            "grpc"        -> ""","grpcSettings":{"serviceName":"$path"}"""
            "h2", "http"  -> ""","httpSettings":{"path":"$path","host":["$host"]}"""
            "httpupgrade" -> ""","httpupgradeSettings":{"path":"$path","host":"$host"}"""
            "kcp", "mkcp" -> ""","kcpSettings":{"mtu":1350,"tti":20,"uplinkCapacity":5,"downlinkCapacity":20,"congestion":false,"readBufferSize":2,"writeBufferSize":2,"header":{"type":"none"},"seed":"$host"}"""
            else          -> ""","tcpSettings":{"header":{"type":"none"}}"""
        }
        return """{"network":"$net","security":"$security"$tlsPart$netSettings}"""
    }

    private fun buildVmessJson(
        obj: org.json.JSONObject, transport: String, security: String,
        sni: String, path: String, wsHost: String
    ): String {
        val host    = obj.optString("add", "")
        val port    = obj.optInt("port", 443)
        val uuid    = obj.optString("id", "")
        val alterId = obj.optInt("aid", 0)
        val stream  = streamSettings(transport, security, sni, path, wsHost)
        return """{"log":{"loglevel":"warning"},"inbounds":[{"port":10808,"protocol":"socks","settings":{"udp":true}}],"outbounds":[{"protocol":"vmess","settings":{"vnext":[{"address":"$host","port":$port,"users":[{"id":"$uuid","alterId":$alterId,"security":"auto"}]}]},"streamSettings":$stream,"mux":{"enabled":false}},{"protocol":"freedom","tag":"direct"}],"routing":{"rules":[]}}"""
    }

    private fun buildVlessOrTrojanJson(
        proto: String, uuid: String, host: String, port: Int,
        transport: String, security: String, sni: String,
        path: String, wsHost: String, fp: String,
        pbk: String, sid: String, flow: String
    ): String {
        val stream = streamSettings(transport, security, sni, path, wsHost, fp, pbk, sid)
        val flowPart = if (flow.isNotEmpty()) ""","flow":"$flow"""" else ""
        val outbound = when (proto) {
            "trojan" -> """{"protocol":"trojan","settings":{"servers":[{"address":"$host","port":$port,"password":"$uuid"}]},"streamSettings":$stream,"mux":{"enabled":false}}"""
            else     -> """{"protocol":"vless","settings":{"vnext":[{"address":"$host","port":$port,"users":[{"id":"$uuid","encryption":"none"$flowPart}]}]},"streamSettings":$stream,"mux":{"enabled":false}}"""
        }
        return """{"log":{"loglevel":"warning"},"inbounds":[{"port":10808,"protocol":"socks","settings":{"udp":true}}],"outbounds":[$outbound,{"protocol":"freedom","tag":"direct"}],"routing":{"rules":[]}}"""
    }

}
