package com.cabine.momo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** File d'attente des SMS utiles (survit à la fermeture de l'application). */
final class CabineSmsStore {

    private static final String PREFS = "cabine_sms_queue";
    private static final String KEY = "q";
    private static final int MAX = 300;

    private CabineSmsStore() {
    }

    static synchronized JSONArray all(Context c) {
        try {
            return new JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized JSONObject add(Context c, String sender, String body, long ts) {
        try {
            JSONArray old = all(c);
            JSONObject it = new JSONObject();
            it.put("id", ts + "-" + (body == null ? 0 : Math.abs(body.hashCode())));
            it.put("sender", sender == null ? "" : sender);
            it.put("body", body == null ? "" : body);
            it.put("ts", ts);
            JSONArray out = new JSONArray();
            int start = Math.max(0, old.length() - (MAX - 1));
            for (int i = start; i < old.length(); i++) out.put(old.get(i));
            out.put(it);
            save(c, out);
            return it;
        } catch (Exception e) {
            return null;
        }
    }

    static synchronized void remove(Context c, JSONArray ids) {
        try {
            if (ids == null) return;
            JSONArray old = all(c), out = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject it = old.getJSONObject(i);
                boolean drop = false;
                for (int j = 0; j < ids.length(); j++) {
                    if (it.getString("id").equals(ids.getString(j))) {
                        drop = true;
                        break;
                    }
                }
                if (!drop) out.put(it);
            }
            save(c, out);
        } catch (Exception ignored) {
        }
    }

    private static void save(Context c, JSONArray a) {
        SharedPreferences.Editor ed = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putString(KEY, a.toString());
        ed.apply();
    }
}
