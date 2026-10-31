package com.cosyhome.tv;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Turns the Home alias on or off. Android 11+ refuses this to the shell, but an app may change its
 * own components. The manifest limits the sender to holders of DUMP, in practice ADB.
 *   am broadcast -f 32 -n com.famiglia.home/com.cosyhome.tv.HomeSwitch -a com.famiglia.home.HOME --ez on true
 */
public class HomeSwitch extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        boolean on = intent.getBooleanExtra("on", false);
        ctx.getPackageManager().setComponentEnabledSetting(
                new ComponentName(ctx.getPackageName(), "com.cosyhome.tv.HomeAlias"),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
