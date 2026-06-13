package com.kighmu.vpn.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.widget.*
import com.kighmu.vpn.profiles.HttpProxyProfile

object HttpProxyProfileEditDialog {
    fun show(context: Context, profile: HttpProxyProfile? = null, onSave: (HttpProxyProfile) -> Unit) {
        val p = profile ?: HttpProxyProfile()

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

        label("Proxy")
        val etProxyHost = field("Proxy Host / IP", p.proxyHost, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
        val etProxyPort = field("Proxy Port (8080)", p.proxyPort.toString(), android.text.InputType.TYPE_CLASS_NUMBER)

        label("SSH")
        val etSshHost = field("SSH Host / IP", p.sshHost, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI)
        val etSshPort = field("SSH Port (22)", p.sshPort.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        val etSshUser = field("SSH Username", p.sshUser)
            val etSshPass = field("SSH Password", p.sshPass, android.text.InputType.TYPE_CLASS_TEXT)

        label("Payload HTTP (placeholders: [host] [port] [crlf])")
        val etPayload = EditText(context).apply {
            hint = "HTTP Payload"
            setText(p.customPayload)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 10
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setBackgroundColor(0xFF1A1F2E.toInt())
            setPadding(16, 12, 16, 12)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        layout.addView(etPayload)

        AlertDialog.Builder(context)
            .setTitle(if (profile == null) "Nouveau profil HTTP Proxy" else "Modifier le profil")
            .setView(scroll)
            .setPositiveButton("Enregistrer") { _, _ ->
                val updated = p.copy(
                    profileName = etName.text.toString().ifBlank { "Profil HTTP" },
                    proxyHost = etProxyHost.text.toString().trim(),
                    proxyPort = etProxyPort.text.toString().toIntOrNull() ?: 8080,
                    sshHost = etSshHost.text.toString().trim(),
                    sshPort = etSshPort.text.toString().toIntOrNull() ?: 22,
                    sshUser = etSshUser.text.toString().trim(),
                    sshPass = etSshPass.text.toString(),
                    customPayload = etPayload.text.toString()
                )
                onSave(updated)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
