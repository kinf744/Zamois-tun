package com.kighmu.vpn.ui.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.kighmu.vpn.BuildConfig
import com.kighmu.vpn.R
import com.kighmu.vpn.ui.MainViewModel

class SettingsFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("kighmu_prefs", Context.MODE_PRIVATE)

        // Hardware ID
        val hardwareId = com.kighmu.vpn.config.ConfigEncryption.getHardwareId(requireContext()).uppercase()
        view.findViewById<TextView>(R.id.tv_hardware_id).text = hardwareId

        view.findViewById<Button>(R.id.btn_copy_hwid).setOnClickListener {
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("hardware_id", hardwareId))
            Toast.makeText(requireContext(), "Hardware ID copié!", Toast.LENGTH_SHORT).show()
        }

        // Langue
        val spinnerLang = view.findViewById<Spinner>(R.id.spinner_language)
        val langs = arrayOf("Par défaut", "Français", "English", "Chinois", "Arabe", "Grec")
        val langAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, langs)
        spinnerLang.adapter = langAdapter
        val savedLang = prefs.getString("language", "Par défaut")
        spinnerLang.setSelection(langs.indexOf(savedLang).takeIf { it >= 0 } ?: 0)
        spinnerLang.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString("language", langs[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Thème
        val spinnerTheme = view.findViewById<Spinner>(R.id.spinner_theme)
        val themes = arrayOf("Night", "Light")
        val themeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, themes)
        spinnerTheme.adapter = themeAdapter
        val isDarkMode = prefs.getBoolean("dark_mode", true)
        spinnerTheme.setSelection(if (isDarkMode) 0 else 1)
        spinnerTheme.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val night = position == 0
                prefs.edit().putBoolean("dark_mode", night).apply()
                AppCompatDelegate.setDefaultNightMode(
                    if (night) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Switches
        val switches = mapOf(
            R.id.switch_notifications to "notifications",
            R.id.switch_auto_reconnect to "auto_reconnect",
            R.id.switch_kill_switch to "kill_switch",
            R.id.switch_dns_protection to "dns_protection",
            R.id.switch_wakelock to "wakelock",
            R.id.switch_http_ping to "http_ping",
            R.id.switch_keepalive to "keepalive",
            R.id.switch_compression to "compression",
            R.id.switch_enable_dns to "enable_dns",
            R.id.switch_dns_forwarding to "dns_forwarding"
        )
        
        for ((id, key) in switches) {
            val sw = view.findViewById<Switch>(id)
            sw.isChecked = prefs.getBoolean(key, key == "notifications" || key == "auto_reconnect" || key == "dns_protection")
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                if (key == "kill_switch") {
                    Toast.makeText(requireContext(), if (checked) "Kill Switch activé" else "Kill Switch désactivé", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // EditTexts
        val edits = mapOf(
            R.id.et_dns_primary to "dns_primary",
            R.id.et_dns_secondary to "dns_secondary",
            R.id.et_mtu to "mtu",
            R.id.et_udpgw to "udpgw"
        )
        
        for ((id, key) in edits) {
            val et = view.findViewById<EditText>(id)
            val default = when(key) {
                "dns_primary" -> "8.8.8.8"
                "dns_secondary" -> "8.8.4.4"
                else -> ""
            }
            if (key == "dns_primary" || key == "dns_secondary") {
                val saved = prefs.getString(key, "")?.trim() ?: ""
                if (saved.isEmpty() || saved == "1.1.1.1" || saved == "1.0.0.1") {
                    prefs.edit().putString(key, default).apply()
                    et.setText(default)
                } else {
                    et.setText(saved)
                }
            } else {
                et.setText(prefs.getString(key, ""))
            }
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(key, s.toString()).apply()
                }
            })
        }

        view.findViewById<TextView>(R.id.tv_app_version).text =
            "KIGHMU VPN v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }
}