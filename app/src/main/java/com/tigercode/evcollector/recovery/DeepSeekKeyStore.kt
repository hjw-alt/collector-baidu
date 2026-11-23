package com.tigercode.evcollector.recovery

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object DeepSeekKeyStore {
    private const val ALIAS = "baidu_ai_recovery_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private val keyFormat = Regex("sk-[A-Za-z0-9_-]{12,256}")

    fun isConfigured(context: Context): Boolean = file(context).baseFile.isFile

    fun isValidFormat(key: String): Boolean = keyFormat.matches(key)

    @Synchronized
    fun read(context: Context): String? = runCatching {
        val file = file(context)
        if (!file.baseFile.exists()) return null
        val data = file.readFully()
        require(data.size in (IV_BYTES + 17)..1024)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, keyStore().getKey(ALIAS, null) as SecretKey,
            GCMParameterSpec(128, data.copyOf(IV_BYTES)))
        cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES).toString(Charsets.UTF_8)
            .takeIf(keyFormat::matches)
    }.getOrNull()

    @Synchronized
    fun save(context: Context, key: String): Boolean = runCatching {
        require(keyFormat.matches(key))
        val existing = keyStore().getKey(ALIAS, null) as? SecretKey
        val secret = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        require(cipher.iv.size == IV_BYTES)
        val data = cipher.iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val file = file(context)
        val out = file.startWrite()
        try {
            out.write(data)
            file.finishWrite(out)
        } catch (error: Exception) {
            file.failWrite(out)
            throw error
        }
        read(context) == key
    }.getOrDefault(false)

    @Synchronized
    fun disable(context: Context): Boolean = runCatching {
        file(context).delete()
        keyStore().deleteEntry(ALIAS)
        read(context) == null
    }.getOrDefault(false)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "baidu-ai-recovery.v1"))
}
