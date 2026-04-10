package com.mycloudgallery.desktop.data.security

import java.security.KeyStore
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Gestione credenziali desktop tramite JVM KeyStore + java.util.prefs.Preferences.
 * Equivalente JVM del TokenManager Android (che usa EncryptedSharedPreferences).
 */
class DesktopKeyStore {
    private val prefs = Preferences.userNodeForPackage(DesktopKeyStore::class.java)
    private val keyStoreFile = System.getProperty("user.home") + "/.mycloudgallery_ks.jks"

    var accessToken: String?
        get() = prefs.get(KEY_ACCESS_TOKEN, null)
        set(value) { if (value != null) prefs.put(KEY_ACCESS_TOKEN, value) else prefs.remove(KEY_ACCESS_TOKEN) }

    var refreshToken: String?
        get() = prefs.get(KEY_REFRESH_TOKEN, null)
        set(value) { if (value != null) prefs.put(KEY_REFRESH_TOKEN, value) else prefs.remove(KEY_REFRESH_TOKEN) }

    var username: String?
        get() = prefs.get(KEY_USERNAME, null)
        set(value) { if (value != null) prefs.put(KEY_USERNAME, value) else prefs.remove(KEY_USERNAME) }

    var nasIp: String?
        get() = prefs.get(KEY_NAS_IP, null)
        set(value) { if (value != null) prefs.put(KEY_NAS_IP, value) else prefs.remove(KEY_NAS_IP) }

    var tokenExpiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        set(value) { prefs.putLong(KEY_EXPIRES_AT, value) }

    val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank()

    fun clearAll() = prefs.clear()

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USERNAME = "username"
        const val KEY_NAS_IP = "nas_ip"
        const val KEY_EXPIRES_AT = "token_expires_at"
    }
}
