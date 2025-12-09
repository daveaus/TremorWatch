package com.opensource.tremorwatch.phone

import android.app.Application
import timber.log.Timber

/**
 * Custom Application class for TremorWatch Phone app.
 * Initializes logging and other app-wide configurations.
 */
class TremorWatchPhoneApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            // Debug: Plant a DebugTree that logs with class name as tag
            Timber.plant(Timber.DebugTree())
            Timber.d("TremorWatch Phone App initialized (Debug build)")
        } else {
            // Release: Plant a tree that doesn't log to logcat
            // In production, you could plant a crash reporting tree here
            Timber.plant(ReleaseTree())
            Timber.i("TremorWatch Phone App initialized (Release build)")
        }
    }

    /**
     * Release build tree - only logs warnings and errors
     * Does not log debug/info messages to protect user privacy
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Only log warnings and errors in release builds
            if (priority >= android.util.Log.WARN) {
                // In a production app, you could send these to a crash reporting service
                android.util.Log.println(priority, tag ?: "TremorWatch", message)
            }
        }
    }
}
