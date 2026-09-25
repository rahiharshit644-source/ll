package com.soltini.app.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * IncomingCallReceiver
 *
 * BroadcastReceiver listening for TelephonyManager.ACTION_PHONE_STATE_CHANGED
 * to detect incoming ringing phone calls even when background voice service was idle.
 */
class IncomingCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IncomingCallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        try {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            Log.i(TAG, "PHONE_STATE broadcast: state=$stateStr")

            val callManager = CallNotificationManager.getInstance(context)

            when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    @Suppress("DEPRECATION")
                    var incomingNumber = try {
                        intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    } catch (_: Exception) {
                        null
                    }
                    var callerName = callManager.resolveContactName(incomingNumber)
                    if (callerName == "Unknown Caller" || callerName.isBlank()) {
                        val callInfo = callManager.getCurrentCallInfo()
                        if (!callInfo.callerName.isNullOrBlank() && !callInfo.callerName.equals("Unknown Caller", ignoreCase = true)) {
                            callerName = callInfo.callerName
                            if (incomingNumber.isNullOrBlank()) incomingNumber = callInfo.phoneNumber
                        }
                    }
                    Log.i(TAG, "Incoming ringing call from: $callerName ($incomingNumber)")
                    callManager.onIncomingCall(callerName, incomingNumber)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    Log.i(TAG, "Call idle / ended")
                    callManager.onCallEnded()
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    Log.i(TAG, "Call offhook / answered")
                    callManager.onCallOffhook()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling PHONE_STATE broadcast: ${e.message}")
        }
    }
}
