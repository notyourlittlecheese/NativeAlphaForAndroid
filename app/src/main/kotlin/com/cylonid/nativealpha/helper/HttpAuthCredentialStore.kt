package com.cylonid.nativealpha.helper

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class HttpAuthCredentials(val username: String, val password: String)

object HttpAuthCredentialStore {
    private const val PREFS_NAME = "http_auth_credentials"
    private const val USERNAME_SUFFIX = "_username"
    private const val PASSWORD_SUFFIX = "_password"

    fun get(context: Context, host: String?, realm: String?): HttpAuthCredentials? {
        val prefs = encryptedPrefs(context)
        val key = key(host, realm)
        val username = prefs.getString(key + USERNAME_SUFFIX, null)
        val password = prefs.getString(key + PASSWORD_SUFFIX, null)
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) return null
        return HttpAuthCredentials(username, password)
    }

    fun getForHost(context: Context, host: String?): HttpAuthCredentials? {
        val normalizedHost = host.orEmpty().trim()
        if (normalizedHost.isEmpty()) return null

        val prefs = encryptedPrefs(context)
        val usernameKey = prefs.all.keys.firstOrNull {
            it.startsWith("$normalizedHost|") && it.endsWith(USERNAME_SUFFIX)
        } ?: return null

        val key = usernameKey.removeSuffix(USERNAME_SUFFIX)
        val username = prefs.getString(key + USERNAME_SUFFIX, null)
        val password = prefs.getString(key + PASSWORD_SUFFIX, null)
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) return null
        return HttpAuthCredentials(username, password)
    }

    fun save(context: Context, host: String?, realm: String?, username: String, password: String) {
        encryptedPrefs(context).edit()
            .putString(key(host, realm) + USERNAME_SUFFIX, username)
            .putString(key(host, realm) + PASSWORD_SUFFIX, password)
            .apply()
    }

    private fun key(host: String?, realm: String?): String {
        return "${host.orEmpty().trim()}|${realm.orEmpty().trim()}"
    }

    private fun encryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
