package com.kighmu.vpn.config

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.kighmu.vpn.models.KighmuConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Central manager for all configuration operations:
 * - Load / Save current config (encrypted local storage)
 * - Import / Export .kighmu files
 * - Config validation
 */
class ConfigManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("kighmu_prefs", Context.MODE_PRIVATE)
    private val configDir = File(context.filesDir, "configs").also { it.mkdirs() }
    private val activeConfigFile = File(configDir, "active.kighmu")

    companion object {
        const val KIGHMU_EXTENSION = ".kighmu"
        const val DEFAULT_PASSWORD = "KIGHMU_INTERNAL_v1"
        const val PREF_CURRENT_CONFIG = "current_config_json"
    }

    // ─── Active Config ────────────────────────────────────────────────────────

    fun loadCurrentConfig(): KighmuConfig {
        val json = prefs.getString(PREF_CURRENT_CONFIG, null)
        val config = if (json != null) {
            try { KighmuConfig.fromJson(json) } catch (e: Exception) { KighmuConfig() }
        } else {
            KighmuConfig()
        }
        // Migration automatique et définitive au chargement
        val migratedXray = com.kighmu.vpn.models.XrayConfig.migrate(config.xray)
        return if (migratedXray !== config.xray) {
            val migrated = config.copy(xray = migratedXray)
            saveCurrentConfig(migrated)
            migrated
        } else config
    }
    fun saveCurrentConfig(config: KighmuConfig) {
        prefs.edit().putString(PREF_CURRENT_CONFIG, config.toJson()).apply()
    }

    // ─── Import ───────────────────────────────────────────────────────────────

    sealed class ImportResult {
        data class Success(val config: KighmuConfig) : ImportResult()
        data class Expired(val expiredAt: Long) : ImportResult()
        data class WrongDevice(val msg: String) : ImportResult()
        data class InvalidPassword(val msg: String) : ImportResult()
        data class ParseError(val msg: String) : ImportResult()
        data class BurnedConfig(val msg: String) : ImportResult()
        data class AppMismatch(val msg: String) : ImportResult()
    }

    suspend fun importFromUri(uri: Uri, password: String = DEFAULT_PASSWORD): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                    ?: return@withContext ImportResult.ParseError("Cannot read file")

                val config = ConfigEncryption.decryptConfig(bytes, password)

                // Validate
                when (val result = ConfigEncryption.validateConfig(context, config)) {
                    is ConfigEncryption.ValidationResult.Expired ->
                        return@withContext ImportResult.Expired(result.expiredAt)
                    is ConfigEncryption.ValidationResult.WrongDevice ->
                        return@withContext ImportResult.WrongDevice(
                            "Config locked to device: ${result.expected}\nYour device: ${result.current}"
                        )
                    else -> { /* ok */ }
                }

                saveCurrentConfig(config)
                ImportResult.Success(config)

            } catch (e: javax.crypto.BadPaddingException) {
                ImportResult.InvalidPassword("Wrong password or corrupted file")
            } catch (e: Exception) {
                ImportResult.ParseError(e.message ?: "Unknown error")
            }
        }

    // ─── Export ───────────────────────────────────────────────────────────────

    suspend fun exportConfig(
        config: KighmuConfig,
        outputUri: Uri,
        password: String = DEFAULT_PASSWORD
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = ConfigEncryption.encryptConfig(config, password)
            context.contentResolver.openOutputStream(outputUri)?.use { it.write(bytes) }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun exportToShareFile(
        config: KighmuConfig,
        password: String = DEFAULT_PASSWORD
    ): File = withContext(Dispatchers.IO) {
        val safeName = config.configName
            .replace(Regex("[^A-Za-z0-9_\\- ]"), "")
            .replace(" ", "_")
            .take(40)
        val file = File(context.cacheDir, "${safeName}${KIGHMU_EXTENSION}")
        val bytes = ConfigEncryption.encryptConfig(config, password)
        file.writeBytes(bytes)
        file
    }

    // ─── Import Plain JSON (Xray / Hysteria direct) ───────────────────────────

    fun importXrayJson(json: String): Boolean {
        return try {
            val obj = org.json.JSONObject(json) // validate JSON
            val config = loadCurrentConfig()
            config.xray.jsonConfig = obj.toString(2)
            saveCurrentConfig(config)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ─── Config List ─────────────────────────────────────────────────────────

    fun listSavedConfigs(): List<File> =
        configDir.listFiles { f -> f.name.endsWith(KIGHMU_EXTENSION) }?.toList() ?: emptyList()

    // ─── Factory: Create locked config ───────────────────────────────────────

    fun createLockedConfig(
        base: KighmuConfig,
        expiresInDays: Int = 0,
        lockToCurrentDevice: Boolean = false,
        creator: String = "",
        signingKey: String = ""
    ): KighmuConfig {
        val config = base.copy(
            creator = creator,
            expiresAt = if (expiresInDays > 0)
                System.currentTimeMillis() + (expiresInDays * 86400_000L)
            else 0L,
            hardwareId = if (lockToCurrentDevice) ConfigEncryption.getHardwareId(context) else ""
        )
        if (signingKey.isNotEmpty()) {
            return config.copy(signature = ConfigEncryption.signConfig(config, signingKey))
        }
        return config
    }
}
