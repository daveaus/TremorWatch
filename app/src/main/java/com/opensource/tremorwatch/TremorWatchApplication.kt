package com.opensource.tremorwatch

import android.app.Application
import timber.log.Timber

/**
 * Application class for TremorWatch watch app.
 * 
 * Initializes logging infrastructure and other app-wide components.
 */
class TremorWatchApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            // Debug builds: Plant debug tree with full logging
            Timber.plant(Timber.DebugTree())
        } else {
            // Release builds: Plant release tree that filters verbose logs
            Timber.plant(ReleaseTree())
        }
        
        Timber.i("TremorWatchApplication initialized (Debug=${BuildConfig.DEBUG})")
    }
    
    /**
     * Custom Timber tree for release builds.
     * Filters out verbose DEBUG logs while keeping INFO, WARN, and ERROR.
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // In release builds, only log INFO, WARN, and ERROR
            // Skip DEBUG and VERBOSE logs
            if (priority == android.util.Log.DEBUG || priority == android.util.Log.VERBOSE) {
                return
            }
            
            // Use Android Log for release builds
            when (priority) {
                android.util.Log.INFO -> android.util.Log.i(tag, message)
                android.util.Log.WARN -> android.util.Log.w(tag, message, t)
                android.util.Log.ERROR -> android.util.Log.e(tag, message, t)
            }
        }
    }
}

