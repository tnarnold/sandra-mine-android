package io.sandramine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts mining again after the TV boots (and after an app update). */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        MinerService.ensure(c);
    }
}
