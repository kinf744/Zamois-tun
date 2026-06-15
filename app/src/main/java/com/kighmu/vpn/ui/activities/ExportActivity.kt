package com.kighmu.vpn.ui.activities

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.kighmu.vpn.R
import com.kighmu.vpn.models.ExportConfig
import com.kighmu.vpn.ui.MainViewModel
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.UUID
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

class ExportActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingExportJson: String = ""
    private var pendingExportFileName: String = ""
    private val createFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && pendingExportJson.isNotEmpty()) {
            try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(pendingExportJson.toByteArray())
                }
                Toast.makeText(this, "✓ Config exportée avec succès", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    private var expiresAt = 0L
    private var exportType = "normal"  // normal | burn | expiry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)

        setupExpiryDate()
        setupExportButtons()
        setupCloudExport()
        loadPersistedData()
    }

    private fun loadPersistedData() {
        val prefs = getSharedPreferences("kighmu_export_prefs", Context.MODE_PRIVATE)
        findViewById<EditText>(R.id.et_github_token).setText(prefs.getString("github_token", ""))
        findViewById<EditText>(R.id.et_user_message).setText(prefs.getString("user_message", ""))
    }

    private fun savePersistedData() {
        val prefs = getSharedPreferences("kighmu_export_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("github_token", findViewById<EditText>(R.id.et_github_token).text.toString().trim())
            .putString("user_message", findViewById<EditText>(R.id.et_user_message).text.toString())
            .apply()
    }

    private fun setupExpiryDate() {
        val cbExpiry = findViewById<CheckBox>(R.id.cb_set_expiry)
        val layoutExpiry = findViewById<View>(R.id.layout_expiry)
        val tvDate = findViewById<TextView>(R.id.tv_expiry_date)
        val tvTime = findViewById<TextView>(R.id.tv_expiry_time)
        
        val expiryCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }
        
        cbExpiry.setOnCheckedChangeListener { _, checked ->
            layoutExpiry.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) expiresAt = 0L
        }
        
        findViewById<Button>(R.id.btn_pick_date).setOnClickListener {
            DatePickerDialog(this,
                { _, year, month, day ->
                    expiryCalendar.set(Calendar.YEAR, year)
                    expiryCalendar.set(Calendar.MONTH, month)
                    expiryCalendar.set(Calendar.DAY_OF_MONTH, day)
                    expiresAt = expiryCalendar.timeInMillis
                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    tvDate.text = sdf.format(expiryCalendar.time)
                    tvDate.setTextColor(0xFF009688.toInt())
                },
                expiryCalendar.get(Calendar.YEAR),
                expiryCalendar.get(Calendar.MONTH),
                expiryCalendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        
        findViewById<Button>(R.id.btn_pick_time).setOnClickListener {
            TimePickerDialog(this,
                { _, hourOfDay, minute ->
                    expiryCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    expiryCalendar.set(Calendar.MINUTE, minute)
                    expiryCalendar.set(Calendar.SECOND, 0)
                    expiresAt = expiryCalendar.timeInMillis
                    tvTime.text = String.format("Heure : %02d:%02d", hourOfDay, minute)
                    tvTime.setTextColor(0xFF009688.toInt())
                },
                expiryCalendar.get(Calendar.HOUR_OF_DAY),
                expiryCalendar.get(Calendar.MINUTE),
                true
            ).show()
        }
    }

    private fun setupCloudExport() {
        val rgCloud = findViewById<RadioGroup>(R.id.rg_cloud_type)
        val layoutDuration = findViewById<View>(R.id.layout_cloud_duration)
        
        rgCloud.setOnCheckedChangeListener { _, id ->
            layoutDuration.visibility = if (id == R.id.rb_cloud_limited) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btn_export_cloud).setOnClickListener {
            val isLimited = rgCloud.checkedRadioButtonId == R.id.rb_cloud_limited
            val durationMinutes = if (isLimited) {
                findViewById<EditText>(R.id.et_cloud_duration).text.toString().toLongOrNull() ?: 60L
            } else 0L

            val btn = it as Button
            btn.isEnabled = false
            btn.text = "Génération du lien..."

            Thread {
                try {
                    // Priorite : datepicker (cb_set_expiry) > duree minutes > pas expiration
                    val cloudExpiresAt = when {
                        findViewById<CheckBox>(R.id.cb_set_expiry).isChecked && expiresAt > 0L -> expiresAt
                        durationMinutes > 0 -> System.currentTimeMillis() + durationMinutes * 60 * 1000L
                        else -> 0L
                    }
                    
                    val config = viewModel.config.value
                    val cloudLockDevice = findViewById<CheckBox>(R.id.cb_lock_device_id).isChecked
                    val cloudLockOp = findViewById<CheckBox>(R.id.cb_lock_operator).isChecked
                    
                    val cloudSecurity = ExportConfig(
                        expiresAt = cloudExpiresAt,
                        exportType = if (isLimited) "expiry" else "normal",
                        appId = packageName,
                        userMessage = findViewById<EditText>(R.id.et_user_message).text.toString(),
                        lockAllConfig = findViewById<CheckBox>(R.id.cb_lock_all_config).isChecked,
                        accessCode = "",
                        lockDeviceId = cloudLockDevice,
                        hardwareId = if (cloudLockDevice) com.kighmu.vpn.config.ConfigEncryption.getHardwareId(this).uppercase() else "",
                        lockOperator = cloudLockOp,
                        operatorName = if (cloudLockOp) (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName else "",
                        burnAfterImport = exportType == "burn",
                        securitySignature = buildExportSignature(
                            hwid = if (cloudLockDevice) com.kighmu.vpn.config.ConfigEncryption.getHardwareId(this).uppercase() else "",
                            expiresAt = cloudExpiresAt,
                            lockDeviceId = cloudLockDevice,
                            lockOperator = cloudLockOp,
                            operatorName = if (cloudLockOp) (getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).networkOperatorName else "",
                            burnAfterImport = exportType == "burn",
                            appId = packageName
                        )
                    )
                    
                    // Lire les tunnels cochés
                    val enabledTunnels = mutableListOf<Int>()
                    if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_slowdns).isChecked) enabledTunnels.add(0)
                    if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_http).isChecked) enabledTunnels.add(1)
                    if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_ssl).isChecked) enabledTunnels.add(3)
                    if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_xray).isChecked) enabledTunnels.add(4)
                    if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_v2dns).isChecked) enabledTunnels.add(5)
                    if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_hysteria).isChecked) enabledTunnels.add(6)
                    if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_zivpn).isChecked) enabledTunnels.add(7)
                    val configWithTunnels = config.copy(
                        enabledTunnels = if (enabledTunnels.isEmpty()) mutableListOf(0,1,3,4,5,6,7) else enabledTunnels
                    )

                    val exportPackage = mapOf(
                        "config" to configWithTunnels,
                        "security" to cloudSecurity,
                        "exportedAt" to System.currentTimeMillis(),
                        "appVersion" to com.kighmu.vpn.BuildConfig.VERSION_NAME
                    )
                    val json = Gson().toJson(exportPackage)

                    // Sauvegarder les données persistantes
                    savePersistedData()

                    // Methode principale : GitHub Gist (si token fourni), sinon paste.ee
                    val githubToken = findViewById<EditText>(R.id.et_github_token).text.toString().trim()
                    val pasteUrl = uploadToCloud(json, githubToken)

                    runOnUiThread {
                        btn.isEnabled = true
                        btn.text = "☁️ Exporter vers le cloud"
                        if (pasteUrl != null) {
                            val parts = pasteUrl.removePrefix("kighmu:").split("~")
                            val shortCode = parts[0]
                            val fullCode = if (parts.size > 1) parts[1] else shortCode
                            val kighmuLink = "https://kighmu.link/$fullCode"
                            val displayLink = "https://kighmu.link/$shortCode"
                            findViewById<View>(R.id.layout_cloud_result).visibility = View.VISIBLE
                            findViewById<TextView>(R.id.tv_cloud_link_kighmu).text = displayLink
                            findViewById<TextView>(R.id.tv_cloud_code_only).text = shortCode

                            findViewById<Button>(R.id.btn_copy_cloud_link).setOnClickListener { copyToClipboard("Lien", kighmuLink) }
                            findViewById<Button>(R.id.btn_copy_cloud_code_only).setOnClickListener { copyToClipboard("Code", fullCode) }
                            findViewById<Button>(R.id.btn_copy_all_cloud).setOnClickListener { copyToClipboard("Lien & Code", "Lien: $kighmuLink\nCode: $fullCode") }
                            Toast.makeText(this, "✓ Export Cloud Réussi", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Erreur lors de l'exportation", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        btn.isEnabled = true
                        btn.text = "☁️ Exporter vers le cloud"
                        val msg = e.message ?: "Erreur inconnue"
                        Toast.makeText(this, "❌ $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    /**
     * uploadToCloud — Methode principale d'upload.
     *
     * 1. Si un token GitHub est fourni -> GitHub Gist (API REST, fiable, raw URL stable)
     * 2. Sinon -> paste.ee (anonyme)
     * 3. Fallback final -> hastebin
     */
    private fun generateCode(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun uploadToCloud(content: String, githubToken: String): String? {
        val token = githubToken.ifBlank {
            throw Exception("Token GitHub requis pour l export cloud")
        }
        val code = generateCode(8)
        val fileName = "$code.json"

        val requestBody = org.json.JSONObject().apply {
            put("description", "KIGHMU:$code")
            put("public", false)
            val files = org.json.JSONObject()
            files.put(fileName, org.json.JSONObject().put("content", content))
            put("files", files)
        }.toString()

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val underlyingNetwork = connectivityManager.allNetworks.firstOrNull { net ->
            val caps = connectivityManager.getNetworkCapabilities(net)
            caps != null &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !caps.hasTransportType(android.net.NetworkCapabilities.TRANSPORT_VPN)
        }
        val conn = (if (underlyingNetwork != null) {
            underlyingNetwork.openConnection(URL("https://api.github.com/gists"))
        } else {
            URL("https://api.github.com/gists").openConnection()
        }) as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.doInput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.setRequestProperty("Authorization", "token $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.outputStream.use { it.write(requestBody.toByteArray()) }

        val responseCode = conn.responseCode
        val responseBody = try {
            conn.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.readText()?.trim() ?: "Erreur inconnue"
        }
        conn.disconnect()

        if (responseCode == 201) {
            val gistId = org.json.JSONObject(responseBody).optString("id", "")
            if (gistId.isBlank()) throw Exception("Gist créé mais ID introuvable")
            val payload = "$gistId|$fileName"
            val encoded = android.util.Base64.encodeToString(
                payload.toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
            ).trimEnd('=')
            return "kighmu:$code~$encoded"
        } else {
            val errorMessage = try {
                org.json.JSONObject(responseBody).optString("message", "Erreur $responseCode")
            } catch (_: Exception) { "Erreur $responseCode" }
            throw Exception("GitHub: $errorMessage")
        }
    }

    
    private fun setupExportButtons() {
        // Toggle champ multi-IDs appareils
        val cbLockDevice = findViewById<CheckBox>(R.id.cb_lock_device_id)
        val layoutDeviceIds = findViewById<android.view.View>(R.id.layout_device_ids)
        cbLockDevice.setOnCheckedChangeListener { _, checked ->
            layoutDeviceIds.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }

        findViewById<Button>(R.id.btn_export_save_file).setOnClickListener {
            showExportTypeDialog(share = false, locked = findViewById<CheckBox>(R.id.cb_lock_all_config).isChecked)
        }
    }

    private fun showExportTypeDialog(share: Boolean, locked: Boolean) {
        val options = arrayOf("Normal", "Usage unique (Burn)", "Avec expiration")
        AlertDialog.Builder(this)
            .setTitle("Type d'exportation")
            .setItems(options) { _, which ->
                exportType = when(which) {
                    1 -> "burn"
                    2 -> "expiry"
                    else -> "normal"
                }
                exportConfig(locked, share)
            }
            .show()
    }

    private fun exportConfig(locked: Boolean, @Suppress("UNUSED_PARAMETER") share: Boolean) {
        try {
        savePersistedData()
        val fileName = findViewById<EditText>(R.id.et_export_filename).text.toString().ifBlank { "kighmu_config" }
        val userMessage = findViewById<EditText>(R.id.et_user_message).text.toString()
        val lockOperator = findViewById<CheckBox>(R.id.cb_lock_operator).isChecked
        val operatorName = if (lockOperator) (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName else ""
        val lockDeviceId = findViewById<CheckBox>(R.id.cb_lock_device_id).isChecked
        val androidId = if (lockDeviceId) com.kighmu.vpn.config.ConfigEncryption.getHardwareId(this).uppercase() else ""
        val allowedDeviceIds: List<String> = if (lockDeviceId) {
            val raw = findViewById<EditText>(R.id.et_allowed_device_ids).text.toString()
            val typed = raw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
            if (typed.isEmpty()) listOf(androidId).filter { it.isNotEmpty() } else typed
        } else emptyList()

        val config = viewModel.config.value
        val isBurn = exportType == "burn"
        val exportConfig = ExportConfig(
            fileName = fileName,
            expiresAt = if (exportType == "expiry" || (findViewById<CheckBox>(R.id.cb_set_expiry).isChecked && expiresAt > 0L)) expiresAt else 0L,
            hardwareId = androidId,
            allowedDeviceIds = allowedDeviceIds,
            lockOperator = lockOperator,
            operatorName = operatorName,
            blockRoot = findViewById<CheckBox>(R.id.cb_block_root).isChecked,
            mobileDataOnly = findViewById<CheckBox>(R.id.cb_mobile_data_only).isChecked,
            lockDeviceId = lockDeviceId,
            playStoreOnly = findViewById<CheckBox>(R.id.cb_playstore_only).isChecked,
            disableOverride = findViewById<CheckBox>(R.id.cb_disable_override).isChecked,
            blockTorrent = findViewById<CheckBox>(R.id.cb_block_torrent).isChecked,
            gameMode = findViewById<CheckBox>(R.id.cb_game_mode).isChecked,
            userMessage = userMessage,
            exportType = exportType,
            burnAfterImport = isBurn,
            burnToken = if (isBurn) UUID.randomUUID().toString() else "",
            lockAllConfig = locked || findViewById<CheckBox>(R.id.cb_lock_all_config).isChecked,
            appId = packageName,
            securitySignature = buildExportSignature(
            hwid = androidId,
            expiresAt = if (exportType == "expiry" || (findViewById<CheckBox>(R.id.cb_set_expiry).isChecked && expiresAt > 0L)) expiresAt else 0L,
            lockDeviceId = lockDeviceId,
            lockOperator = lockOperator,
            operatorName = operatorName,
            burnAfterImport = isBurn,
            appId = packageName
        )
        )

        // ── Lire les tunnels cochés (même logique que cloud export) ──
        val enabledTunnels = mutableListOf<Int>()
        if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_slowdns).isChecked) enabledTunnels.add(0)
        if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_http).isChecked) enabledTunnels.add(1)
        if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_ssl).isChecked) enabledTunnels.add(3)
        if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_xray).isChecked) enabledTunnels.add(4)
        if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_v2dns).isChecked) enabledTunnels.add(5)
        if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_hysteria).isChecked) enabledTunnels.add(6)
        if (findViewById<android.widget.CheckBox>(R.id.cb_tunnel_zivpn).isChecked) enabledTunnels.add(7)
        val finalEnabled = if (enabledTunnels.isEmpty()) mutableListOf(0,1,3,4,5,6,7) else enabledTunnels
        val configWithTunnels = config.copy(
            enabledTunnels = finalEnabled,
            tunnelMode = if (config.tunnelMode.id in finalEnabled) config.tunnelMode
                         else com.kighmu.vpn.models.TunnelMode.fromId(finalEnabled.first())
        )
        val exportPackage = mapOf(
            "config" to configWithTunnels,
            "security" to exportConfig,
            "exportedAt" to System.currentTimeMillis(),
            "appVersion" to com.kighmu.vpn.BuildConfig.VERSION_NAME
        )

        val json = Gson().toJson(exportPackage)
        val finalFileName = if (fileName.endsWith(".kighmu")) fileName else "$fileName.kighmu"
        
        // Sauvegarder le fichier — choix du dossier via SAF
        pendingExportJson = json
        pendingExportFileName = finalFileName
        createFileLauncher.launch(finalFileName)
        Toast.makeText(this, "Config sauvegardée: $finalFileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildSignature(config: String, hwid: String, expiry: Long, operator: String): String {
        val salt = "KIGHMU_SECURE_SALT_2026"
        val input = "$config|$hwid|$expiry|$operator|$salt"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun buildExportSignature(
        hwid: String,
        expiresAt: Long,
        lockDeviceId: Boolean,
        lockOperator: Boolean,
        operatorName: String,
        burnAfterImport: Boolean,
        appId: String
    ): String {
        return com.kighmu.vpn.config.ConfigEncryption.signExportConfig(
            hwid, expiresAt, lockDeviceId, lockOperator, operatorName, burnAfterImport, appId
        )
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copié !", Toast.LENGTH_SHORT).show()
    }
}
