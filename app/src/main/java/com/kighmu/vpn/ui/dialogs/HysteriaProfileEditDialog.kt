package com.kighmu.vpn.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.widget.*
import com.kighmu.vpn.profiles.HysteriaProfile

object HysteriaProfileEditDialog {
    fun show(context: Context, profile: HysteriaProfile? = null, onSave: (HysteriaProfile) -> Unit) {
        val p = profile ?: HysteriaProfile()

        val scroll = android.widget.ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        scroll.addView(layout)

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

        fun label(text: String) {
            layout.addView(TextView(context).apply {
                this.text = text
                setTextColor(0xFF4FC3F7.toInt())
                textSize = 12f
                setPadding(0, 16, 0, 4)
            })
        }

        label("Nom du profil")
        val etName = field("Nom du profil", p.profileName)

        label("Serveur Hysteria")
        val etHost = field("Server Address (host ou IP)", p.serverAddress)
        val etAuth = field("Authentication Password", p.authPassword, android.text.InputType.TYPE_CLASS_TEXT)

        label("Bande passante")
        val etUp = field("Upload Speed (Mbps)", p.uploadMbps.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        val etDown = field("Download Speed (Mbps)", p.downloadMbps.toString(), android.text.InputType.TYPE_CLASS_NUMBER)

        label("Options avancees (optionnel)")
        val etObfs = field("Obfuscation Password (optionnel)", p.obfsPassword)
        val etHop = field("Port Hopping Range (ex: 20000-50000)", p.portHopping)

        AlertDialog.Builder(context)
            .setTitle(if (profile == null) "Nouveau profil Hysteria" else "Modifier le profil")
            .setView(scroll)
            .setPositiveButton("Enregistrer") { _, _ ->
                val updated = p.copy(
                    profileName = etName.text.toString().ifBlank { "Profil Hysteria" },
                    serverAddress = etHost.text.toString().trim(),
                    authPassword = etAuth.text.toString(),
                    uploadMbps = etUp.text.toString().toIntOrNull() ?: 100,
                    downloadMbps = etDown.text.toString().toIntOrNull() ?: 100,
                    obfsPassword = etObfs.text.toString(),
                    portHopping = etHop.text.toString().trim()
                )
                onSave(updated)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
