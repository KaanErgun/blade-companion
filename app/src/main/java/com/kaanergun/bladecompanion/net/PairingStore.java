package com.kaanergun.bladecompanion.net;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.kaanergun.bladecompanion.core.CompanionProtocol;
import com.kaanergun.bladecompanion.core.PairingRecord;

import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Pairing credentials remain encrypted with a non-exportable Android Keystore key. */
public final class PairingStore {
    private static final Object LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicLong REVISION = new java.util.concurrent.atomic.AtomicLong();
    public static long revision() { return REVISION.get(); }
    public static long change() { return REVISION.incrementAndGet(); }
    private static final String ALIAS = "blade.companion.pairing.v1";
    private final Context context;
    public PairingStore(Context context) { this.context = context.getApplicationContext(); }

    public static PairingRecord parse(byte[] bytes) throws JSONException, java.io.IOException {
        if (bytes.length > 4096) { throw new IllegalArgumentException("Pairing file too large"); }
        JSONObject value = new JSONObject(CompanionProtocol.utf8Text(bytes));
        if (value.length() != 5 || !(value.get("protocol") instanceof Integer) || value.getInt("protocol") != 1
                || !(value.get("port") instanceof Integer) || !(value.get("host") instanceof String)
                || !(value.get("certificate_sha256") instanceof String) || !(value.get("token") instanceof String)) {
            throw new IllegalArgumentException("Invalid pairing fields");
        }
        return new PairingRecord(value.getString("host"), value.getInt("port"),
                value.getString("certificate_sha256"), value.getString("token"));
    }

    public PairingRecord load() throws Exception {
        String encoded = context.getSharedPreferences("pairing", Context.MODE_PRIVATE).getString("record", null);
        if (encoded == null) { return null; }
        byte[] value = Base64.decode(encoded, Base64.NO_WRAP);
        if (value.length < 28 || value.length > 8192) { throw new GeneralSecurityException("Invalid encrypted record"); }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(value, 12)));
        return parse(cipher.doFinal(value, 12, value.length - 12));
    }

    public void save(PairingRecord record, long revision) throws Exception {
        synchronized (LOCK) {
        if (REVISION.get() != revision) { throw new java.io.IOException("Pairing operation cancelled"); }
        JSONObject json = new JSONObject().put("protocol", 1).put("host", record.host).put("port", record.port)
                .put("certificate_sha256", CompanionProtocol.hex(record.certificate()))
                .put("token", CompanionProtocol.hex(record.token()));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        if (cipher.getIV().length != 12) { throw new GeneralSecurityException("Unexpected GCM nonce"); }
        byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        byte[] joined = new byte[12 + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, joined, 0, 12); System.arraycopy(encrypted, 0, joined, 12, encrypted.length);
        if (!context.getSharedPreferences("pairing", Context.MODE_PRIVATE).edit()
                .putString("record", Base64.encodeToString(joined, Base64.NO_WRAP)).commit()) {
            throw new java.io.IOException("Pairing persistence failed");
        }
    }

    }

    public void forget(long revision) throws Exception {
        synchronized (LOCK) {
        if (REVISION.get() != revision) { throw new java.io.IOException("Pairing operation cancelled"); }
        if (!context.getSharedPreferences("pairing", Context.MODE_PRIVATE).edit().clear().commit()) {
            throw new java.io.IOException("Pairing removal failed");
        }
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(ALIAS);
    }

    }

    private SecretKey key() throws Exception {
        synchronized (LOCK) {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) { return (SecretKey) store.getKey(ALIAS, null); }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
        }
    }
}
