package com.opensource.tremorwatch.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver for periodic batch retry alarms.
 * Triggers pending batch uploads every 2 minutes if batches exist.
 */
class BatchRetryAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("BatchRetry", "Alarm triggered - checking for pending batches")
        MainActivity.sendPendingBatches(context)
    }
}
