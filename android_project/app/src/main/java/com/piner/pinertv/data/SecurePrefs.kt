package com.piner.pinertv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.piner.pinertv.BuildConfig

class SecurePrefs(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "pinertv_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences("pinertv_prefs", Context.MODE_PRIVATE)
    }

    init {
        if (!prefs.contains(KEY_URL)) {
            prefs.edit()
                .putString(KEY_URL, BuildConfig.DEFAULT_NAS_URL)
                .putString(KEY_HOST, BuildConfig.DEFAULT_NAS_HOST)
                .putString(KEY_USER, BuildConfig.DEFAULT_NAS_USER)
                .putString(KEY_PASS, BuildConfig.DEFAULT_NAS_PASS)
                .putString(KEY_MAC, BuildConfig.DEFAULT_NAS_MAC)
                .putString(KEY_BROADCAST, BuildConfig.DEFAULT_NAS_BROADCAST)
                .apply()
        }
    }

    var nasUrl: String
        get() = prefs.getString(KEY_URL, BuildConfig.DEFAULT_NAS_URL).orEmpty()
        set(v) = prefs.edit().putString(KEY_URL, v).apply()

    var nasHost: String
        get() = prefs.getString(KEY_HOST, BuildConfig.DEFAULT_NAS_HOST).orEmpty()
        set(v) = prefs.edit().putString(KEY_HOST, v).apply()

    var nasUser: String
        get() = prefs.getString(KEY_USER, BuildConfig.DEFAULT_NAS_USER).orEmpty()
        set(v) = prefs.edit().putString(KEY_USER, v).apply()

    var nasPass: String
        get() = prefs.getString(KEY_PASS, BuildConfig.DEFAULT_NAS_PASS).orEmpty()
        set(v) = prefs.edit().putString(KEY_PASS, v).apply()

    var nasMac: String
        get() = prefs.getString(KEY_MAC, BuildConfig.DEFAULT_NAS_MAC).orEmpty()
        set(v) = prefs.edit().putString(KEY_MAC, v).apply()

    var nasBroadcast: String
        get() = prefs.getString(KEY_BROADCAST, BuildConfig.DEFAULT_NAS_BROADCAST).orEmpty()
        set(v) = prefs.edit().putString(KEY_BROADCAST, v).apply()

    companion object {
        private const val KEY_URL = "nas_url"
        private const val KEY_HOST = "nas_host"
        private const val KEY_USER = "nas_user"
        private const val KEY_PASS = "nas_pass"
        private const val KEY_MAC = "nas_mac"
        private const val KEY_BROADCAST = "nas_broadcast"
    }
}
