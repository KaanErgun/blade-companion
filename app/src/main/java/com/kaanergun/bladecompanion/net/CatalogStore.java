package com.kaanergun.bladecompanion.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import com.kaanergun.bladecompanion.core.CompanionProtocol;
import com.kaanergun.bladecompanion.core.SignedCatalog;
import java.io.IOException;

/** Public catalog trust and high-water revisions; never camera/management authority. */
public final class CatalogStore {
    private static final Object LOCK=new Object();
    private final SharedPreferences preferences;
    public CatalogStore(Context context) { preferences=context.getSharedPreferences("signed_catalog",Context.MODE_PRIVATE); }
    public byte[] key() throws IOException {
        synchronized (LOCK) {
            String key=preferences.getString("key",null);
            if (key==null) { return null; }
            try { return SignedCatalog.publicKey(CompanionProtocol.unhex(key,44)); }
            catch (IllegalArgumentException error) { throw new IOException("Stored catalog key invalid"); }
        }
    }
    public void trust(byte[] key) throws IOException {
        byte[] der=SignedCatalog.publicKey(key);
        synchronized (LOCK) {
            // Replacing a source never resets the remembered revision for any publisher.
            if (!preferences.edit().putString("key",CompanionProtocol.hex(der)).remove("catalog").remove("source").commit()) {
                throw new IOException("Catalog trust not persisted");
            }
        }
    }
    public SignedCatalog load() throws Exception {
        synchronized (LOCK) {
            byte[] key=key();String value=preferences.getString("catalog",null);
            if (key==null || value==null) { return null; }
            if (value.length()>SignedCatalog.MAX_BYTES*2) { throw new IOException("Stored catalog size"); }
            SignedCatalog catalog=SignedCatalog.verify(Base64.decode(value,Base64.NO_WRAP),key);
            check(catalog);return catalog;
        }
    }
    private void check(SignedCatalog catalog) throws IOException {
        catalog.checkRevision(preferences.getLong("revision."+catalog.signer,0),preferences.getString("hash."+catalog.signer,null));
    }
    public void accept(SignedCatalog catalog,String source) throws IOException {
        synchronized (LOCK) {
            byte[] key=key();if (key==null || !SignedCatalog.fingerprint(key).equals(catalog.signer)) { throw new IOException("Catalog trust changed"); }
            check(catalog);
            SharedPreferences.Editor edit=preferences.edit().putLong("revision."+catalog.signer,catalog.revision)
                    .putString("hash."+catalog.signer,catalog.sha256).putString("catalog",Base64.encodeToString(catalog.bytes(),Base64.NO_WRAP));
            if (source==null) { edit.remove("source"); } else { edit.putString("source",source); }
            if (!edit.commit()) { throw new IOException("Catalog revision not persisted"); }
        }
    }
    public String source() { synchronized (LOCK) { return preferences.getString("source",""); } }
}
