package com.kaanergun.bladecompanion.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class CatalogTest {
    private static int checks;
    private interface Task { void run() throws Exception; }
    private static void check(boolean value,String message) { checks++;if (!value) { throw new AssertionError(message); } }
    private static void rejects(Task task,String message) throws Exception {
        checks++;try { task.run(); }catch (java.io.IOException|java.security.GeneralSecurityException error) { return; }throw new AssertionError(message);
    }
    private static byte[] resource(String path) throws Exception {
        try (InputStream source=CatalogTest.class.getResourceAsStream("/"+path);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            if (source==null) { throw new AssertionError("Missing SDK fixture"); }byte[] data=new byte[4096];int count;
            while ((count=source.read(data))!=-1) { output.write(data,0,count); }return output.toByteArray();
        }
    }
    private static byte[] sign(String text,java.security.KeyPair pair,String originalSigner) throws Exception {
        String signer=CompanionProtocol.hex(CompanionProtocol.sha256(pair.getPublic().getEncoded()));
        String changed=text.replace(originalSigner,signer);
        String document=changed.substring("{\"catalog\":".length(),changed.lastIndexOf(",\"signature\":"));
        java.security.Signature signature=java.security.Signature.getInstance("Ed25519");signature.initSign(pair.getPrivate());
        signature.update((document+"\n").getBytes(StandardCharsets.US_ASCII));
        return ("{\"catalog\":"+document+",\"signature\":\""+java.util.Base64.getEncoder().encodeToString(signature.sign())+"\"}\n").getBytes(StandardCharsets.US_ASCII);
    }
    static int run() throws Exception {
        byte[] bytes=resource("catalog-v1.json"),pem=resource("catalog-publisher.pem"),key=SignedCatalog.publicKey(pem);
        SignedCatalog catalog=SignedCatalog.verify(bytes,key);SignedCatalog.Entry entry=catalog.entries.get(0);
        check(SignedCatalog.available(),"JDK17 platform Ed25519 present");
        check(key.length==44 && Arrays.equals(key,SignedCatalog.publicKey(key)),"DER/PEM identity");
        check(catalog.revision==7 && catalog.entries.size()==1,"SDK signed catalog revision and entries");
        check(entry.id.equals("dev.kaanergun.hello") && entry.permissions==4 && entry.downloadable(),"SDK HUD entry");
        check(!catalog.signer.equals(entry.signer),"Catalog trust does not imply package signer trust");
        check(catalog.selected(catalog.sha256,entry.id,entry.sha256)==entry,"SAF selection resolves exact verified entry");
        rejects(() -> catalog.selected(MarketProtocol.EMPTY_REVISION,entry.id,entry.sha256),"selection catalog changed");
        rejects(() -> catalog.selected(catalog.sha256,"dev.other.app",entry.sha256),"selection identity changed");
        rejects(() -> catalog.selected(catalog.sha256,entry.id,MarketProtocol.EMPTY_REVISION),"selection package changed");
        rejects(() -> catalog.selected(null,entry.id,entry.sha256),"lost selection rejected");
        catalog.checkRevision(0,null);checks++;catalog.checkRevision(7,catalog.sha256);checks++;
        rejects(() -> catalog.checkRevision(8,catalog.sha256),"rollback rejected");
        rejects(() -> catalog.checkRevision(7,MarketProtocol.EMPTY_REVISION),"same revision altered bytes rejected");
        byte[] changed=bytes.clone();changed[changed.length-8]^=1;
        rejects(() -> SignedCatalog.verify(changed,key),"signature mutation");
        byte[] otherKey=key.clone();otherKey[43]^=1;
        rejects(() -> SignedCatalog.verify(bytes,otherKey),"foreign key");
        String text=new String(bytes,StandardCharsets.US_ASCII);
        for (String altered:new String[]{text.replace("\"revision\":7","\"revision\":7,\"revision\":7")," "+text,
                text.substring(0,text.length()-1),text.replace("\"revision\":7","\"revision\":07"),
                text.replace("\"revision\":7","\"revision\":9007199254740992"),text.replace("\"revision\":7","\"revision\":true"),
                text.replace("\"revision\":7","\"revision\":7.0"),text.replace("https://","http://"),
                text.replace("\"signature\":\"","\"signature\":\"="),text.replace("\"kind\":","\"extra\":1,\"kind\":")}) {
            rejects(() -> SignedCatalog.verify(altered.getBytes(StandardCharsets.US_ASCII),key),"noncanonical/malformed/tampered catalog");
        }
        java.security.KeyPair pair=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        check(SignedCatalog.verify(sign(text,pair,catalog.signer),pair.getPublic().getEncoded()).entries.size()==1,"fresh signed fixture");
        SignedCatalog offline=SignedCatalog.verify(sign(text.replace("https://","http://"),pair,catalog.signer),pair.getPublic().getEncoded());
        SignedCatalog.Entry offlineEntry=offline.entries.get(0);
        check(offlineEntry.supported() && !offlineEntry.downloadable(),"HTTP entry permits verified local file, never insecure download");
        check(offline.selected(offline.sha256,offlineEntry.id,offlineEntry.sha256)==offlineEntry,"offline selection retains signed identity");
        SignedCatalog unsupported=SignedCatalog.verify(sign(text.replace("[\"hud\"]","[\"camera\"]"),pair,catalog.signer),pair.getPublic().getEncoded());
        rejects(() -> unsupported.selected(unsupported.sha256,entry.id,entry.sha256),"unsupported offline grant rejected");
        for (String malformed:new String[]{text.replace("\"bytes\":"+entry.bytes,"\"bytes\":0"),
                text.replace("\"bytes\":"+entry.bytes,"\"bytes\":67108865"),
                text.replace(entry.version,"01.0.0"),text.replace(entry.version,"1.0.0-01"),text.replace(entry.version,"1.0.0+a..b"),
                text.replace("[\"hud\"]","[\"hud\",\"hud\"]"),text.replace("[\"hud\"]","[\"unknown\"]"),
                text.replace("\"run_as\":\"app\"","\"run_as\":\"root\""),
                text.replace(entry.id,"invalid"),text.replace(entry.url,entry.url+"?secret=x"),
                text.replace(entry.sha256,entry.sha256.toUpperCase(java.util.Locale.ROOT))}) {
            rejects(() -> SignedCatalog.verify(sign(malformed,pair,catalog.signer),pair.getPublic().getEncoded()),"signed invalid catalog schema");
        }
        rejects(() -> SignedCatalog.verify(new byte[SignedCatalog.MAX_BYTES+1],key),"catalog size limit");
        rejects(() -> SignedCatalog.verify("[[[[[[[[[[0]]]]]]]]]]\n".getBytes(StandardCharsets.US_ASCII),key),"nesting limit");
        rejects(() -> SignedCatalog.publicKey(new byte[44]),"wrong key OID");
        rejects(() -> SignedCatalog.publicKey(new byte[513]),"oversized key");
        String keyText=new String(pem,StandardCharsets.US_ASCII);
        rejects(() -> SignedCatalog.publicKey(keyText.replace("PUBLIC KEY","PRIVATE KEY").getBytes(StandardCharsets.US_ASCII)),"private key refused");
        for (String url:new String[]{"http://example.test/x","https://user:secret@example.test/x","https://example.test/x?secret=1",
                "https://example.test/x#x","file:///x","https://example.test:0/x","https://example.test:65536/x","https://example.test/ x"}) {
            rejects(() -> SignedCatalog.url(url,true),"unsafe/unsupported source URL");
        }
        check(SignedCatalog.url(entry.url,true).getHost().equals("example.test"),"HTTPS source");
        PackageTransfer.Result stored=new PackageTransfer.Result(entry.bytes,CompanionProtocol.unhex(entry.sha256,32));
        entry.checkDownload(stored);checks++;
        rejects(() -> entry.checkDownload(new PackageTransfer.Result(entry.bytes-1,stored.sha256)),"short download");
        rejects(() -> entry.checkDownload(new PackageTransfer.Result(entry.bytes,new byte[32])),"corrupt download");
        MarketProtocol.Preview preview=new MarketProtocol.Preview(entry.id,entry.version,entry.sha256,entry.signer,entry.permissions,"app",2,entry.bytes,true);
        entry.checkPreview(preview);checks++;
        rejects(() -> entry.checkPreview(new MarketProtocol.Preview(entry.id,entry.version,entry.sha256,catalog.signer,entry.permissions,"app",2,entry.bytes,true)),"native signer differs");
        rejects(() -> entry.checkPreview(new MarketProtocol.Preview(entry.id,"99.0.0",entry.sha256,entry.signer,entry.permissions,"app",2,entry.bytes,true)),"native version differs");
        rejects(() -> entry.checkPreview(new MarketProtocol.Preview(entry.id,entry.version,entry.sha256,entry.signer,8,"app",2,entry.bytes,true)),"native grant differs");
        return checks;
    }
}
