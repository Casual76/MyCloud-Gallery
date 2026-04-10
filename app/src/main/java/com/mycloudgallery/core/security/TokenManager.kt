package com.mycloudgallery.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestisce token di autenticazione in modo sicuro tramite Android Keystore.
 * Supporta credenziali per più utenti (multi-user).
 */
@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            "mycloudgallery_auth",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var tokenExpiresAt: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRES_AT, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var nasLocalIp: String?
        get() = prefs.getString(KEY_NAS_LOCAL_IP, null)
        set(value) = prefs.edit().putString(KEY_NAS_LOCAL_IP, value).apply()

    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    val isLoggedIn: Boolean
        get() = !accessToken.isNullOrBlank()

    val isTokenExpired: Boolean
        get() = System.currentTimeMillis() >= tokenExpiresAt

    fun saveTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.tokenExpiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
        const val KEY_USERNAME = "username"
        const val KEY_NAS_LOCAL_IP = "nas_local_ip"
        const val KEY_DEVICE_NAME = "device_name"
    }
}
