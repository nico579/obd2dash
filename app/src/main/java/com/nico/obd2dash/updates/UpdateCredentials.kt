package com.nico.obd2dash.updates

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Aucun secret dans l'APK, les logs, les sauvegardes Android ou l'état sauvegardé. */
internal class UpdateCredentials(context: Context) {
    private val preferences = context.getSharedPreferences("update_access", Context.MODE_PRIVATE)
    private val alias = "obd2dash-update-access"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun read(): String {
        val encrypted = preferences.getString("encrypted_token", null) ?: return ""
        return try {
            val parts = encrypted.split(':')
            if (parts.size != 2) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Une clé perdue/inaccessible ne provoque ni crash ni stockage en clair.
            ""
        }
    }

    fun save(raw: String) {
        val token = validatedUpdateToken(raw)
        if (token.isEmpty()) {
            require(preferences.edit().clear().commit()) { "Impossible de supprimer l’accès GitHub." }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        require(preferences.edit().putString("encrypted_token", value).commit()) { "Impossible d’enregistrer l’accès GitHub." }
    }
}
