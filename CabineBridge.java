package com.cabine.momo;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

/**
 * Pont entre la page web (index.html) et Android. Il remplace Capacitor :
 * la page appelle CabineNative.call(...) (voir le petit script au début de index.html)
 * et reçoit la réponse par window.__cabineCb(...).
 */
final class CabineBridge {

    /** Un appel venant de la page. On y répond une seule fois : resolve() ou reject(). */
    static final class Call {
        final int id;
        final JSONObject args;
        private final CabineBridge bridge;

        Call(CabineBridge bridge, int id, JSONObject args) {
            this.bridge = bridge;
            this.id = id;
            this.args = args;
        }

        void resolve(JSONObject data) {
            bridge.reply(id, true, data == null ? "{}" : data.toString());
        }

        void reject(String message) {
            bridge.reply(id, false, JSONObject.quote(message == null ? "Erreur" : message));
        }
    }

    private final MainActivity act;
    private final WebView web;
    final CabineSmsPlugin sms;
    private final CabineFilesPlugin files;
    private final CabineSecurityPlugin security;

    CabineBridge(MainActivity act, WebView web) {
        this.act = act;
        this.web = web;
        this.sms = new CabineSmsPlugin(act);
        this.files = new CabineFilesPlugin(act);
        this.security = new CabineSecurityPlugin(act);
    }

    @JavascriptInterface
    public void call(int id, String plugin, String method, String json) {
        Call c;
        try {
            JSONObject args = (json == null || json.isEmpty()) ? new JSONObject() : new JSONObject(json);
            c = new Call(this, id, args);
        } catch (Exception e) {
            reply(id, false, JSONObject.quote("Paramètres illisibles"));
            return;
        }
        try {
            if ("CabineSms".equals(plugin)) {
                sms.handle(method, c);
            } else if ("Filesystem".equals(plugin)) {
                files.handleFilesystem(method, c);
            } else if ("Share".equals(plugin)) {
                files.handleShare(method, c);
            } else if ("CabineSecurity".equals(plugin)) {
                security.handle(method, c);
            } else {
                c.reject("Module inconnu : " + plugin);
            }
        } catch (Exception e) {
            c.reject(e.getMessage());
        }
    }

    void reply(final int id, final boolean ok, final String js) {
        run("window.__cabineCb(" + id + "," + ok + "," + js + ")");
    }

    void emit(String plugin, String event, String js) {
        run("window.__cabineEmit(" + JSONObject.quote(plugin) + "," + JSONObject.quote(event) + "," + js + ")");
    }

    private void run(final String script) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    web.evaluateJavascript(script, null);
                } catch (Exception ignored) {
                }
            }
        });
    }
}
