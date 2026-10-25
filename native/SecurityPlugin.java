package com.cabine.momo;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Local PIN protection and Android biometric unlock for Cabine Momo. */
@CapacitorPlugin(name = "CabineSecurity")
public class SecurityPlugin extends Plugin {
    private static final String PREFS = "cabine_momo_security";
    private static final String KEY_ALIAS = "cabine_momo_pin_v1";
    private static final String PIN_BLOB = "pin_blob";
    private static final int PIN_ITERATIONS = 150000;
    private static final long LOCKOUT_MS = 30000L;
    private static final int MAX_ATTEMPTS = 5;
    private static final int HASH_LENGTH = 32;
    private static final int SALT_LENGTH = 16;

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, 0);
    }

    private boolean hasPin(SharedPreferences p) {
        return p.getBoolean("pin_enabled", false) && !p.getString(PIN_BLOB, "").isEmpty();
    }

    private boolean biometricAvailable() {
        return BiometricManager.from(getContext()).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private JSObject state(SharedPreferences p) {
        JSObject r = new JSObject();
        r.put("pinSet", hasPin(p));
        r.put("biometricAvailable", biometricAvailable());
        r.put("biometricEnabled", hasPin(p) && p.getBoolean("biometric_enabled", false));
        return r;
    }

    @PluginMethod
    public void getState(PluginCall call) {
        call.resolve(state(prefs()));
    }

    private void background(final PluginCall call, final Work work) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    work.run();
                } catch (Exception e) {
                    call.reject("Le verrouillage n’a pas pu être vérifié sur ce téléphone.");
                }
            }
        }, "CabineSecurity").start();
    }

    private interface Work {
        void run() throws Exception;
    }

    private SecretKey aesKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private String encrypt(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain);
        ByteBuffer out = ByteBuffer.allocate(1 + iv.length + encrypted.length);
        out.put((byte) iv.length);
        out.put(iv);
        out.put(encrypted);
        return Base64.encodeToString(out.array(), Base64.NO_WRAP);
    }

    private byte[] decrypt(String encoded) throws Exception {
        byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
        ByteBuffer in = ByteBuffer.wrap(all);
        int ivLength = in.get() & 0xff;
        if (ivLength < 12 || ivLength > 16 || in.remaining() <= ivLength) {
            throw new IllegalStateException("invalid PIN record");
        }
        byte[] iv = new byte[ivLength];
        in.get(iv);
        byte[] encrypted = new byte[in.remaining()];
        in.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(128, iv));
        return cipher.doFinal(encrypted);
    }

    private byte[] derive(String pin, byte[] salt) throws Exception {
        byte[] password = pin.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        byte[] block = ByteBuffer.allocate(salt.length + 4).put(salt).putInt(1).array();
        byte[] u = mac.doFinal(block);
        byte[] result = u.clone();
        for (int i = 1; i < PIN_ITERATIONS; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < result.length; j++) {
                result[j] ^= u[j];
            }
        }
        Arrays.fill(password, (byte) 0);
        Arrays.fill(u, (byte) 0);
        return result;
    }

    private boolean matches(SharedPreferences p, String pin) throws Exception {
        if (!hasPin(p) || pin == null || !pin.matches("[0-9]{6}")) return false;
        byte[] record = decrypt(p.getString(PIN_BLOB, ""));
        if (record.length != 1 + SALT_LENGTH + HASH_LENGTH || record[0] != 1) return false;
        byte[] salt = Arrays.copyOfRange(record, 1, 1 + SALT_LENGTH);
        byte[] saved = Arrays.copyOfRange(record, 1 + SALT_LENGTH, record.length);
        byte[] actual = derive(pin, salt);
        boolean equal = MessageDigest.isEqual(saved, actual);
        Arrays.fill(record, (byte) 0);
        Arrays.fill(saved, (byte) 0);
        Arrays.fill(actual, (byte) 0);
        return equal;
    }

    private long remainingLockout(SharedPreferences p) {
        return Math.max(0L, p.getLong("blocked_until", 0L) - System.currentTimeMillis());
    }

    private long failedAttempt(SharedPreferences p) {
        int attempts = p.getInt("failed_attempts", 0) + 1;
        if (attempts >= MAX_ATTEMPTS) {
            p.edit().putInt("failed_attempts", 0)
                    .putLong("blocked_until", System.currentTimeMillis() + LOCKOUT_MS)
                    .apply();
            return LOCKOUT_MS;
        }
        p.edit().putInt("failed_attempts", attempts).apply();
        return 0L;
    }

    private void successfulAttempt(SharedPreferences p) {
        p.edit().remove("failed_attempts").remove("blocked_until").apply();
    }

    private JSObject rejectedPin(long retryAfterMs) {
        JSObject r = new JSObject();
        r.put("ok", false);
        r.put("retryAfterMs", retryAfterMs);
        return r;
    }

    @PluginMethod
    public void setPin(final PluginCall call) {
        final String pin = call.getString("pin", "");
        final String oldPin = call.getString("oldPin", "");
        if (!pin.matches("[0-9]{6}")) {
            call.reject("Le code PIN doit contenir 6 chiffres.");
            return;
        }
        background(call, new Work() {
            @Override
            public void run() throws Exception {
                SharedPreferences p = prefs();
                if (hasPin(p)) {
                    long wait = remainingLockout(p);
                    if (wait > 0) {
                        call.resolve(rejectedPin(wait));
                        return;
                    }
                    if (!matches(p, oldPin)) {
                        call.resolve(rejectedPin(failedAttempt(p)));
                        return;
                    }
                    successfulAttempt(p);
                }
                byte[] salt = new byte[SALT_LENGTH];
                new SecureRandom().nextBytes(salt);
                byte[] hash = derive(pin, salt);
                byte[] record = ByteBuffer.allocate(1 + SALT_LENGTH + HASH_LENGTH)
                        .put((byte) 1).put(salt).put(hash).array();
                p.edit().putString(PIN_BLOB, encrypt(record))
                        .putBoolean("pin_enabled", true)
                        .putBoolean("biometric_enabled", false)
                        .remove("failed_attempts").remove("blocked_until").apply();
                Arrays.fill(hash, (byte) 0);
                Arrays.fill(record, (byte) 0);
                JSObject r = new JSObject();
                r.put("ok", true);
                call.resolve(r);
            }
        });
    }

    @PluginMethod
    public void verifyPin(final PluginCall call) {
        final String pin = call.getString("pin", "");
        background(call, new Work() {
            @Override
            public void run() throws Exception {
                SharedPreferences p = prefs();
                long wait = remainingLockout(p);
                if (wait > 0) {
                    call.resolve(rejectedPin(wait));
                    return;
                }
                if (!matches(p, pin)) {
                    call.resolve(rejectedPin(failedAttempt(p)));
                    return;
                }
                successfulAttempt(p);
                JSObject r = new JSObject();
                r.put("ok", true);
                r.put("retryAfterMs", 0);
                call.resolve(r);
            }
        });
    }

    @PluginMethod
    public void disablePin(final PluginCall call) {
        final String pin = call.getString("pin", "");
        background(call, new Work() {
            @Override
            public void run() throws Exception {
                SharedPreferences p = prefs();
                long wait = remainingLockout(p);
                if (wait > 0) {
                    call.resolve(rejectedPin(wait));
                    return;
                }
                if (!matches(p, pin)) {
                    call.resolve(rejectedPin(failedAttempt(p)));
                    return;
                }
                p.edit().remove(PIN_BLOB).putBoolean("pin_enabled", false)
                        .putBoolean("biometric_enabled", false)
                        .remove("failed_attempts").remove("blocked_until").apply();
                JSObject r = new JSObject();
                r.put("ok", true);
                call.resolve(r);
            }
        });
    }

    @PluginMethod
    public void clearPin(final PluginCall call) {
        if (!call.getBoolean("force", false)) {
            call.reject("La réinitialisation du verrouillage doit être confirmée.");
            return;
        }
        SharedPreferences p = prefs();
        p.edit().remove(PIN_BLOB).putBoolean("pin_enabled", false)
                .putBoolean("biometric_enabled", false)
                .remove("failed_attempts").remove("blocked_until").apply();
        call.resolve(state(p));
    }

    @PluginMethod
    public void setBiometricEnabled(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", false);
        SharedPreferences p = prefs();
        if (enabled && !hasPin(p)) {
            call.reject("Un code PIN doit être configuré avant la biométrie.");
            return;
        }
        if (enabled && !biometricAvailable()) {
            call.reject("Aucune biométrie forte n’est configurée sur cet appareil.");
            return;
        }
        p.edit().putBoolean("biometric_enabled", enabled).apply();
        JSObject r = state(p);
        r.put("ok", true);
        call.resolve(r);
    }

    @PluginMethod
    public void authenticateBiometric(PluginCall call) {
        SharedPreferences p = prefs();
        if (!hasPin(p) || !p.getBoolean("biometric_enabled", false) || !biometricAvailable()) {
            call.reject("Le déverrouillage biométrique n’est pas disponible.");
            return;
        }
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Déverrouiller Cabine Momo")
                .setSubtitle("Confirmez votre identité")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Utiliser mon code PIN")
                .build();
        BiometricPrompt prompt = new BiometricPrompt(
                getActivity(),
                ContextCompat.getMainExecutor(getContext()),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        JSObject r = new JSObject();
                        r.put("ok", true);
                        call.resolve(r);
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        call.reject("Authentification biométrique annulée ou échouée.");
                    }
                }
        );
        prompt.authenticate(promptInfo);
    }
}
