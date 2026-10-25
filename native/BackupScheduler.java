package com.cabine.momo;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Planifie et exécute la sauvegarde Excel quotidienne de 23h59 (heure du Bénin, UTC+1). */
final class BackupScheduler {
    private BackupScheduler() {}

    static final String PREFS = "cabine_backup";
    static final String K_ON = "on";
    static final String K_LAST = "last";
    static final String ACTION = "com.cabine.momo.BACKUP";
    private static final String SNAPSHOT = "snapshot.tsv";
    private static final TimeZone BJ = TimeZone.getTimeZone("GMT+01:00");
    private static final String MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String last(Context ctx) {
        return prefs(ctx).getString(K_LAST, "");
    }

    /** Jour (heure du Bénin) pour lequel une sauvegarde est due : aujourd'hui à partir de 23h59, sinon hier. */
    static String targetDay() {
        Calendar c = Calendar.getInstance(BJ);
        boolean tonight = c.get(Calendar.HOUR_OF_DAY) == 23 && c.get(Calendar.MINUTE) >= 59;
        if (!tonight) c.add(Calendar.DAY_OF_MONTH, -1);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(BJ);
        return f.format(c.getTime());
    }

    private static long nextTrigger() {
        Calendar c = Calendar.getInstance(BJ);
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 1000) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, BackupReceiver.class);
        i.setAction(ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, 4417, i, flags);
    }

    /** Programme (ou annule) la prochaine alarme de 23h59. */
    static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(ctx);
        if (!prefs(ctx).getBoolean(K_ON, false)) {
            am.cancel(pi);
            return;
        }
        long t = nextTrigger();
        try {
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact && Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            else if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            else am.set(AlarmManager.RTC_WAKEUP, t, pi);
        } catch (Exception e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, t, pi);
            } catch (Exception ignored) {
            }
        }
    }

    /** Appelé depuis l'application : enregistre l'état, les données, puis rattrape une sauvegarde manquée. */
    static synchronized void configure(Context ctx, boolean on, String data) {
        prefs(ctx).edit().putBoolean(K_ON, on).apply();
        File f = new File(ctx.getFilesDir(), SNAPSHOT);
        if (on) {
            if (data != null && !data.isEmpty()) writeFile(f, data.getBytes(StandardCharsets.UTF_8));
        } else if (f.exists()) {
            f.delete();
        }
        schedule(ctx);
        if (on) backup(ctx);
    }

    /** Crée la sauvegarde due, s'il y en a une. */
    static synchronized void backup(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            if (!p.getBoolean(K_ON, false)) return;
            String day = targetDay();
            if (p.getString(K_LAST, "").compareTo(day) >= 0) return;
            File f = new File(ctx.getFilesDir(), SNAPSHOT);
            if (!f.exists()) return;
            String tsv = new String(readFile(f), StandardCharsets.UTF_8);
            if (tsv.isEmpty()) return;
            byte[] x = BackupCore.build(tsv, day);
            if (x == null) return;
            if (save(ctx, "Cabine-Momo-sauvegarde-" + day + ".xlsx", x)) {
                p.edit().putString(K_LAST, day).apply();
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean save(Context ctx, String name, byte[] data) {
        try {
            if (Build.VERSION.SDK_INT >= 29) return saveMediaStore(ctx, name, data);
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CabineMomo");
            try {
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("dossier");
                writeFile(new File(dir, name), data);
                return true;
            } catch (Exception e) {
                // pas d'autorisation de stockage : dossier propre à l'application
                File alt = ctx.getExternalFilesDir(null);
                if (alt == null) alt = ctx.getFilesDir();
                writeFile(new File(alt, name), data);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @TargetApi(29)
    private static boolean saveMediaStore(Context ctx, String name, byte[] data) throws Exception {
        ContentResolver r = ctx.getContentResolver();
        Uri col = MediaStore.Files.getContentUri("external");
        String rel = "Documents/CabineMomo/";
        Uri target = null;
        Cursor c = r.query(col, new String[] {MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[] {name, rel}, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) target = ContentUris.withAppendedId(col, c.getLong(0));
            } finally {
                c.close();
            }
        }
        if (target == null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            target = r.insert(col, v);
        }
        if (target == null) return false;
        OutputStream o = r.openOutputStream(target, "wt");
        if (o == null) return false;
        try {
            o.write(data);
        } finally {
            o.close();
        }
        return true;
    }

    private static void writeFile(File f, byte[] data) {
        try {
            FileOutputStream o = new FileOutputStream(f, false);
            try {
                o.write(data);
            } finally {
                o.close();
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] readFile(File f) throws java.io.IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally {
            in.close();
        }
    }
}
