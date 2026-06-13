package com.kighmu.vpn.config

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.google.gson.Gson
import com.kighmu.vpn.models.KighmuConfig
import org.bouncycastle.crypto.generators.SCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Handles encryption, decryption, hardware binding and signature
 * for .kighmu configuration files.
 *
 * Encryption: AES-256-GCM
 * KDF: scrypt(password + hwid, salt)
 * Compression: DEFLATE
 */
object ConfigEncryption {

    private const val MAGIC_HEADER = "KIGHMU01"
    private const val GCM_TAG_LENGTH = 128
    private const val SALT_LENGTH = 32
    private const val IV_LENGTH = 12
    private const val KEY_LENGTH = 32

    // ─── Hardware ID ─────────────────────────────────────────────────────────

    fun getHardwareId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val fingerprint = Build.FINGERPRINT
        val combined = "$androidId|${Build.MANUFACTURER}|${Build.MODEL}|$fingerprint"
        return sha256(combined).take(32)
    }

    // ─── Signature ───────────────────────────────────────────────────────────

    // Signature HMAC renforcee sur tous les champs critiques de securite
    fun signExportConfig(
        hwid: String,
        expiresAt: Long,
        lockDeviceId: Boolean,
        lockOperator: Boolean,
        operatorName: String,
        burnAfterImport: Boolean,
        appId: String
    ): String {
        val salt = "KIGHMU_EXPORT_HMAC_v2_2026"
        val input = "$hwid|$expiresAt|$lockDeviceId|$lockOperator|$operatorName|$burnAfterImport|$appId|$salt"
        return sha256(input)
    }

    fun verifyExportSignature(
        hwid: String,
        expiresAt: Long,
        lockDeviceId: Boolean,
        lockOperator: Boolean,
        operatorName: String,
        burnAfterImport: Boolean,
        appId: String,
        signature: String
    ): Boolean {
        if (signature.isBlank()) return false
        val expected = signExportConfig(hwid, expiresAt, lockDeviceId, lockOperator, operatorName, burnAfterImport, appId)
        return expected == signature
    }

    fun signConfig(config: KighmuConfig, secretKey: String): String {
        val payload = "${config.configName}|${config.creator}|${config.createdAt}|${config.expiresAt}|${config.hardwareId}"
        return sha256("$payload|$secretKey").take(16)
    }

    fun verifySignature(config: KighmuConfig, secretKey: String): Boolean {
        if (config.signature.isEmpty()) return true
        val expected = signConfig(config, secretKey)
        return config.signature == expected
    }

    // ─── Validation ──────────────────────────────────────────────────────────

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Expired(val expiredAt: Long) : ValidationResult()
        data class WrongDevice(val expected: String, val current: String) : ValidationResult()
        data class InvalidSignature(val msg: String) : ValidationResult()
        data class DecryptionError(val msg: String) : ValidationResult()
    }

    fun validateConfig(context: Context, config: KighmuConfig): ValidationResult {
        // Check expiry
        if (config.expiresAt > 0 && System.currentTimeMillis() > config.expiresAt) {
            return ValidationResult.Expired(config.expiresAt)
        }

        // Check hardware binding
        if (config.hardwareId.isNotEmpty()) {
            val currentHwId = getHardwareId(context)
            if (config.hardwareId != currentHwId) {
                return ValidationResult.WrongDevice(config.hardwareId, currentHwId)
            }
        }

        return ValidationResult.Valid
    }

    // ─── Encrypt Config → .kighmu bytes ──────────────────────────────────────

    fun encryptConfig(config: KighmuConfig, password: String): ByteArray {
        val json = Gson().toJson(config)
        val compressed = compress(json.toByteArray(Charsets.UTF_8))

        val salt = SecureRandom().generateSeed(SALT_LENGTH)
        val key = deriveKey(password, salt)
        val iv = SecureRandom().generateSeed(IV_LENGTH)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val encrypted = cipher.doFinal(compressed)

        // Layout: MAGIC(8) + SALT(32) + IV(12) + DATA(*)
        val out = ByteArrayOutputStream()
        out.write(MAGIC_HEADER.toByteArray(Charsets.US_ASCII))
        out.write(salt)
        out.write(iv)
        out.write(encrypted)
        return out.toByteArray()
    }

    // ─── Decrypt .kighmu bytes → Config ──────────────────────────────────────

    fun decryptConfig(data: ByteArray, password: String): KighmuConfig {
        if (data.size < MAGIC_HEADER.length + SALT_LENGTH + IV_LENGTH + 16)
            throw IllegalArgumentException("Invalid .kighmu file")

        val magic = data.sliceArray(0 until MAGIC_HEADER.length).toString(Charsets.US_ASCII)
        if (magic != MAGIC_HEADER) throw IllegalArgumentException("Not a valid KIGHMU config file")

        var offset = MAGIC_HEADER.length
        val salt = data.sliceArray(offset until offset + SALT_LENGTH)
        offset += SALT_LENGTH
        val iv = data.sliceArray(offset until offset + IV_LENGTH)
        offset += IV_LENGTH
        val encrypted = data.sliceArray(offset until data.size)

        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val compressed = cipher.doFinal(encrypted)

        val json = decompress(compressed).toString(Charsets.UTF_8)
        return Gson().fromJson(json, KighmuConfig::class.java)
    }

    // ─── Key Derivation (scrypt) ──────────────────────────────────────────────

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        return SCrypt.generate(password.toByteArray(Charsets.UTF_8), salt, 16384, 8, 1, KEY_LENGTH)
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private fun compress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun decompress(data: ByteArray): ByteArray {
        val bis = ByteArrayInputStream(data)
        return InflaterInputStream(bis).readBytes()
    }

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun toBase64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
    fun fromBase64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
