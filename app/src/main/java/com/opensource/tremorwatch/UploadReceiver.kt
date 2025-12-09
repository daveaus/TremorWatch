package com.opensource.tremorwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opensource.tremorwatch.service.TremorService

class UploadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.opensource.tremorwatch.ACTION_UPLOAD") {
            context.startService(Intent(context, TremorService::class.java).apply {
                action = "ACTION_UPLOAD"
            })
        }
    }
}
