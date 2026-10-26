package com.cabine.momo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;

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
