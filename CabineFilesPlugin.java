package com.cabine.momo;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Enregistrement des fichiers Excel (Documents/CabineMomo) et partage. */
final class CabineFilesPlugin {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final MainActivity act;

    CabineFilesPlugin(MainActivity act) {
        this.act = act;
    }

    void handleFilesystem(String method, CabineBridge.Call c) {
        if (!"writeFile".equals(method)) {
            c.reject("Méthode inconnue : " + method);
            return;
        }
        try {
            String path = c.args.optString("path", "fichier");
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.isEmpty()) name = "fichier";
            byte[] bytes = Base64.decode(c.args.optString("data", ""), Base64.DEFAULT);

            String uri;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime(name));
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/CabineMomo");
                Uri u = act.getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
                if (u == null) throw new IOException("Création du fichier impossible");
                OutputStream os = act.getContentResolver().openOutputStream(u);
                if (os == null) throw new IOException("Écriture du fichier impossible");
                try {
                    os.write(bytes);
                } finally {
                    os.close();
                }
                uri = u.toString();
            } else {
                File dir = new File(act.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "CabineMomo");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Création du dossier impossible");
                File f = new File(dir, name);
                FileOutputStream os = new FileOutputStream(f);
                try {
                    os.write(bytes);
                } finally {
                    os.close();
                }
                uri = Uri.fromFile(f).toString();
            }
            JSONObject r = new JSONObject();
            r.put("uri", uri);
            c.resolve(r);
        } catch (Exception e) {
            c.reject(e.getMessage());
        }
    }

    void handleShare(String method, CabineBridge.Call c) {
        if (!"share".equals(method)) {
            c.reject("Méthode inconnue : " + method);
            return;
        }
        final String url = c.args.optString("url", "");
        final String title = c.args.optString("title", "");
        final String dialog = c.args.optString("dialogTitle", "Partager");
        if (url.isEmpty()) {
            c.reject("Fichier manquant");
            return;
        }
        final CabineBridge.Call call = c;
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(mime(title));
                    i.putExtra(Intent.EXTRA_STREAM, Uri.parse(url));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    act.startActivity(Intent.createChooser(i, dialog));
                    call.resolve(null);
                } catch (Exception e) {
                    call.reject(e.getMessage());
                }
            }
        });
    }

    private static String mime(String name) {
        return name != null && name.toLowerCase().endsWith(".xlsx") ? XLSX : "application/octet-stream";
    }
}
