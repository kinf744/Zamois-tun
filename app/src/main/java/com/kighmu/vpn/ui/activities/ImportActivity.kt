package com.kighmu.vpn.ui.activities

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kighmu.vpn.R
import com.kighmu.vpn.config.ConfigEncryption
import com.kighmu.vpn.models.ExportConfig
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.ui.MainViewModel
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

class ImportActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val configManager by lazy { com.kighmu.vpn.config.ConfigManager(this) }

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importFromFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)

        findViewById<Button>(R.id.btn_import_file).setOnClickListener {
            filePicker.launch("*/*")
        }

        findViewById<Button>(R.id.btn_import_cloud).setOnClickListener {
            importFromCloud()
        }

        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { importFromFile(it) }
        }
    }

    private fun importFromFile(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return
            val json = String(bytes, Charsets.UTF_8)
            processImportJson(json)
        } catch (e: Exception) {
            showError("Erreur lecture fichier: ${e.message}")
        }
    }

    private fun importFromCloud() {
        val etCode = findViewById<EditText>(R.id.et_cloud_code)
        val code = etCode.text.toString().trim()
        if (code.isBlank()) {
            showError("Entrez un code d'accès")
            return
        }
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { fetchCloudConfig(code) }
                if (json != null) processImportJson(json)
                else showError("Code invalide ou config introuvable")
            } catch (e: Exception) {
                showError("Erreur réseau: ${e.message}")
            }
        }
    }

    private fun processImportJson(json: String) {
        try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = Gson().fromJson(json, type)

            val configJson = Gson().toJson(map["config"])
            val securityJson = Gson().toJson(map["security"])

            val config = Gson().fromJson(configJson, KighmuConfig::class.java)
            val security = Gson().fromJson(securityJson, ExportConfig::class.java)

            if (security.accessCode.isNotBlank()) {
                promptAccessCode(config, security)
                return
            }

            validateAndImport(config, security)

        } catch (e: Exception) {
            showError("Format invalide: ${e.message}")
        }
    }

    private fun promptAccessCode(config: KighmuConfig, security: ExportConfig) {
        val input = EditText(this).apply {
            hint = "Code d'accès"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Code d'accès requis")
            .setView(input)
            .setPositiveButton("Valider") { _, _ ->
                if (input.text.toString() == security.accessCode) {
                    validateAndImport(config, security)
                } else {
                    showError("Code incorrect")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun validateAndImport(config: KighmuConfig, security: ExportConfig) {
        // 1. Verifier signature HMAC - protection anti-modification du fichier
        if (security.securitySignature.isNotBlank()) {
            val valid = ConfigEncryption.verifyExportSignature(
                hwid = security.hardwareId,
                expiresAt = security.expiresAt,
                lockDeviceId = security.lockDeviceId,
                lockOperator = security.lockOperator,
                operatorName = security.operatorName,
                burnAfterImport = security.burnAfterImport,
                appId = security.appId,
                signature = security.securitySignature
            )
            if (!valid) {
                showError("Config corrompue ou modifiee - import refuse")
                return
            }
        }

        // 2. Verifier expiration
        if (security.expiresAt > 0 && System.currentTimeMillis() > security.expiresAt) {
            showError("Cette configuration a expire")
            return
        }


        // 3. Verifier hardware ID
        if (security.lockDeviceId) {
            val currentHwId = ConfigEncryption.getHardwareId(this).uppercase()
            val allowedIds = if (security.allowedDeviceIds.isNotEmpty())
                security.allowedDeviceIds.map { it.uppercase() }
            else if (security.hardwareId.isNotEmpty())
                listOf(security.hardwareId.uppercase())
            else emptyList()
            if (allowedIds.isNotEmpty() && currentHwId !in allowedIds) {
                showError("Config verrouillee sur un autre appareil")
                return
            }
        }

        // 4. Verifier operateur
        if (security.lockOperator && security.operatorName.isNotEmpty()) {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val currentOp = tm.networkOperatorName.trim()
            if (!currentOp.equals(security.operatorName.trim(), ignoreCase = true)) {
                showError("Config verrouillee sur l operateur: ${security.operatorName}")
                return
            }
        }

        // 5. Verifier appId - empeche import dans une autre app
        if (security.appId.isNotBlank() && security.appId != packageName) {
            showError("Config non compatible avec cette application")
            return
        }

        val finalConfig = config.copy(
            exportConfig = security,
            hardwareId = if (security.lockDeviceId) security.hardwareId else config.hardwareId,
            expiresAt = if (security.expiresAt > 0L) security.expiresAt else config.expiresAt
        )

        if (security.burnAfterImport) {
            showConfirmBurn(finalConfig, security)
            return
        }

        applyImport(finalConfig)
    }

    
    private fun showConfirmBurn(config: KighmuConfig, @Suppress("UNUSED_PARAMETER") security: ExportConfig) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Config à usage unique")
            .setMessage("Cette configuration sera détruite après importation. Continuer?")
            .setPositiveButton("Importer") { _, _ ->
                applyImport(config)
                Toast.makeText(this, "Config importée et brûlée", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun applyImport(config: KighmuConfig) {
        configManager.saveCurrentConfig(config)
        viewModel.saveConfig(config)
        Toast.makeText(this, "✓ Configuration importée avec succès!", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private suspend fun fetchCloudConfig(code: String): String? {
        return try {
            val cleanCode = when {
                code.startsWith("https://kighmu.link/") -> code.removePrefix("https://kighmu.link/")
                else -> code.trim()
            }

            // Tenter décodage base64 → "gistId|fileName"
            // Note: "~" est utilisé comme séparateur entre shortCode et encoded
            val encodedPart = if (cleanCode.contains("~")) cleanCode.split("~")[1] else cleanCode
            val decoded = try {
                val padded = encodedPart.padEnd((encodedPart.length + 3) / 4 * 4, '=')
                String(android.util.Base64.decode(padded, android.util.Base64.URL_SAFE))
            } catch (_: Exception) { null }

            val urlString = if (decoded != null && decoded.contains("|")) {
                // Nouveau format Gist
                val gistId = decoded.split("|")[0]
                "https://api.github.com/gists/$gistId"
            } else {
                // Ancien format raw.githubusercontent (rétrocompatibilité)
                "https://raw.githubusercontent.com/kinf744/kighmu-vpn/cloud-configs/configs/$cleanCode.json"
            }

            android.util.Log.d("ImportActivity", "fetchCloudConfig URL: $urlString")
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "KIGHMU-VPN/1.0")

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText().trim()
                val json = if (urlString.contains("api.github.com/gists")) {
                    val gistJson = org.json.JSONObject(body)
                    val files = gistJson.getJSONObject("files")
                    val firstFile = files.keys().next()
                    files.getJSONObject(firstFile).getString("content")
                } else {
                    body
                }
                if (json.contains("config") && json.contains("security")) json else null
            } else {
                android.util.Log.e("ImportActivity", "HTTP ${conn.responseCode} pour $urlString")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("ImportActivity", "fetchCloudConfig exception: ${e.message}")
            null
        }
    }

    
    private fun showError(msg: String) {
        runOnUiThread {
            Toast.makeText(this, "❌ $msg", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
