package com.kighmu.vpn.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.widget.*
import com.kighmu.vpn.profiles.ZivpnProfile

object ZivpnProfileEditDialog {
    fun show(context: Context, profile: ZivpnProfile? = null, onSave: (ZivpnProfile) -> Unit) {
        val p = profile ?: ZivpnProfile()

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

        label("Serveur ZIVPN")
        val etHost = field("Server Address (host ou IP)", p.serverAddress)
        val etPort = field("Port Range (ex: 6000-19999)", p.serverPort)
        val etPass = field("Password", p.password)


        AlertDialog.Builder(context)
            .setTitle(if (profile == null) "Nouveau profil ZIVPN" else "Modifier le profil")
            .setView(scroll)
            .setPositiveButton("Enregistrer") { _, _ ->
                val updated = p.copy(
                    profileName = etName.text.toString().ifBlank { "Profil ZIVPN" },
                    serverAddress = etHost.text.toString().trim(),
                    serverPort = etPort.text.toString().trim(),
                    password = etPass.text.toString(),
                    obfs = "hu``hqb`c"
                )
                onSave(updated)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
