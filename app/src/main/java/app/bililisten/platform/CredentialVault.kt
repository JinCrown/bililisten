package app.bililisten.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialVault(context: Context, private val namespace: String = "session") : app.bililisten.shared.CredentialStore {
    init { require(namespace.matches(Regex("[a-z-]+"))) }
    private val file = File(context.noBackupFilesDir, "$namespace.enc")
    private val alias = "bili-listen-m0-$namespace"
    @Volatile private var memory: String? = null
    @Volatile override var generation: Long = 0; private set
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun restore() {
        if (!file.exists()) return
        try {
            val parts = file.readText().split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            memory = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) { clear() }
    }
    override fun read(): String? = memory
    @Synchronized override fun save(cookie: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(cookie.toByteArray(Charsets.UTF_8))
        val next = File(file.parentFile, "$namespace.enc.tmp")
        next.writeText(Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        check(next.renameTo(file)) { "无法保存登录状态" }
        memory = cookie
        generation++
    }
    @Synchronized override fun clear() {
        memory = null
        generation++
        file.delete()
        File(file.parentFile, "$namespace.enc.tmp").delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
