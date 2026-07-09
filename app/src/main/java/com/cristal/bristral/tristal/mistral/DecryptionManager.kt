package com.cristal.bristral.tristal.mistral

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
 * Downloads .enc files from GitHub Release at first open
 * Decrypts into RAM only — nothing stays on disk unencrypted
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

    // ── GitHub Release Base URL ───────────────────────────────────────────────
    // Points to the latest release assets on your repo
    private const val GITHUB_BASE    = "https://github.com/rendoyelaka/Nova-All-Dynamic/releases/latest/download"

    // ── File names ────────────────────────────────────────────────────────────
    private const val FILE_META      = "build.meta"
    private const val FILE_CLASSES   = "classes.enc"
    private const val FILE_RES       = "res.enc"
    private const val FILE_RESOURCES = "resources.enc"

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
     * Downloads if needed → reads build.meta → derives key → decrypts into RAM
     */
    fun loadAll(context: Context): Boolean {
        if (isLoaded) return true

        return try {
            // Step 1: Download .enc files if not already cached
            if (!ensureFilesDownloaded(context)) return false

            // Step 2: Read build.meta
            val meta    = readBuildMeta(context) ?: return false
            val buildId = meta["BUILD_ID"]        ?: return false

            // Step 3: Decrypt each file from internal storage into RAM
            classesDex    = decryptFile(context, FILE_CLASSES,   buildId)
            resZip        = decryptFile(context, FILE_RES,        buildId)
            resourcesArsc = decryptFile(context, FILE_RESOURCES,  buildId)

            isLoaded = true
            true
        } catch (e: Exception) {
            wipeAll()
            false
        }
    }

    // ── Wipe All From RAM ─────────────────────────────────────────────────────
    fun wipeAll() {
        classesDex?.fill(0)
        resZip?.fill(0)
        resourcesArsc?.fill(0)
        classesDex    = null
        resZip        = null
        resourcesArsc = null
        isLoaded      = false
    }

    // ── Ensure All Files Are Downloaded ──────────────────────────────────────
    private fun ensureFilesDownloaded(context: Context): Boolean {
        val files = listOf(FILE_META, FILE_CLASSES, FILE_RES, FILE_RESOURCES)
        for (name in files) {
            val dest = File(context.filesDir, name)
            if (!dest.exists() || dest.length() == 0L) {
                val ok = downloadFile("$GITHUB_BASE/$name", dest)
                if (!ok) return false
            }
        }
        return true
    }

    // ── Download Single File ──────────────────────────────────────────────────
    private fun downloadFile(urlStr: String, dest: File): Boolean {
        return try {
            val url  = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout    = 30000
            conn.requestMethod  = "GET"
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false

            val tmp = File(dest.parent, dest.name + ".tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) output.write(buf, 0, n)
                }
            }
            conn.disconnect()
            tmp.renameTo(dest)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ── Read build.meta From Internal Storage ─────────────────────────────────
    private fun readBuildMeta(context: Context): Map<String, String>? {
        return try {
            val file  = File(context.filesDir, FILE_META)
            val lines = file.readLines()
            lines.associate { line ->
                val parts = line.split("=", limit = 2)
                parts[0].trim() to parts[1].trim()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Decrypt Single File From Internal Storage Into RAM ────────────────────
    private fun decryptFile(
        context : Context,
        fileName: String,
        buildId : String
    ): ByteArray? {
        return try {
            val file = File(context.filesDir, fileName)
            if (!file.exists()) return null

            val raw = file.readBytes()

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
            val meta    = readBuildMeta(context) ?: return false
            val keyFp   = meta["KEY_FP"]         ?: return false
            val buildId = meta["BUILD_ID"]        ?: return false
            val saltHex = meta["SALT"]            ?: return false

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

    // ── Force Re-download (call when update needed) ───────────────────────────
    fun clearCache(context: Context) {
        listOf(FILE_META, FILE_CLASSES, FILE_RES, FILE_RESOURCES).forEach {
            File(context.filesDir, it).delete()
        }
        isLoaded = false
    }
}
