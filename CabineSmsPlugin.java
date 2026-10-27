package com.cabine.momo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Lecture automatique des SMS : file d'attente, autorisations et envoi du code de connexion SIM. */
final class CabineSmsPlugin {

    private static final String[] PERMS = {
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
    };

    private final MainActivity act;
    private CabineBridge.Call pendingPerm;

    CabineSmsPlugin(MainActivity act) {
        this.act = act;
    }

    void handle(String method, CabineBridge.Call c) {
        try {
            switch (method) {
                case "getPending": {
                    JSONObject r = new JSONObject();
                    r.put("items", CabineSmsStore.all(act));
                    c.resolve(r);
                    break;
                }
                case "ack":
                    CabineSmsStore.remove(act, c.args.optJSONArray("ids"));
                    c.resolve(null);
                    break;
                case "sendCode":
                    sendCode(c);
                    break;
                case "checkPermissions":
                    c.resolve(state(granted() ? "granted" : "prompt"));
                    break;
                case "requestPermissions":
                    requestPermissions(c);
                    break;
                case "readHistory":
                    readHistory(c);
                    break;
                default:
                    c.reject("Méthode inconnue : " + method);
            }
        } catch (Exception e) {
            c.reject(e.getMessage());
        }
    }

    private boolean granted() {
        for (String p : PERMS) {
            if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private static JSONObject state(String value) {
        JSONObject r = new JSONObject();
        try {
            r.put("sms", value);
        } catch (JSONException ignored) {
        }
        return r;
    }

    private void requestPermissions(CabineBridge.Call c) {
        if (granted()) {
            c.resolve(state("granted"));
            return;
        }
        synchronized (this) {
            if (pendingPerm != null) pendingPerm.reject("Demande d'autorisation déjà en cours");
            pendingPerm = c;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.requestPermissions(PERMS, MainActivity.REQ_PERMS);
            }
        });
    }

    /** Appelé par l'activité quand l'utilisateur a répondu à la demande d'autorisation. */
    void onPermissionResult() {
        CabineBridge.Call c;
        synchronized (this) {
            c = pendingPerm;
            pendingPerm = null;
        }
        if (c != null) c.resolve(state(granted() ? "granted" : "denied"));
    }

    /** SMS reçus (type 1) et envoyés (type 2) déjà présents dans le téléphone, du plus récent au plus ancien. */
    private void readHistory(CabineBridge.Call c) throws Exception {
        if (act.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            c.reject("Autorisation SMS refusée");
            return;
        }
        long since = c.args.optLong("since", 0);
        int limit = Math.max(1, Math.min(c.args.optInt("limit", 3000), 5000));
        JSONArray items = new JSONArray();
        Cursor cur = act.getContentResolver().query(Uri.parse("content://sms"),
                new String[]{"_id", "address", "body", "date", "type"},
                "date > ? AND (type = 1 OR type = 2)", new String[]{String.valueOf(since)}, "date DESC");
        if (cur != null) {
            try {
                while (cur.moveToNext() && items.length() < limit) {
                    JSONObject it = new JSONObject();
                    it.put("id", "h" + cur.getLong(0));
                    it.put("sender", cur.getString(1) == null ? "" : cur.getString(1));
                    it.put("body", cur.getString(2) == null ? "" : cur.getString(2));
                    it.put("ts", cur.getLong(3));
                    it.put("box", cur.getInt(4) == 2 ? "sent" : "inbox");
                    items.put(it);
                }
            } finally {
                cur.close();
            }
        }
        JSONObject r = new JSONObject();
        r.put("items", items);
        c.resolve(r);
    }

    private void sendCode(CabineBridge.Call c) {
        String to = c.args.optString("to", "");
        String code = c.args.optString("code", "");
        if (to.isEmpty() || code.isEmpty()) {
            c.reject("Numéro ou code manquant");
            return;
        }
        if (act.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            c.reject("Autorisation SMS refusée");
            return;
        }
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31
                    ? act.getSystemService(SmsManager.class)
                    : SmsManager.getDefault();
            sm.sendTextMessage(to, null, "Cabine Momo : code " + code, null, null);
            c.resolve(null);
        } catch (Exception e) {
            c.reject(e.getMessage());
        }
    }
}
