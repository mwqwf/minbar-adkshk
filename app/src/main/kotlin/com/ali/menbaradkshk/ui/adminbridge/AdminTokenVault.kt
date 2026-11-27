package com.ali.menbaradkshk.ui.adminbridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** This package's own token only; both the Keystore key and ciphertext are outside app backups. */
internal class AdminTokenVault(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "minbar_admin_v1"))
    private val keyAlias = "Minbar.AdminSession.V1"

    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(create: Boolean): SecretKey {
        (keys().getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("Admin storage key unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    fun read(): String? {
        val bytes = try {
            file.openRead().use { stream ->
                val bytes = ByteArray(4097)
                var next = 0
                while (next < bytes.size) {
                    val n = stream.read(bytes, next, bytes.size - next)
                    if (n < 0) break
                    next += n
                }
                bytes.copyOf(next)
            }
        } catch (e: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw e
            return null
        }
        if (bytes.size !in 29..4096 || bytes[0] != VERSION) throw IOException("Invalid admin storage")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes, 1, 12))
        }
        return cipher.doFinal(bytes, 13, bytes.size - 13).toString(Charsets.UTF_8)
    }

    fun write(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        check(cipher.iv.size == 12)
        val bytes = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun clear() {
        val ks = keys()
        ks.deleteEntry(keyAlias)
        file.delete()
    }

    private companion object { const val VERSION: Byte = 1 }
}
