package com.example.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val ciphertextBase64: String,
    val ivBase64: String
)

data class RoomKeyBundle(
    val roomKeyBase64: String,
    val keyFingerprint: String
)

data class IdentityKeyMaterial(
    val publicKeyBase64: String,
    val fingerprint: String
)

data class EncryptedMediaResult(
    val localVaultFile: File,
    val ivBase64: String,
    val sha256Hash: String,
    val encryptedPreviewBase64: String?,
    val previewIvBase64: String?,
    val totalBytesProcessed: Long
)

/**
 * Genuine client-side End-to-End Encryption engine for Nexus.
 * Uses AES-256-GCM (96-bit random IV, 128-bit auth tag) for all messages and
 * streaming chunked encryption (64 KB constant-memory buffer) for files up to 4.00 GB.
 */
object NexusCryptoEngine {

    private const val AES_ALGORITHM = "AES"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private val secureRandom = SecureRandom()

    fun generateRoomKey(): RoomKeyBundle {
        val keyGen = KeyGenerator.getInstance(AES_ALGORITHM)
        keyGen.init(256, secureRandom)
        val secretKey: SecretKey = keyGen.generateKey()
        val keyBytes = secretKey.encoded
        val base64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val fingerprint = formatShortFingerprint(sha256Hex(keyBytes))
        return RoomKeyBundle(roomKeyBase64 = base64, keyFingerprint = fingerprint)
    }

    fun generateIdentityKeyPair(): IdentityKeyMaterial {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val keyPair = generator.generateKeyPair()
        val pubBytes = keyPair.public.encoded
        val pubBase64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP)
        val fingerprint = formatIdentityFingerprint(sha256Hex(pubBytes))
        return IdentityKeyMaterial(publicKeyBase64 = pubBase64, fingerprint = fingerprint)
    }

    fun generateInviteCode(length: Int = 8): String {
        val chars = CharArray(length)
        for (i in 0 until length) {
            chars[i] = INVITE_ALPHABET[secureRandom.nextInt(INVITE_ALPHABET.length)]
        }
        return String(chars)
    }

    fun encryptText(plaintext: String, roomKeyBase64: String): EncryptedPayload {
        val encrypted = encryptBytes(plaintext.toByteArray(StandardCharsets.UTF_8), roomKeyBase64)
        return encrypted
    }

    fun decryptText(ciphertextBase64: String, ivBase64: String, roomKeyBase64: String): String {
        val bytes = decryptBytes(ciphertextBase64, ivBase64, roomKeyBase64)
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun encryptBytes(plainBytes: ByteArray, roomKeyBase64: String): EncryptedPayload {
        val keyBytes = Base64.decode(roomKeyBase64, Base64.NO_WRAP)
        val keySpec = SecretKeySpec(keyBytes, AES_ALGORITHM)
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plainBytes)
        return EncryptedPayload(
            ciphertextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decryptBytes(ciphertextBase64: String, ivBase64: String, roomKeyBase64: String): ByteArray {
        val keyBytes = Base64.decode(roomKeyBase64, Base64.NO_WRAP)
        val keySpec = SecretKeySpec(keyBytes, AES_ALGORITHM)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val cipherBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }

    /**
     * Streams any file (up to 4.00 GB) through AES-256-GCM into app-private vault cache using
     * a fixed 64 KB buffer while computing a SHA-256 integrity digest. Also generates an
     * AES-256-GCM encrypted thumbnail preview for images so all room members can decrypt it.
     */
    suspend fun encryptMediaStream(
        context: Context,
        sourceUri: Uri,
        dropId: String,
        mimeType: String,
        expectedSizeBytes: Long,
        roomKeyBase64: String,
        onProgress: (Float) -> Unit
    ): EncryptedMediaResult = withContext(Dispatchers.IO) {
        val vaultDir = File(context.cacheDir, "nexus_vault").apply { mkdirs() }
        val outFile = File(vaultDir, "$dropId.enc")

        val keyBytes = Base64.decode(roomKeyBase64, Base64.NO_WRAP)
        val keySpec = SecretKeySpec(keyBytes, AES_ALGORITHM)
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))

        val digest = MessageDigest.getInstance("SHA-256")
        var bytesProcessed = 0L

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(outFile).use { fileOut ->
                CipherOutputStream(fileOut, cipher).use { cipherOut ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                        cipherOut.write(buffer, 0, read)
                        bytesProcessed += read
                        if (expectedSizeBytes > 0L) {
                            onProgress((bytesProcessed.toFloat() / expectedSizeBytes.toFloat()).coerceIn(0f, 0.95f))
                        }
                    }
                }
            }
        } ?: throw IllegalStateException("Unable to open selected file stream.")

        val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }

        var previewCipherBase64: String? = null
        var previewIvBase64: String? = null
        if (mimeType.startsWith("image/")) {
            val thumbnailBytes = createCompressedPreview(context, sourceUri)
            if (thumbnailBytes != null && thumbnailBytes.isNotEmpty()) {
                val encPreview = encryptBytes(thumbnailBytes, roomKeyBase64)
                if (encPreview.ciphertextBase64.length <= 240_000) {
                    previewCipherBase64 = encPreview.ciphertextBase64
                    previewIvBase64 = encPreview.ivBase64
                }
            }
        }

        onProgress(1f)

        EncryptedMediaResult(
            localVaultFile = outFile,
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            sha256Hash = sha256Hex,
            encryptedPreviewBase64 = previewCipherBase64,
            previewIvBase64 = previewIvBase64,
            totalBytesProcessed = bytesProcessed.coerceAtLeast(1L)
        )
    }

    /**
     * Securely overwrites the leading block of an encrypted temporary vault file with zeros
     * and deletes it from app cache when a media drop expires or is burned.
     */
    suspend fun shredLocalVaultFile(context: Context, dropId: String) = withContext(Dispatchers.IO) {
        val file = File(File(context.cacheDir, "nexus_vault"), "$dropId.enc")
        if (file.exists()) {
            runCatching {
                RandomAccessFile(file, "rw").use { raf ->
                    val wipeLen = minOf(raf.length(), 131_072L).toInt()
                    if (wipeLen > 0) {
                        raf.seek(0)
                        raf.write(ByteArray(wipeLen))
                    }
                }
            }
            file.delete()
        }
    }

    fun hasLocalEncryptedVaultFile(context: Context, dropId: String): Boolean {
        return File(File(context.cacheDir, "nexus_vault"), "$dropId.enc").exists()
    }

    private fun createCompressedPreview(context: Context, uri: Uri): ByteArray? {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val maxDim = 640
            var sampleSize = 1
            while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, baos)
            bitmap.recycle()
            baos.toByteArray()
        }.getOrNull()
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun formatShortFingerprint(hex: String): String {
        val upper = hex.uppercase()
        return listOf(
            upper.substring(0, 4),
            upper.substring(4, 8),
            upper.substring(8, 12),
            upper.substring(12, 16)
        ).joinToString("-")
    }

    private fun formatIdentityFingerprint(hex: String): String {
        val upper = hex.uppercase()
        return (0 until 32 step 4).map { upper.substring(it, it + 4) }.joinToString(" ")
    }
}
