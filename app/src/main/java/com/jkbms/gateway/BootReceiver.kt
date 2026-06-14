package com.jkbms.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jkbms.gateway.ble.BmsService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED)
            BmsService.start(context)
    }
}
