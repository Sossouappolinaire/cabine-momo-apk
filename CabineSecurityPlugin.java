package com.cabine.momo;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Base64;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Executor;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Verrouillage de l'application : code PIN à 6 chiffres (haché) et empreinte digitale. */
final class CabineSecurityPlugin {

    private static final int ITERATIONS = 50000;

    private final MainActivity act;
    private final SharedPreferences sp;

    CabineSecurityPlugin(MainActivity act) {
        this.act = act;
        this.sp = act.getSharedPreferences("cabine_security", Context.MODE_PRIVATE);
    }

    void handle(String method, CabineBridge.Call c) {
        try {
            switch (method) {
                case "getState":
                    c.resolve(state());
                    break;
                case "setPin":
                    c.resolve(setPin(c.args.optString("pin", ""), c.args.optString("oldPin", "")));
                    break;
                case "verifyPin":
                    c.resolve(verify(c.args.optString("pin", "")));
                    break;
                case "disablePin": {
                    JSONObject r = verify(c.args.optString("pin", ""));
                    if (r.optBoolean("ok")) clearAll();
                    c.resolve(r);
                    break;
                }
                case "clearPin":
                    clearAll();
                    c.resolve(null);
                    break;
                case "setBiometricEnabled":
                    c.resolve(setBiometric(c.args.optBoolean("enabled", false)));
                    break;
                case "authenticateBiometric":
                    authenticate(c);
                    break;
                default:
                    c.reject("Méthode inconnue : " + method);
            }
        } catch (Exception e) {
            c.reject(e.getMessage());
        }
    }

    // ---------- état ----------

    private boolean pinSet() {
        return sp.getString("hash", null) != null && sp.getString("salt", null) != null;
    }

    private JSONObject state() throws Exception {
        boolean avail = biometricAvailable();
        JSONObject r = new JSONObject();
        r.put("pinSet", pinSet());
        r.put("biometricAvailable", avail);
        r.put("biometricEnabled", pinSet() && avail && sp.getBoolean("bio", false));
        return r;
    }

    private static JSONObject ok(boolean value) {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", value);
        } catch (Exception ignored) {
        }
        return r;
    }

    private void clearAll() {
        sp.edit().clear().apply();
    }

    // ---------- code PIN ----------

    private static byte[] hash(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
    }

    private long retryAfter() {
        return Math.max(0L, sp.getLong("lockUntil", 0L) - System.currentTimeMillis());
    }

    private JSONObject verify(String pin) throws Exception {
        JSONObject r = new JSONObject();
        long wait = retryAfter();
        if (wait > 0) {
            r.put("ok", false);
            r.put("retryAfterMs", wait);
            return r;
        }
        boolean good = false;
        if (pinSet() && pin.matches("[0-9]{6}")) {
            byte[] salt = Base64.decode(sp.getString("salt", ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(sp.getString("hash", ""), Base64.NO_WRAP);
            good = java.security.MessageDigest.isEqual(expected, hash(pin, salt));
        }
        if (good) {
            sp.edit().putInt("fails", 0).putLong("lockUntil", 0L).apply();
            r.put("ok", true);
            return r;
        }
        int fails = sp.getInt("fails", 0) + 1;
        long lock = 0L;
        if (fails >= 5) {
            lock = System.currentTimeMillis() + Math.min(30000L << Math.min(fails - 5, 5), 900000L);
        }
        sp.edit().putInt("fails", fails).putLong("lockUntil", lock).apply();
        r.put("ok", false);
        r.put("retryAfterMs", retryAfter());
        return r;
    }

    private JSONObject setPin(String pin, String oldPin) throws Exception {
        if (!pin.matches("[0-9]{6}")) return ok(false);
        if (pinSet()) {
            JSONObject check = verify(oldPin);
            if (!check.optBoolean("ok")) return check;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] h = hash(pin, salt);
        boolean bio = sp.getBoolean("bio", false);
        sp.edit().clear()
                .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("hash", Base64.encodeToString(h, Base64.NO_WRAP))
                .putBoolean("bio", bio)
                .apply();
        Arrays.fill(salt, (byte) 0);
        return ok(true);
    }

    // ---------- biométrie ----------

    private boolean biometricAvailable() {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            BiometricManager bm = act.getSystemService(BiometricManager.class);
            return bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject setBiometric(boolean enabled) {
        if (!pinSet()) return ok(false);
        if (enabled && !biometricAvailable()) return ok(false);
        sp.edit().putBoolean("bio", enabled).apply();
        return ok(true);
    }

    private void authenticate(final CabineBridge.Call c) {
        if (!pinSet() || !sp.getBoolean("bio", false) || !biometricAvailable()) {
            c.resolve(ok(false));
            return;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Executor ex = act.getMainExecutor();
                    BiometricPrompt prompt = new BiometricPrompt.Builder(act)
                            .setTitle("Cabine Momo")
                            .setSubtitle("Déverrouillez l'application")
                            .setNegativeButton("Utiliser le code PIN", ex,
                                    new android.content.DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(android.content.DialogInterface d, int which) {
                                            c.resolve(ok(false));
                                        }
                                    })
                            .build();
                    prompt.authenticate(new CancellationSignal(), ex,
                            new BiometricPrompt.AuthenticationCallback() {
                                @Override
                                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                    c.resolve(ok(true));
                                }

                                @Override
                                public void onAuthenticationError(int errorCode, CharSequence errString) {
                                    c.resolve(ok(false));
                                }
                            });
                } catch (Exception e) {
                    c.resolve(ok(false));
                }
            }
        });
    }
}
