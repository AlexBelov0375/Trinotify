package com.trinotify.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Пароль базы данных для SQLCipher. Сам пароль — случайные 32 байта, зашифрованные
 * AES-ключом из аппаратного Android Keystore (ключ не покидает железо). Ключ доступен
 * приложению без ввода пина, поэтому фоновый сервис работает всегда; но после
 * перезагрузки до первой разблокировки экрана Keystore закрыт — это учитывает Db.
 */
object KeyVault {
    private const val ALIAS = "trinotify_db_key"
    private const val PREFS = "trinotify_vault"
    private const val PASS_KEY = "db_pass"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Сбрасывает пароль и мастер-ключ. Нужен, когда зашифрованная БД стала нечитаемой. */
    fun reset(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(PASS_KEY).apply()
        }
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
    }

    /** Возвращает пароль БД, при первом вызове создаёт его. Бросает, если Keystore недоступен. */
    fun dbPassphrase(context: Context): ByteArray {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(PASS_KEY, null)?.let { stored ->
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, masterKey(),
                GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_LEN)
            )
            return cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN)
        }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val blob = cipher.iv + cipher.doFinal(pass)
        sp.edit().putString(PASS_KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        return pass
    }
}
