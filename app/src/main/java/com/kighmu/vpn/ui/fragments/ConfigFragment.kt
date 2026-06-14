package com.kighmu.vpn.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kighmu.vpn.R
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.kighmu.vpn.ui.adapters.SlowDnsProfileAdapter
import com.kighmu.vpn.profiles.V2rayDnsProfileRepository
import com.kighmu.vpn.ui.adapters.V2rayDnsProfileAdapter
import com.kighmu.vpn.ui.dialogs.V2rayDnsProfileEditDialog
import com.kighmu.vpn.profiles.SlowDnsProfile
import com.kighmu.vpn.profiles.ProfileRepository
import com.kighmu.vpn.ui.MainViewModel
import com.kighmu.vpn.models.*


class ConfigFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var slowDnsProfileAdapter: SlowDnsProfileAdapter? = null
    private var parsedJsonFromLink: String = ""
    private val dnsProfiles: MutableList<com.kighmu.vpn.profiles.SlowDnsProfile> = mutableListOf()
    private var parsedJsonFromV2dnsLink: String = ""
    private var currentTab = 0
    private lateinit var profileRepo: ProfileRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        profileRepo = ProfileRepository(requireContext())

        val tabs = listOf(
            view.findViewById<Button>(R.id.tab_slowdns),
            view.findViewById<Button>(R.id.tab_http),
            view.findViewById<Button>(R.id.tab_ssl),
            view.findViewById<Button>(R.id.tab_xray),
            view.findViewById<Button>(R.id.tab_v2dns),
            view.findViewById<Button>(R.id.tab_hysteria),
            view.findViewById<Button>(R.id.tab_zivpn),
        )

        val panels = listOf(
            view.findViewById<LinearLayout>(R.id.panel_slowdns),
            view.findViewById<LinearLayout>(R.id.panel_http),
            view.findViewById<LinearLayout>(R.id.panel_ssl),
            view.findViewById<LinearLayout>(R.id.panel_xray),
            view.findViewById<LinearLayout>(R.id.panel_v2dns),
            view.findViewById<LinearLayout>(R.id.panel_hysteria),
            view.findViewById<LinearLayout>(R.id.section_zivpn),
        )

        fun selectTab(index: Int) {
            currentTab = index
            val xray = com.kighmu.vpn.models.XrayConfig.migrate(viewModel.config.value.xray)
            val rgXray = view.findViewById<android.widget.RadioGroup>(R.id.rg_xray_mode)
            val pLink = view.findViewById<android.view.View>(R.id.panel_xray_link)
            val pJson = view.findViewById<android.view.View>(R.id.panel_xray_json)
            val etJson = view.findViewById<EditText>(R.id.et_xray_json)
            val etLink = view.findViewById<EditText>(R.id.et_xray_link)

            if (index == 4) {
                // Charger chaque champ depuis son propre stockage - aucune interférence
                etJson.setText(xray.jsonConfig2.json)
                etLink.setText(xray.linkConfig.link)

                rgXray.setOnCheckedChangeListener(null)
                when (xray.activeMode) {
                    "link" -> {
                        rgXray.check(R.id.rb_xray_link)
                        pLink.visibility = android.view.View.VISIBLE
                        pJson.visibility = android.view.View.GONE
                    }
                    else -> {
                        rgXray.check(R.id.rb_xray_json)
                        pJson.visibility = android.view.View.VISIBLE
                        pLink.visibility = android.view.View.GONE
                    }
                }
                // Listener : changer de mode ne touche PAS aux données de l'autre mode
                rgXray.setOnCheckedChangeListener { _, id ->
                    view.findViewById<android.widget.TextView>(R.id.tv_xray_mode_warning).visibility = android.view.View.GONE
                    when (id) {
                        R.id.rb_xray_link -> {
                            pLink.visibility = android.view.View.VISIBLE
                            pJson.visibility = android.view.View.GONE
                        }
                        R.id.rb_xray_json -> {
                            pJson.visibility = android.view.View.VISIBLE
                            pLink.visibility = android.view.View.GONE
                        }
                    }
                }
            } else if (index == 5) {
                val v2dnsJson = viewModel.config.value.v2dns.jsonConfig.ifBlank {
                    viewModel.config.value.xray.v2dnsJsonConfig
                }
                view.findViewById<android.widget.EditText>(R.id.et_v2dns_json).setText(v2dnsJson)
            }
            tabs.forEachIndexed { i, btn ->
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (i == index) 0xFF2196F3.toInt() else 0xFF333344.toInt()
                )
            }
            panels.forEachIndexed { i, panel ->
                panel.visibility = if (i == index) View.VISIBLE else View.GONE
            }
        }

        tabs.forEachIndexed { index, btn -> btn.setOnClickListener { selectTab(index) } }

        // Parse Xray link
        view.findViewById<Button>(R.id.btn_parse_link).setOnClickListener {
            val link = view.findViewById<EditText>(R.id.et_xray_link).text.toString()
            val statusView = view.findViewById<TextView>(R.id.tv_link_status)
            val json = parseLinkToJson(link)
            if (json != null) {
                parsedJsonFromLink = json
                // Sauvegarder immédiatement pour persistance
                saveConfig(view)
                statusView.text = "✓ Lien valide - config sauvegardée"
                statusView.setTextColor(0xFF00C853.toInt())
            } else {
                statusView.text = "❌ Format invalide"
                statusView.setTextColor(0xFFFF5252.toInt())
            }
        }

        // JSON buttons
        view.findViewById<Button>(R.id.btn_paste_json).setOnClickListener {
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            view.findViewById<EditText>(R.id.et_xray_json).setText(text)
        }
        view.findViewById<Button>(R.id.btn_copy_json).setOnClickListener {
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = view.findViewById<EditText>(R.id.et_xray_json).text.toString()
            cm.setPrimaryClip(android.content.ClipData.newPlainText("json", text))
            Toast.makeText(requireContext(), "Copié!", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.btn_format_json).setOnClickListener {
            try {
                val text = view.findViewById<EditText>(R.id.et_xray_json).text.toString()
                val formatted = org.json.JSONObject(text).toString(2)
                view.findViewById<EditText>(R.id.et_xray_json).setText(formatted)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "JSON invalide", Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<Button>(R.id.btn_select_all_json).setOnClickListener {
            view.findViewById<EditText>(R.id.et_xray_json).selectAll()
        }

        // V2DNS link parsing a été supprimé ou déplacé

        // SlowDNS profiles
        val rv = view.findViewById<RecyclerView>(R.id.rv_dns_profiles)
        rv.layoutManager = LinearLayoutManager(requireContext())
        dnsProfiles.clear(); dnsProfiles.addAll(profileRepo.getAll())
        slowDnsProfileAdapter = SlowDnsProfileAdapter(
            dnsProfiles,
            onSelectionChanged = { _, _ -> },
            onEdit = { p -> showAddProfileDialog(p) },
            onDelete = { p -> dnsProfiles.remove(p); slowDnsProfileAdapter?.notifyDataSetChanged(); saveConfig(view) },
            onClone = { p -> dnsProfiles.add(p.copy(id = java.util.UUID.randomUUID().toString())); slowDnsProfileAdapter?.notifyDataSetChanged(); saveConfig(view) }
        )
        rv.adapter = slowDnsProfileAdapter

        view.findViewById<Button>(R.id.btn_add_dns_profile).setOnClickListener {
            showAddProfileDialog()
        }

        // V2ray+DNS profiles
        setupV2rayDnsProfiles(view)
        // HTTP Proxy multi-profil
        setupHttpProxyProfiles(view)
        // Hysteria multi-profil
        setupHysteriaProfiles(view)
        // ZIVPN multi-profil
        setupZivpnProfiles(view)

        view.findViewById<Button>(R.id.btn_save_config).setOnClickListener { saveConfig(view) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.config.collect { c -> loadConfig(view, c) }
        }

        selectTab(currentTab)
    }

    private fun loadConfig(view: View, c: com.kighmu.vpn.models.KighmuConfig) {
        // Verrouiller config si lockAllConfig = true
        val isLocked = c.exportConfig?.lockAllConfig == true
        applyConfigLock(view, isLocked)
        // Recharger profils SlowDNS
        val savedProfiles = profileRepo.getAll()
        if (savedProfiles.isNotEmpty() || dnsProfiles.isEmpty()) {
            dnsProfiles.clear()
            dnsProfiles.addAll(savedProfiles)
            slowDnsProfileAdapter?.notifyDataSetChanged()
        }
        // HTTP
        view.findViewById<EditText>(R.id.et_http_ssh_host).setText(c.httpProxy.sshHost)
        view.findViewById<EditText>(R.id.et_http_ssh_port).setText(c.httpProxy.sshPort.toString())
        view.findViewById<EditText>(R.id.et_http_ssh_user).setText(c.httpProxy.sshUser)
        view.findViewById<EditText>(R.id.et_http_ssh_pass).setText(c.httpProxy.sshPass)
        view.findViewById<EditText>(R.id.et_proxy_host).setText(c.httpProxy.proxyHost)
        view.findViewById<EditText>(R.id.et_proxy_port).setText(c.httpProxy.proxyPort.toString())
        view.findViewById<EditText>(R.id.et_payload).setText(c.httpProxy.customPayload)

        // SSL
        view.findViewById<EditText>(R.id.et_ssl_ssh_host).setText(c.sshSsl.sshHost)
        view.findViewById<EditText>(R.id.et_ssl_ssh_port).setText(c.sshSsl.sshPort.toString())
        view.findViewById<EditText>(R.id.et_ssl_ssh_user).setText(c.sshSsl.sshUser)
        view.findViewById<EditText>(R.id.et_ssl_ssh_pass).setText(c.sshSsl.sshPass)
        view.findViewById<EditText>(R.id.et_sni).setText(c.sshSsl.sni)
        // Xray - initialiser les variables mémoire depuis la config sauvegardée
        // Xray - chaque mode charge uniquement ses propres données
        val xrayMigrated = com.kighmu.vpn.models.XrayConfig.migrate(c.xray)
        if (parsedJsonFromV2dnsLink.isBlank()) parsedJsonFromV2dnsLink = c.v2dns.jsonConfig.ifBlank { xrayMigrated.v2dnsJsonConfig }
        if (currentTab == 5) {
            view.findViewById<EditText>(R.id.et_v2dns_json).setText(c.v2dns.jsonConfig.ifBlank { xrayMigrated.v2dnsJsonConfig })
        } else if (currentTab == 4) {
            // Mode lien : charger uniquement linkConfig
            // Mode json : charger uniquement jsonConfig2
            // Les deux champs sont toujours chargés mais chacun depuis sa propre source
            view.findViewById<EditText>(R.id.et_xray_json).setText(xrayMigrated.jsonConfig2.json)
            view.findViewById<EditText>(R.id.et_xray_link).setText(xrayMigrated.linkConfig.link)
        }
        val rgRestore = view.findViewById<android.widget.RadioGroup>(R.id.rg_xray_mode)
        val pLink = view.findViewById<android.view.View>(R.id.panel_xray_link)
        val pJson = view.findViewById<android.view.View>(R.id.panel_xray_json)
        rgRestore.setOnCheckedChangeListener(null)
        when (xrayMigrated.activeMode) {
            "link" -> { rgRestore.check(R.id.rb_xray_link); pLink.visibility = View.VISIBLE; pJson.visibility = View.GONE }
            else   -> { rgRestore.check(R.id.rb_xray_json); pJson.visibility = View.VISIBLE; pLink.visibility = View.GONE }
        }

        view.findViewById<EditText>(R.id.et_zivpn_host).setText(c.zivpnHost)
        view.findViewById<EditText>(R.id.et_zivpn_port).setText(c.zivpnPort)
        view.findViewById<EditText>(R.id.et_zivpn_password).setText(c.zivpnPassword)
        view.findViewById<EditText>(R.id.et_hys_auth).setText(c.hysteria.authPassword)
        view.findViewById<EditText>(R.id.et_hys_upload).setText(c.hysteria.uploadMbps.toString())
        view.findViewById<EditText>(R.id.et_hys_download).setText(c.hysteria.downloadMbps.toString())
        view.findViewById<EditText>(R.id.et_hys_obfs).setText(c.hysteria.obfsPassword)
        view.findViewById<EditText>(R.id.et_hys_port_hopping).setText(c.hysteria.portHopping)

        // Tab
        val tabIndex = when (c.tunnelMode) {
            com.kighmu.vpn.models.TunnelMode.SLOW_DNS -> 0
            com.kighmu.vpn.models.TunnelMode.HTTP_PROXY -> 1
            com.kighmu.vpn.models.TunnelMode.SSH_SSL_TLS -> 2
            com.kighmu.vpn.models.TunnelMode.V2RAY_XRAY -> 3
            com.kighmu.vpn.models.TunnelMode.V2RAY_SLOWDNS -> 4
            com.kighmu.vpn.models.TunnelMode.HYSTERIA_UDP -> 5
            com.kighmu.vpn.models.TunnelMode.ZIVPN_UDP -> 6
        }
        currentTab = tabIndex
    }

    private fun saveConfig(view: View) {
        val c = viewModel.config.value

        val http = c.httpProxy.copy(
            sshHost = view.findViewById<EditText>(R.id.et_http_ssh_host).text.toString(),
            sshPort = view.findViewById<EditText>(R.id.et_http_ssh_port).text.toString().toIntOrNull() ?: 22,
            sshUser = view.findViewById<EditText>(R.id.et_http_ssh_user).text.toString(),
            sshPass = view.findViewById<EditText>(R.id.et_http_ssh_pass).text.toString(),
            proxyHost = view.findViewById<EditText>(R.id.et_proxy_host).text.toString(),
            proxyPort = view.findViewById<EditText>(R.id.et_proxy_port).text.toString().toIntOrNull() ?: 8080,
            customPayload = view.findViewById<EditText>(R.id.et_payload).text.toString()
        )

        val ssl = c.sshSsl.copy(
            sshHost = view.findViewById<EditText>(R.id.et_ssl_ssh_host).text.toString(),
            sshPort = view.findViewById<EditText>(R.id.et_ssl_ssh_port).text.toString().toIntOrNull() ?: 22,
            sshUser = view.findViewById<EditText>(R.id.et_ssl_ssh_user).text.toString(),
            sshPass = view.findViewById<EditText>(R.id.et_ssl_ssh_pass).text.toString(),
            sni = view.findViewById<EditText>(R.id.et_sni).text.toString()
        )

        val dns = c.slowDns

        val rgXray = view.findViewById<android.widget.RadioGroup>(R.id.rg_xray_mode)
        val tvXrayWarning = view.findViewById<android.widget.TextView>(R.id.tv_xray_mode_warning)
        if (currentTab == 4 && rgXray.checkedRadioButtonId == -1) {
            tvXrayWarning.visibility = View.VISIBLE
            return
        }

        val xray = if (currentTab == 5) {
            // V2DNS totalement indépendant - pas de double sauvegarde
            val v2dnsJson = view.findViewById<android.widget.EditText>(R.id.et_v2dns_json).text.toString()
            val finalV2dns = v2dnsJson.ifBlank { c.v2dns.jsonConfig }
            // v2dns sera inclus dans le saveConfig final plus bas
            c.xray.copy(v2dnsJsonConfig = finalV2dns)
        } else if (currentTab == 3) {
            // Onglet V2RAY_XRAY actif: lire les champs depuis l UI
            val rgXray = view.findViewById<android.widget.RadioGroup>(R.id.rg_xray_mode)
            val activeMode = when (rgXray.checkedRadioButtonId) {
                R.id.rb_xray_link -> "link"
                R.id.rb_xray_json -> "json"
                else -> c.xray.activeMode.ifBlank { "json" }
            }
            val rawJson = view.findViewById<android.widget.EditText>(R.id.et_xray_json).text.toString()
            val rawLink = view.findViewById<android.widget.EditText>(R.id.et_xray_link).text.toString()
            val parsedJson = if (parsedJsonFromLink.isNotBlank()) parsedJsonFromLink else c.xray.linkConfig.parsedJson
            com.kighmu.vpn.models.XrayConfig.migrate(c.xray).copy(
                activeMode = activeMode,
                jsonConfig2 = com.kighmu.vpn.models.XrayJsonConfig(
                    json = rawJson.ifBlank { c.xray.jsonConfig2.json }
                ),
                linkConfig = com.kighmu.vpn.models.XrayLinkConfig(
                    link = rawLink.ifBlank { c.xray.linkConfig.link },
                    parsedJson = parsedJson
                ),
                inputMode = activeMode,
                jsonConfig = rawJson.ifBlank { c.xray.jsonConfig },
                xrayLink = rawLink.ifBlank { c.xray.xrayLink },
                xrayLinkJson = parsedJson.ifBlank { c.xray.xrayLinkJson }
            )
        } else {
            // Autre onglet actif: preserver integralement la config Xray existante sans toucher
            com.kighmu.vpn.models.XrayConfig.migrate(c.xray)
        }
        val hys = c.hysteria.copy(
            serverAddress = view.findViewById<EditText>(R.id.et_hys_host).text.toString(),
            authPassword = view.findViewById<EditText>(R.id.et_hys_auth).text.toString(),
            uploadMbps = view.findViewById<EditText>(R.id.et_hys_upload).text.toString().toIntOrNull() ?: 10,
            downloadMbps = view.findViewById<EditText>(R.id.et_hys_download).text.toString().toIntOrNull() ?: 50,
            obfsPassword = view.findViewById<EditText>(R.id.et_hys_obfs).text.toString(),
            portHopping = view.findViewById<EditText>(R.id.et_hys_port_hopping).text.toString().ifBlank { "20000-50000" },
        )
        val newTunnelMode = when (currentTab) {
            0 -> com.kighmu.vpn.models.TunnelMode.SLOW_DNS
            1 -> com.kighmu.vpn.models.TunnelMode.HTTP_PROXY
            2 -> com.kighmu.vpn.models.TunnelMode.SSH_SSL_TLS
            3 -> com.kighmu.vpn.models.TunnelMode.V2RAY_XRAY
            4 -> com.kighmu.vpn.models.TunnelMode.V2RAY_SLOWDNS
            5 -> com.kighmu.vpn.models.TunnelMode.HYSTERIA_UDP
            6 -> com.kighmu.vpn.models.TunnelMode.ZIVPN_UDP
            else -> c.tunnelMode
        }
        // Validation JSON avant sauvegarde
        val xrayJsonToValidate = when {
            currentTab == 5 -> xray.v2dnsJsonConfig
            xray.activeMode == "link" -> xray.linkConfig.parsedJson
            else -> xray.jsonConfig2.json
        }
        if (xrayJsonToValidate.isNotBlank()) {
            try {
                org.json.JSONObject(xrayJsonToValidate)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "❌ JSON invalide : ${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                return
            }
        }

        // Persister les profils SlowDNS
        profileRepo.save(dnsProfiles)
        viewModel.saveConfig(c.copy(
            tunnelMode = newTunnelMode,
            httpProxy = http,
            sshSsl = ssl,
            slowDns = dns,
            xray = xray,
            v2dns = if (currentTab == 5) com.kighmu.vpn.models.V2dnsConfig(jsonConfig = xray.v2dnsJsonConfig) else c.v2dns,
            hysteria = hys,
            zivpnHost = view.findViewById<EditText>(R.id.et_zivpn_host).text.toString(),
            zivpnPort = view.findViewById<EditText>(R.id.et_zivpn_port).text.toString(),
            zivpnPassword = view.findViewById<EditText>(R.id.et_zivpn_password).text.toString(),
            zivpnObfs = "hu``hqb`c",
            slowDnsProfiles = dnsProfiles.map { p -> com.kighmu.vpn.models.SlowDnsConfig(dnsServer = p.dnsServer, nameserver = p.nameserver, publicKey = p.publicKey, sshHost = p.sshHost, sshPort = p.sshPort, sshUser = p.sshUser, sshPass = p.sshPass) }.toMutableList()
        ))
        Toast.makeText(requireContext(), "Config saved!", Toast.LENGTH_SHORT).show()
    }

    private fun parseLinkToJson(link: String): String? {
        return when {
            link.startsWith("vmess://") -> parseVmess(link)
            link.startsWith("vless://") -> parseVless(link)
            link.startsWith("trojan://") -> parseTrojan(link)
            else -> null
        }
    }

    private fun buildStreamSettings(
        network: String,
        security: String,
        sni: String,
        wsPath: String,
        wsHost: String,
        fp: String = "",
        pbk: String = "",
        sid: String = "",
        grpcService: String = "",
        isReality: Boolean = false
    ): String {
        val tlsPart = when {
            isReality -> "\"realitySettings\":{\"serverName\":\"$sni\",\"fingerprint\":\"${fp.ifEmpty{"chrome"}}\",\"publicKey\":\"$pbk\",\"shortId\":\"$sid\"}"
            security == "tls" -> {
                val fpPart = if (fp.isNotEmpty()) ",\"fingerprint\":\"$fp\"" else ""
                "\"tlsSettings\":{\"serverName\":\"$sni\",\"allowInsecure\":false$fpPart}"
            }
            else -> ""
        }
        return when (network) {
            "ws" -> buildStreamJson(network, security, tlsPart, "wsSettings", """{"path":"$wsPath","headers":{"Host":"$wsHost"}}""")
            "grpc" -> buildStreamJson(network, security, tlsPart, "grpcSettings", """{"serviceName":"$grpcService"}""")
            "xhttp", "splithttp" -> buildStreamJson(network, security, tlsPart, "xhttpSettings", """{"path":"$wsPath","host":"$wsHost","mode":"auto"}""")
            "h2", "http" -> buildStreamJson("h2", security, tlsPart, "httpSettings", """{"path":"$wsPath","host":["$wsHost"]}""")
            "httpupgrade" -> buildStreamJson(network, security, tlsPart, "httpupgradeSettings", """{"path":"$wsPath","host":"$wsHost"}""")
            "kcp", "mkcp" -> buildStreamJson("kcp", security, tlsPart, "kcpSettings", """{"mtu":1350,"tti":20,"uplinkCapacity":5,"downlinkCapacity":20,"congestion":false,"readBufferSize":2,"writeBufferSize":2,"header":{"type":"none"}}""")
            else -> buildStreamJson("tcp", security, tlsPart, null, null)
        }
    }

    private fun buildStreamJson(network: String, security: String, tlsPart: String, settingsKey: String?, settingsValue: String?): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"network\":\"$network\"")
        sb.append(",\"security\":\"$security\"")
        if (tlsPart.isNotEmpty()) sb.append(",$tlsPart")
        if (settingsKey != null && settingsValue != null) sb.append(",\"$settingsKey\":$settingsValue")
        sb.append("}")
        return "\"streamSettings\":$sb"
    }

    private fun parseVless(link: String): String {
        val uri = java.net.URI(link)
        val uuid = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = uri.port.takeIf { it > 0 } ?: 443
        val params = (uri.rawQuery ?: "").split("&").associate {
            val idx = it.indexOf("=")
            if (idx < 0) it to "" else it.substring(0, idx) to java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
        }
        val type = params["type"] ?: "tcp"
        val security = params["security"] ?: "none"
        val sni = params["sni"] ?: params["host"] ?: host
        val wsPath = params["path"] ?: "/"
        val wsHost = params["host"] ?: host
        val fp = params["fp"] ?: ""
        val pbk = params["pbk"] ?: ""
        val sid = params["sid"] ?: ""
        val flow = params["flow"] ?: ""
        val grpcService = params["serviceName"] ?: params["path"] ?: ""
        val isReality = security == "reality"
        val streamSettings = buildStreamSettings(type, if (isReality) "reality" else security, sni, wsPath, wsHost, fp, pbk, sid, grpcService, isReality)
        val flowPart = if (flow.isNotEmpty()) ""","flow":"$flow"""" else ""
        return """{
  "log":{"loglevel":"warning"},
  "inbounds":[{"port":10808,"protocol":"socks","settings":{"udp":true}}],
  "outbounds":[{
    "protocol":"vless",
    "settings":{"vnext":[{"address":"$host","port":$port,"users":[{"id":"$uuid","encryption":"none"$flowPart}]}]},
    $streamSettings
  },{"protocol":"freedom","tag":"direct"}],
  "routing":{"rules":[]}
}"""
    }

    private fun parseVmess(link: String): String {
        val b64 = link.removePrefix("vmess://")
        val json = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        val obj = org.json.JSONObject(json)
        val host = obj.optString("add", "")
        val port = obj.optInt("port", 443)
        val uuid = obj.optString("id", "")
        val alterId = obj.optInt("aid", 0)
        val net = obj.optString("net", "tcp")
        val tls = obj.optString("tls", "")
        val sni = obj.optString("sni", host)
        val wsPath = java.net.URLDecoder.decode(obj.optString("path", "/"), "UTF-8")
        val wsHost = obj.optString("host", host)
        val grpcService = obj.optString("path", "")
        val security = if (tls == "tls") "tls" else "none"
        val streamSettings = buildStreamSettings(net, security, sni, wsPath, wsHost, grpcService = grpcService)
        return """{
  "log":{"loglevel":"warning"},
  "inbounds":[{"port":10808,"protocol":"socks","settings":{"udp":true}}],
  "outbounds":[{
    "protocol":"vmess",
    "settings":{"vnext":[{"address":"$host","port":$port,"users":[{"id":"$uuid","alterId":$alterId,"security":"auto"}]}]},
    $streamSettings
  },{"protocol":"freedom","tag":"direct"}],
  "routing":{"rules":[]}
}"""
    }

    private fun parseTrojan(link: String): String {
        val uri = java.net.URI(link)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = uri.port.takeIf { it > 0 } ?: 443
        val params = (uri.rawQuery ?: "").split("&").associate {
            val idx = it.indexOf("=")
            if (idx < 0) it to "" else it.substring(0, idx) to java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
        }
        val sni = params["sni"] ?: host
        val type = params["type"] ?: "tcp"
        val security = params["security"] ?: "tls"
        val wsPath = params["path"] ?: "/"
        val wsHost = params["host"] ?: host
        val fp = params["fp"] ?: ""
        val grpcService = params["serviceName"] ?: params["path"] ?: ""
        val streamSettings = buildStreamSettings(type, security, sni, wsPath, wsHost, fp, grpcService = grpcService)
        return """{
  "log":{"loglevel":"warning"},
  "inbounds":[{"port":10808,"protocol":"socks","settings":{"udp":true}}],
  "outbounds":[{
    "protocol":"trojan",
    "settings":{"servers":[{"address":"$host","port":$port,"password":"$password"}]},
    $streamSettings
  },{"protocol":"freedom","tag":"direct"}],
  "routing":{"rules":[]}
}"""
    }
    private fun applyConfigLock(view: View, locked: Boolean) {
        // Propager l'état locked à tous les adapters
        slowDnsProfileAdapter?.setLocked(locked)
        if (!locked) return
        // Désactiver seulement les EditText - pas les boutons de navigation
        fun lockView(v: android.view.View) {
            if (v is android.widget.EditText) {
                v.isEnabled = false
                v.setText("••••••••")
                v.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) lockView(v.getChildAt(i))
            }
        }
        val content = view.findViewById<android.view.ViewGroup>(R.id.config_content)
        lockView(content)
        // Garder le bouton save visible mais désactivé
        val btnSave = view.findViewById<android.widget.Button>(R.id.btn_save_config)
        btnSave.isEnabled = !locked
        if (locked) {
            btnSave.text = "🔒 CONFIG VERROUILLÉE"
            btnSave.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())

            // Bloquer tous les boutons d ajout/modification de profils
            val addButtons = listOf(
                R.id.btn_add_dns_profile,
                R.id.btn_add_http_proxy_profile,
                R.id.btn_add_hysteria_profile,
                R.id.btn_add_v2dns_profile,
                R.id.btn_add_zivpn_profile
            )
            addButtons.forEach { btnId ->
                try {
                    val btn = view.findViewById<android.widget.Button>(btnId)
                    btn?.isEnabled = false
                    btn?.alpha = 0.4f
                } catch (_: Exception) {}
            }
        }
    }

    private var pendingProfileEdit: com.kighmu.vpn.profiles.SlowDnsProfile? = null

    private fun showAddProfileDialog(existing: com.kighmu.vpn.profiles.SlowDnsProfile? = null) {
        pendingProfileEdit = existing
        val intent = android.content.Intent(requireContext(), com.kighmu.vpn.ui.activities.ProfileEditActivity::class.java)
        if (existing != null) intent.putExtra(com.kighmu.vpn.ui.activities.ProfileEditActivity.EXTRA_PROFILE, com.google.gson.Gson().toJson(existing))
        startActivityForResult(intent, com.kighmu.vpn.ui.activities.ProfileEditActivity.REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == com.kighmu.vpn.ui.activities.ProfileEditActivity.REQUEST_CODE
            && resultCode == android.app.Activity.RESULT_OK) {
            val json = data?.getStringExtra(com.kighmu.vpn.ui.activities.ProfileEditActivity.EXTRA_RESULT) ?: return
            val updated = com.google.gson.Gson().fromJson(json, com.kighmu.vpn.profiles.SlowDnsProfile::class.java)
            val existing = pendingProfileEdit
            if (existing == null) {
                dnsProfiles.add(updated)
            } else {
                val idx = dnsProfiles.indexOfFirst { it.id == existing.id }
                if (idx >= 0) dnsProfiles[idx] = updated
            }
            pendingProfileEdit = null
            slowDnsProfileAdapter?.notifyDataSetChanged()
            view?.let { saveConfig(it) }
        }
    }

    private fun setupHttpProxyProfiles(view: View) {
        val repo = com.kighmu.vpn.profiles.HttpProxyProfileRepository(requireContext())
        lateinit var adapter: com.kighmu.vpn.ui.adapters.HttpProxyProfileAdapter
        adapter = com.kighmu.vpn.ui.adapters.HttpProxyProfileAdapter(
            repo.getAll(),
            onSelectionChanged = { id, selected -> repo.updateSelection(id, selected) },
            onEdit = { profile ->
                com.kighmu.vpn.ui.dialogs.HttpProxyProfileEditDialog.show(requireContext(), profile) { updated ->
                    repo.update(updated)
                    adapter.setProfiles(repo.getAll())
                }
            },
            onDelete = { profile ->
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Supprimer profil")
                    .setMessage("Supprimer '${profile.profileName}' ?")
                    .setPositiveButton("Supprimer") { _, _ -> repo.delete(profile.id); adapter.setProfiles(repo.getAll()) }
                    .setNegativeButton("Annuler", null).show()
            },
            onClone = { profile -> repo.clone(profile.id); adapter.setProfiles(repo.getAll()) }
        )
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_http_proxy_profiles)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rv.adapter = adapter
        view.findViewById<android.widget.Button>(R.id.btn_add_http_proxy_profile).setOnClickListener {
            com.kighmu.vpn.ui.dialogs.HttpProxyProfileEditDialog.show(requireContext()) { newProfile ->
                repo.add(newProfile)
                adapter.setProfiles(repo.getAll())
            }
        }
    }

    private fun setupHysteriaProfiles(view: View) {
        val repo = com.kighmu.vpn.profiles.HysteriaProfileRepository(requireContext())
        lateinit var adapter: com.kighmu.vpn.ui.adapters.HysteriaProfileAdapter
        adapter = com.kighmu.vpn.ui.adapters.HysteriaProfileAdapter(
            repo.getAll(),
            onSelectionChanged = { id, selected -> repo.updateSelection(id, selected) },
            onEdit = { profile ->
                com.kighmu.vpn.ui.dialogs.HysteriaProfileEditDialog.show(requireContext(), profile) { updated ->
                    repo.update(updated)
                    adapter.setProfiles(repo.getAll())
                }
            },
            onDelete = { profile ->
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Supprimer profil")
                    .setMessage("Supprimer '${profile.profileName}' ?")
                    .setPositiveButton("Supprimer") { _, _ -> repo.delete(profile.id); adapter.setProfiles(repo.getAll()) }
                    .setNegativeButton("Annuler", null).show()
            },
            onClone = { profile -> repo.clone(profile.id); adapter.setProfiles(repo.getAll()) }
        )
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_hysteria_profiles)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rv.adapter = adapter
        view.findViewById<android.widget.Button>(R.id.btn_add_hysteria_profile).setOnClickListener {
            com.kighmu.vpn.ui.dialogs.HysteriaProfileEditDialog.show(requireContext()) { newProfile ->
                repo.add(newProfile)
                adapter.setProfiles(repo.getAll())
            }
        }
    }

    private fun setupZivpnProfiles(view: View) {
        val repo = com.kighmu.vpn.profiles.ZivpnProfileRepository(requireContext())
        lateinit var adapter: com.kighmu.vpn.ui.adapters.ZivpnProfileAdapter
        adapter = com.kighmu.vpn.ui.adapters.ZivpnProfileAdapter(
            repo.getAll(),
            onSelectionChanged = { id, selected -> repo.updateSelection(id, selected) },
            onEdit = { profile ->
                com.kighmu.vpn.ui.dialogs.ZivpnProfileEditDialog.show(requireContext(), profile) { updated ->
                    repo.update(updated)
                    adapter.setProfiles(repo.getAll())
                }
            },
            onDelete = { profile ->
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Supprimer profil")
                    .setMessage("Supprimer '${profile.profileName}' ?")
                    .setPositiveButton("Supprimer") { _, _ -> repo.delete(profile.id); adapter.setProfiles(repo.getAll()) }
                    .setNegativeButton("Annuler", null).show()
            },
            onClone = { profile -> repo.clone(profile.id); adapter.setProfiles(repo.getAll()) }
        )
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_zivpn_profiles)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rv.adapter = adapter
        view.findViewById<android.widget.Button>(R.id.btn_add_zivpn_profile).setOnClickListener {
            com.kighmu.vpn.ui.dialogs.ZivpnProfileEditDialog.show(requireContext()) { newProfile ->
                repo.add(newProfile)
                adapter.setProfiles(repo.getAll())
            }
        }
    }

    private fun setupV2rayDnsProfiles(view: View) {
        val v2dnsRepo = com.kighmu.vpn.profiles.V2rayDnsProfileRepository(requireContext())
        val profiles = v2dnsRepo.getAll().toMutableList()
        
        lateinit var adapter: com.kighmu.vpn.ui.adapters.V2rayDnsProfileAdapter
        adapter = com.kighmu.vpn.ui.adapters.V2rayDnsProfileAdapter(
            profiles,
            onSelectionChanged = { id, selected ->
                v2dnsRepo.updateSelection(id, selected)
            },
            onEdit = { profile ->
                com.kighmu.vpn.ui.dialogs.V2rayDnsProfileEditDialog.show(requireContext(), profile) { updated ->
                    v2dnsRepo.update(updated)
                    adapter.setProfiles(v2dnsRepo.getAll())
                }
            },
            onDelete = { profile ->
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("Supprimer profil")
                    .setMessage("Êtes-vous sûr de vouloir supprimer '${profile.profileName}' ?")
                    .setPositiveButton("Supprimer") { _, _ ->
                        v2dnsRepo.delete(profile.id)
                        adapter.setProfiles(v2dnsRepo.getAll())
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            },
            onClone = { profile ->
                v2dnsRepo.clone(profile.id)
                adapter.setProfiles(v2dnsRepo.getAll())
            }
        )
        
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_v2dns_profiles)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        rv.adapter = adapter
        
        // Bouton pour ajouter un nouveau profil
        val btnAdd = view.findViewById<android.widget.Button>(R.id.btn_add_v2dns_profile)
        btnAdd.setOnClickListener {
            com.kighmu.vpn.ui.dialogs.V2rayDnsProfileEditDialog.show(requireContext()) { newProfile ->
                v2dnsRepo.add(newProfile)
                adapter.setProfiles(v2dnsRepo.getAll())
            }
        }
    }
}
