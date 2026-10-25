package com.cabine.momo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reçoit l'alarme de 23h59, le redémarrage du téléphone et la mise à jour de l'application. */
public class BackupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BackupScheduler.backup(app);
                    BackupScheduler.schedule(app);
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }
}
