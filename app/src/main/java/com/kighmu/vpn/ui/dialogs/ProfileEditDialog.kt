package com.kighmu.vpn.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.widget.*
import com.kighmu.vpn.profiles.SlowDnsProfile

object ProfileEditDialog {

    fun show(
        context: Context,
        profile: SlowDnsProfile? = null,
        onSave: (SlowDnsProfile) -> Unit
    ) {
        val isEdit = profile != null
        val p = profile ?: SlowDnsProfile()

        val scroll = android.widget.ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        scroll.addView(layout)

        fun label(text: String) {
            layout.addView(TextView(context).apply {
                this.text = text
                setTextColor(0xFF4FC3F7.toInt())
                textSize = 12f
                setPadding(0, 16, 0, 4)
            })
        }

        fun field(hint: String, value: String, inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): EditText {
            val et = EditText(context).apply {
                this.hint = hint
                setText(value)
                this.inputType = inputType
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF888888.toInt())
                setBackgroundColor(0xFF1A1F2E.toInt())
                setPadding(16, 12, 16, 12)
            }
            layout.addView(et)
            layout.addView(Space(context).apply { layoutParams = LinearLayout.LayoutParams(-1, 8) })
            return et
        }

        label("PROFILE")
        val etName = field("Profile Name", p.profileName)

        label("SSH CONFIGURATION")
        val etSshHost = field("SSH Host", p.sshHost)
        val etSshPort = field("SSH Port", p.sshPort.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        val etSshUser = field("Username", p.sshUser)
        val etSshPass = field("Password", p.sshPass, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)

        label("SLOWDNS CONFIGURATION")
        val etDns    = field("DNS Server", p.dnsServer)
        val etNs     = field("Nameserver", p.nameserver)
        val etPubKey = field("Public Key", p.publicKey)

        label("HTTP CONNECT PROXY")
        val etProxyHost = field("Proxy Host", p.proxyHost)
        val etProxyPort = field("Proxy Port", p.proxyPort.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        val etPayload = field("Payload (optionnel)", p.customPayload)

        label("TUNNELS PARALLELES")
        val tvTunnelCount = TextView(context).apply {
            text = "Flux simultanes : ${p.tunnelCount}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(0, 8, 0, 4)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            layout.addView(this)
        }
        val seekTunnel = SeekBar(context).apply {
            max = 3
            progress = p.tunnelCount.coerceIn(1, 4) - 1
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4 }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                    tvTunnelCount.text = "Flux simultanes : ${v + 1}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            layout.addView(this)
        }
        TextView(context).apply {
            text = "1 flux = stable  |  2-3 flux = debit x N  |  4 flux = max"
            setTextColor(0xFF888888.toInt())
            textSize = 11f
            setPadding(0, 2, 0, 8)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            layout.addView(this)
        }

        AlertDialog.Builder(context)
            .setTitle(if (isEdit) "Modifier profil" else "Nouveau profil")
            .setView(scroll)
            .setPositiveButton("Sauvegarder") { _, _ ->
                val updated = p.copy(
                    profileName  = etName.text.toString().ifEmpty { "Profil" },
                    sshHost      = etSshHost.text.toString(),
                    sshPort      = etSshPort.text.toString().toIntOrNull() ?: 22,
                    sshUser      = etSshUser.text.toString(),
                    sshPass      = etSshPass.text.toString(),
                    dnsServer    = etDns.text.toString().ifEmpty { "8.8.8.8" },
                    nameserver   = etNs.text.toString(),
                    publicKey    = etPubKey.text.toString(),
                    proxyHost    = etProxyHost.text.toString(),
                    proxyPort    = etProxyPort.text.toString().toIntOrNull() ?: 8080,
                    customPayload = etPayload.text.toString(),
                    tunnelCount  = seekTunnel.progress + 1
                )
                onSave(updated)
            }
            .setNegativeButton("Annuler", null)
            .show().also { dialog ->
                // Forcer hauteur maximale pour afficher tous les champs
                dialog.window?.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (context.resources.displayMetrics.heightPixels * 0.90).toInt()
                )
            }
    }
}
