package com.cabine.momo;

import android.content.Context;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Pont entre l'application (JavaScript) et la sauvegarde Android de 23h59. */
@CapacitorPlugin(name = "CabineBackup")
public class BackupPlugin extends Plugin {
    @PluginMethod
    public void configure(final PluginCall call) {
        final Boolean en = call.getBoolean("enabled", false);
        final boolean on = en != null && en.booleanValue();
        final String data = call.getString("data", "");
        final Context ctx = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BackupScheduler.configure(ctx, on, data);
                    JSObject r = new JSObject();
                    r.put("last", BackupScheduler.last(ctx));
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject("sauvegarde: " + e.getMessage());
                }
            }
        }).start();
    }
}
