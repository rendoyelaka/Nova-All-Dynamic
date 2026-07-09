package com.cristal.bristral.tristal.mistral

import android.content.Context
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Nova Launcher — DecryptionManager
 * CrystalSoft Technologies Ltd
 *
 * Decrypts .enc files from assets into RAM only
 * Nothing written to disk — ever
 * AES-256-CBC | PBKDF2 key derivation
 *
 * .enc file format:
 * MAGIC(4) + VERSION(1) + SALT(32) + IV(16) + ENCRYPTED_DATA
 */
object DecryptionManager {

    // ── Constants ─────────────────────────────────────────────────────────────
    private const val MAGIC          = "NOVA"
    private const val VERSION        = 0x01.toByte()
    private const val MAGIC_LEN      = 4
    private const val VERSION_LEN    = 1
    private const val SALT_LEN       = 32
    private const val IV_LEN         = 16
    private const val HEADER_LEN     = MAGIC_LEN + VERSION_LEN + SALT_LEN + IV_LEN  // 53
    private const val PBKDF2_ITER    = 310000
    private const val KEY_LEN_BITS   = 256
    private const val BUILD_PREFIX   = "CrystalSoft-Nova-"

    // ── Decrypted Payloads (RAM only — never on disk) ─────────────────────────
    var classesDex   : ByteArray? = null
        private set
    var resZip       : ByteArray? = null
        private set
    var resourcesArsc: ByteArray? = null
        private set

    private var isLoaded = false

    // ── Public Entry Point ────────────────────────────────────────────────────
    /**
     * Call once on app open from MainActivity
     * Reads build.meta → derives key → decrypts all .enc files into RAM
     */
    fun loadAll(context: Context): Boolean {
        if (isLoaded) return true

        return try {
            // Read build.meta from assets
            val meta     = readBuildMeta(context) ?: return false
            val buildId  = meta["BUILD_ID"]       ?: return false

            // Decrypt each file
            classesDex    = decryptAsset(context, "classes.enc",   buildId)
            resZip        = decryptAsset(context, "res.enc",        buildId)
            resourcesArsc = decryptAsset(context, "resources.enc",  buildId)

            isLoaded = true
            true
        } catch (e: Exception) {
            wipeAll()
            false
        }
    }

    // ── Wipe All From RAM ─────────────────────────────────────────────────────
    /**
     * Zero out all decrypted byte arrays from RAM
     * Call after DexClassLoader + resources are fully loaded
     */
    fun wipeAll() {
        classesDex?.fill(0)
        resZip?.fill(0)
        resourcesArsc?.fill(0)
        classesDex    = null
        resZip        = null
        resourcesArsc = null
        isLoaded      = false
    }

    // ── Read build.meta ───────────────────────────────────────────────────────
    private fun readBuildMeta(context: Context): Map<String, String>? {
        return try {
            val lines = context.assets.open("build.meta")
                .bufferedReader()
                .readLines()
            lines.associate { line ->
                val parts = line.split("=", limit = 2)
                parts[0].trim() to parts[1].trim()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Decrypt Single Asset ──────────────────────────────────────────────────
    private fun decryptAsset(
        context : Context,
        fileName: String,
        buildId : String
    ): ByteArray? {
        return try {
            // Read raw bytes from assets
            val raw = context.assets.open(fileName).use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var n: Int
                while (stream.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                out.toByteArray()
            }

            // Validate header
            if (raw.size < HEADER_LEN) return null
            val magic = String(raw.sliceArray(0 until MAGIC_LEN))
            if (magic != MAGIC) return null
            if (raw[MAGIC_LEN] != VERSION) return null

            // Extract salt + IV
            val saltStart = MAGIC_LEN + VERSION_LEN
            val ivStart   = saltStart + SALT_LEN
            val dataStart = ivStart   + IV_LEN

            val salt      = raw.sliceArray(saltStart until ivStart)
            val iv        = raw.sliceArray(ivStart   until dataStart)
            val encrypted = raw.sliceArray(dataStart until raw.size)

            // Derive key
            val key = deriveKey(buildId, salt)

            // Decrypt AES-256-CBC
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            val decrypted = cipher.doFinal(encrypted)

            // Zero raw bytes immediately
            raw.fill(0)
            encrypted.fill(0)
            key.fill(0)

            decrypted
        } catch (e: Exception) {
            null
        }
    }

    // ── PBKDF2 Key Derivation ─────────────────────────────────────────────────
    private fun deriveKey(buildId: String, salt: ByteArray): ByteArray {
        val password = "$BUILD_PREFIX$buildId".toCharArray()
        val spec = PBEKeySpec(password, salt, PBKDF2_ITER, KEY_LEN_BITS)
        return try {
            SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
        }
    }

    // ── Integrity Check ───────────────────────────────────────────────────────
    fun verifyIntegrity(context: Context): Boolean {
        return try {
            val meta   = readBuildMeta(context) ?: return false
            val keyFp  = meta["KEY_FP"]         ?: return false
            val buildId = meta["BUILD_ID"]       ?: return false
            val saltHex = meta["SALT"]           ?: return false

            val salt   = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val key    = deriveKey(buildId, salt)
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            val fp     = digest.joinToString("") { "%02x".format(it) }

            key.fill(0)
            salt.fill(0)

            fp == keyFp
        } catch (e: Exception) {
            false
        }
    }
}
