package com.opensource.tremorwatch.phone.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber

/**
 * Manager for encrypted secure storage using EncryptedSharedPreferences.
 *
 * This class provides encrypted storage for sensitive data like passwords and tokens.
 * Uses AES256-GCM encryption with hardware-backed keys when available.
 *
 * Architecture: Singleton pattern with lazy initialization
 */
object SecurePreferencesManager {

    private const val SECURE_PREFS_NAME = "tremorwatch_secure_prefs"
    private const val TAG = "SecurePreferences"

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    /**
     * Get or create the EncryptedSharedPreferences instance.
     * Thread-safe with double-checked locking.
     */
    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        return encryptedPrefs ?: synchronized(this) {
            encryptedPrefs ?: try {
                // Create master key with AES256-GCM scheme
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                // Create encrypted preferences
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also {
                    encryptedPrefs = it
                    Timber.d("EncryptedSharedPreferences initialized successfully")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to create EncryptedSharedPreferences, falling back to regular SharedPreferences")
                // Fallback to regular SharedPreferences if encryption fails
                context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    /**
     * Store an encrypted string value.
     */
    fun putString(context: Context, key: String, value: String) {
        try {
            getEncryptedPrefs(context).edit().putString(key, value).apply()
            Timber.d("Stored encrypted value for key: $key")
        } catch (e: Exception) {
            Timber.e(e, "Failed to store encrypted value for key: $key")
        }
    }

    /**
     * Retrieve an encrypted string value.
     */
    fun getString(context: Context, key: String, defaultValue: String = ""): String {
        return try {
            getEncryptedPrefs(context).getString(key, defaultValue) ?: defaultValue
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve encrypted value for key: $key")
            defaultValue
        }
    }

    /**
     * Store an encrypted boolean value.
     */
    fun putBoolean(context: Context, key: String, value: Boolean) {
        try {
            getEncryptedPrefs(context).edit().putBoolean(key, value).apply()
            Timber.d("Stored encrypted boolean for key: $key")
        } catch (e: Exception) {
            Timber.e(e, "Failed to store encrypted boolean for key: $key")
        }
    }

    /**
     * Retrieve an encrypted boolean value.
     */
    fun getBoolean(context: Context, key: String, defaultValue: Boolean = false): Boolean {
        return try {
            getEncryptedPrefs(context).getBoolean(key, defaultValue)
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve encrypted boolean for key: $key")
            defaultValue
        }
    }

    /**
     * Store an encrypted integer value.
     */
    fun putInt(context: Context, key: String, value: Int) {
        try {
            getEncryptedPrefs(context).edit().putInt(key, value).apply()
            Timber.d("Stored encrypted int for key: $key")
        } catch (e: Exception) {
            Timber.e(e, "Failed to store encrypted int for key: $key")
        }
    }

    /**
     * Retrieve an encrypted integer value.
     */
    fun getInt(context: Context, key: String, defaultValue: Int = 0): Int {
        return try {
            getEncryptedPrefs(context).getInt(key, defaultValue)
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve encrypted int for key: $key")
            defaultValue
        }
    }

    /**
     * Remove an encrypted value.
     */
    fun remove(context: Context, key: String) {
        try {
            getEncryptedPrefs(context).edit().remove(key).apply()
            Timber.d("Removed encrypted value for key: $key")
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove encrypted value for key: $key")
        }
    }

    /**
     * Clear all encrypted values.
     * Use with caution!
     */
    fun clear(context: Context) {
        try {
            getEncryptedPrefs(context).edit().clear().apply()
            Timber.w("Cleared all encrypted preferences")
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear encrypted preferences")
        }
    }

    /**
     * Check if a key exists in encrypted storage.
     */
    fun contains(context: Context, key: String): Boolean {
        return try {
            getEncryptedPrefs(context).contains(key)
        } catch (e: Exception) {
            Timber.e(e, "Failed to check if key exists: $key")
            false
        }
    }

    /**
     * Migrate a value from regular SharedPreferences to encrypted storage.
     * This is useful when upgrading from plain to encrypted storage.
     *
     * @param plainPrefs The regular SharedPreferences instance
     * @param key The key to migrate
     * @return true if migration succeeded or key doesn't exist, false on error
     */
    fun migrateFromPlainPrefs(
        context: Context,
        plainPrefs: SharedPreferences,
        key: String
    ): Boolean {
        return try {
            if (plainPrefs.contains(key)) {
                val value = plainPrefs.getString(key, null)
                if (value != null) {
                    putString(context, key, value)
                    plainPrefs.edit().remove(key).apply()
                    Timber.i("Migrated key to encrypted storage: $key")
                }
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to migrate key: $key")
            false
        }
    }
}
