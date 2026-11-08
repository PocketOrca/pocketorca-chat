package com.pocketorca.chat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * B1a: BootReceiver — restarts PollScheduler after device reboot.
 * Registered in AndroidManifest.xml with RECEIVE_BOOT_COMPLETED permission.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("android.intent.action.BOOT_COMPLETED".equals(intent.getAction())) {
            Intent service = new Intent(context, PollScheduler.class);
            context.startService(service);
        }
    }
}
