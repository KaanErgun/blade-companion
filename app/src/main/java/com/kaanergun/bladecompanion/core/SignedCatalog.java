package com.kaanergun.bladecompanion.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** SDK catalog v1. Trust comes only from the separately selected public key. */
public final class SignedCatalog {
    public static final int MAX_BYTES=128*1024;
    private static final byte[] KEY_PREFIX=CompanionProtocol.unhex("302a300506032b6570032100",12);
    private static final String BASE64="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    public final String signer, sha256;
    public final long revision;
    public final List<Entry> entries;
    private final byte[] encoded;

    private SignedCatalog(String signer,long revision,List<Entry> entries,byte[] encoded) {
        this.signer=signer; this.revision=revision; this.entries=Collections.unmodifiableList(entries);
        this.encoded=encoded.clone(); this.sha256=CompanionProtocol.hex(CompanionProtocol.sha256(encoded));
    }
    public byte[] bytes() { return encoded.clone(); }
    public static boolean available() {
        try { Signature.getInstance("Ed25519"); KeyFactory.getInstance("Ed25519"); return true; }
        catch (GeneralSecurityException error) { return false; }
    }
    public static byte[] publicKey(byte[] source) throws IOException {
        require(source!=null && source.length<=512,"Public key size");
        byte[] der=source.clone();
        if (source.length!=44) {
            String pem=new String(source,StandardCharsets.US_ASCII).trim();
            String begin="-----BEGIN PUBLIC KEY-----", end="-----END PUBLIC KEY-----";
            require(pem.startsWith(begin) && pem.endsWith(end),"Public key encoding");
            String body=pem.substring(begin.length(),pem.length()-end.length()).replace("\r","").replace("\n","");
            der=decode64(body,44);
        }
        require(der.length==44 && Arrays.equals(KEY_PREFIX,Arrays.copyOf(der,KEY_PREFIX.length)),"Only Ed25519 SPKI");
        return der;
    }
    public static String fingerprint(byte[] der) throws IOException {
        return CompanionProtocol.hex(CompanionProtocol.sha256(publicKey(der)));
    }
    public static SignedCatalog verify(byte[] bytes,byte[] trustedKey) throws IOException,GeneralSecurityException {
        require(bytes!=null && bytes.length>0 && bytes.length<=MAX_BYTES,"Catalog size");
        Object parsed=new Parser(bytes).parse(); Map<String,Object> envelope=object(parsed);
        fields(envelope,"catalog","signature");
        require(Arrays.equals(bytes,canonical(envelope)),"Catalog must be SDK canonical JSON");
        Map<String,Object> catalog=object(envelope.get("catalog"));
        fields(catalog,"schema_version","kind","revision","signer","packages");
        require(number(catalog,"schema_version")==1 && "blade-catalog".equals(string(catalog,"kind")),"Catalog format");
        long revision=number(catalog,"revision"); require(revision>=1 && revision<=9007199254740991L,"Catalog revision");
        String signer=hash(string(catalog,"signer")); byte[] der=publicKey(trustedKey);
        require(signer.equals(fingerprint(der)),"Untrusted catalog signer");
        byte[] signature=decode64(string(envelope,"signature"),64);
        Signature verifier=Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der)));
        verifier.update(canonical(catalog)); require(verifier.verify(signature),"Catalog signature");
        Object raw=catalog.get("packages"); require(raw instanceof List<?>,"Catalog packages");
        List<?> packages=(List<?>)raw; require(!packages.isEmpty() && packages.size()<=512,"Catalog package count");
        ArrayList<Entry> entries=new ArrayList<>(); HashSet<String> ids=new HashSet<>(),urls=new HashSet<>();
        for (Object value:packages) {
            Map<String,Object> item=object(value);
            fields(item,"id","version","url","bytes","sha256","signer","run_as","permissions");
            String id=string(item,"id"),version=string(item,"version"),url=string(item,"url"),runAs=string(item,"run_as");
            require(MarketProtocol.validId(id) && validVersion(version),"Catalog package identity"); url(url,false);
            require(ids.add(id) && urls.add(url),"Duplicate catalog entry");
            long size=number(item,"bytes"); require(size>0 && size<=MarketProtocol.MAX_PACKAGE,"Catalog package size");
            int permissions=permissions(item.get("permissions"));
            require("app".equals(runAs) && (permissions & 32)==0,"Root catalog unsupported");
            entries.add(new Entry(id,version,url,size,hash(string(item,"sha256")),hash(string(item,"signer")),permissions,runAs));
        }
        return new SignedCatalog(signer,revision,entries,bytes);
    }
    public void checkRevision(long previous,String previousHash) throws IOException {
        require(previous>=0 && revision>=previous && (revision!=previous || sha256.equals(previousHash)),"Catalog rollback or revision reuse");
    }
    public Entry selected(String catalogHash,String id,String packageHash) throws IOException {
        require(sha256.equals(catalogHash),"Selected catalog changed");
        for (Entry entry:entries) {
            if (entry.id.equals(id) && entry.sha256.equals(packageHash)) {
                require(entry.supported(),"Unsupported selected package");return entry;
            }
        }
        throw new IOException("Selected package changed");
    }
    public static URI url(String value,boolean httpsOnly) throws IOException {
        require(value!=null && !value.isEmpty() && value.length()<=2048,"URL length");
        for (int i=0;i<value.length();i++) { require(value.charAt(i)>32 && value.charAt(i)!=127,"URL control character"); }
        try {
            URI uri=new URI(value); String scheme=uri.getScheme();
            require(("https".equals(scheme) || !httpsOnly && "http".equals(scheme)) && uri.getHost()!=null
                    && uri.getRawUserInfo()==null && uri.getRawQuery()==null && uri.getRawFragment()==null
                    && (uri.getPort()==-1 || uri.getPort()>=1 && uri.getPort()<=65535),"Absolute HTTP(S) URL required");
            return uri;
        } catch (URISyntaxException error) { throw new IOException("Invalid URL"); }
    }
    public static final class Entry {
        public final String id,version,url,sha256,signer,runAs;
        public final long bytes;
        public final int permissions;
        private Entry(String id,String version,String url,long bytes,String sha256,String signer,int permissions,String runAs) {
            this.id=id;this.version=version;this.url=url;this.bytes=bytes;this.sha256=sha256;this.signer=signer;this.permissions=permissions;this.runAs=runAs;
        }
        public void checkDownload(PackageTransfer.Result stored) throws IOException {
            require(stored.bytes==bytes && MessageDigest.isEqual(stored.sha256,CompanionProtocol.unhex(sha256,32)),"Catalog package bytes differ");
        }
        public void checkPreview(MarketProtocol.Preview preview) throws IOException {
            require(id.equals(preview.id) && version.equals(preview.version) && sha256.equals(preview.revision)
                    && signer.equals(preview.signer) && permissions==preview.permissions && runAs.equals(preview.runAs)
                    && bytes==preview.bytes,"Catalog and verified package differ");
        }
        public boolean supported() { return MarketProtocol.supported(permissions,runAs); }
        public boolean downloadable() { return url.startsWith("https://") && supported(); }
    }
    private static int permissions(Object value) throws IOException {
        require(value instanceof List<?>,"Catalog permissions"); int bits=0;
        for (Object permission:(List<?>)value) {
            require(permission instanceof String,"Permission type"); int bit;
            switch ((String)permission) {
                case "camera":bit=1;break;case "microphone":bit=2;break;case "hud":bit=4;break;
                case "network":bit=8;break;case "storage":bit=16;break;case "root":bit=32;break;
                default:throw new IOException("Unknown permission");
            }
            require((bits & bit)==0,"Duplicate permission"); bits|=bit;
        }
        return bits;
    }
    private static boolean validVersion(String value) {
        if (value.length()>64 || !value.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")) { return false; }
        String plain=value.split("\\+",-1)[0]; int dash=plain.indexOf('-');
        if (dash>=0) { for (String part:plain.substring(dash+1).split("\\.",-1)) { if (part.isEmpty() || part.matches("0[0-9]+")) { return false; } } }
        int plus=value.indexOf('+'); if (plus>=0) { for (String part:value.substring(plus+1).split("\\.",-1)) { if (part.isEmpty()) { return false; } } }
        return true;
    }
    private static String hash(String value) throws IOException { require(value.matches("[0-9a-f]{64}"),"Catalog hash");return value; }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws IOException {
        require(value instanceof Map<?,?>,"Expected object");return (Map<String,Object>)value;
    }
    private static void fields(Map<String,Object> value,String... keys) throws IOException {
        require(value.keySet().equals(new HashSet<>(Arrays.asList(keys))),"Catalog fields");
    }
    private static String string(Map<String,Object> value,String key) throws IOException {
        Object item=value.get(key);require(item instanceof String,"Expected string");return (String)item;
    }
    private static long number(Map<String,Object> value,String key) throws IOException {
        Object item=value.get(key);require(item instanceof Long,"Expected integer");return (Long)item;
    }
    private static void require(boolean condition,String reason) throws IOException { if (!condition) { throw new IOException(reason); } }
    private static byte[] decode64(String value,int size) throws IOException {
        require(value.length()==((size+2)/3)*4,"Base64 length"); ByteArrayOutputStream out=new ByteArrayOutputStream();
        for (int i=0;i<value.length();i+=4) {
            int a=BASE64.indexOf(value.charAt(i)),b=BASE64.indexOf(value.charAt(i+1));
            int c=value.charAt(i+2)=='='?-1:BASE64.indexOf(value.charAt(i+2));
            int d=value.charAt(i+3)=='='?-1:BASE64.indexOf(value.charAt(i+3));
            require(a>=0 && b>=0 && (c>=0 || value.charAt(i+2)=='=') && (d>=0 || value.charAt(i+3)=='='),"Base64 alphabet");
            require((c>=0 && d>=0) || i+4==value.length(),"Base64 padding"); out.write(a<<2 | b>>4);
            if (c>=0) { out.write(b<<4 | c>>2); if (d>=0) { out.write(c<<6 | d); } else { require((c & 3)==0,"Base64 tail"); } }
            else { require(d<0 && (b & 15)==0,"Base64 tail"); }
        }
        require(out.size()==size,"Base64 decoded size");return out.toByteArray();
    }
    private static byte[] canonical(Object value) throws IOException {
        StringBuilder text=new StringBuilder();encode(value,text);text.append('\n');return text.toString().getBytes(StandardCharsets.US_ASCII);
    }
    private static void encode(Object value,StringBuilder out) throws IOException {
        if (value instanceof Map<?,?>) {
            out.append('{');boolean first=true;
            for (Map.Entry<String,Object> item:object(value).entrySet()) { if (!first) { out.append(','); } first=false;encode(item.getKey(),out);out.append(':');encode(item.getValue(),out); }out.append('}');
        } else if (value instanceof List<?>) {
            out.append('[');boolean first=true;for (Object item:(List<?>)value) { if (!first) { out.append(','); }first=false;encode(item,out); }out.append(']');
        } else if (value instanceof Long) { out.append(value); }
        else if (value instanceof String) {
            out.append('"');String text=(String)value;
            for (int i=0;i<text.length();i++) { char c=text.charAt(i);
                if (c=='"' || c=='\\') { out.append('\\').append(c); }
                else if (c=='\b') { out.append("\\b"); } else if (c=='\f') { out.append("\\f"); }
                else if (c=='\n') { out.append("\\n"); } else if (c=='\r') { out.append("\\r"); } else if (c=='\t') { out.append("\\t"); }
                else if (c<32 || c>126) { out.append("\\u");String hex=Integer.toHexString(c);for (int j=hex.length();j<4;j++) { out.append('0'); }out.append(hex); }
                else { out.append(c); }
            }out.append('"');
        } else { throw new IOException("Unsupported JSON value"); }
    }
    private static final class Parser {
        private final String text; private int offset;
        Parser(byte[] bytes) throws IOException {
            for (byte b:bytes) { require(b>=0,"Catalog must be ASCII canonical JSON"); }
            text=new String(bytes,StandardCharsets.US_ASCII);
        }
        Object parse() throws IOException { Object value=value(0);require(offset==text.length()-1 && text.charAt(offset)=='\n',"Catalog trailing bytes");return value; }
        private char peek() throws IOException { require(offset<text.length(),"Truncated JSON");return text.charAt(offset); }
        private void take(char expected) throws IOException { require(peek()==expected,"JSON syntax");offset++; }
        private Object value(int depth) throws IOException {
            require(depth<=8,"JSON nesting");char c=peek();
            if (c=='{') {
                offset++;Map<String,Object> map=new TreeMap<>();if (peek()=='}') { offset++;return map; }
                do { String key=string();take(':');require(!map.containsKey(key),"Duplicate JSON key");map.put(key,value(depth+1));c=peek();offset++; } while (c==',');
                require(c=='}',"JSON object");return map;
            }
            if (c=='[') {
                offset++;List<Object> list=new ArrayList<>();if (peek()==']') { offset++;return list; }
                do { require(list.size()<512,"JSON list limit");list.add(value(depth+1));c=peek();offset++; } while (c==',');
                require(c==']',"JSON array");return list;
            }
            if (c=='"') { return string(); }
            int start=offset; if (c=='-') { offset++; }
            while (offset<text.length() && text.charAt(offset)>='0' && text.charAt(offset)<='9') { offset++; }
            require(offset>start,"JSON integer");
            try { return Long.valueOf(text.substring(start,offset)); } catch (NumberFormatException error) { throw new IOException("JSON integer range"); }
        }
        private String string() throws IOException {
            take('"');StringBuilder out=new StringBuilder();
            while (true) {
                char c=peek();offset++;if (c=='"') { return out.toString(); }require(c>=32,"JSON string control");
                if (c=='\\') {
                    c=peek();offset++;
                    switch (c) {
                        case '"':case '\\':out.append(c);break;case 'b':out.append('\b');break;case 'f':out.append('\f');break;
                        case 'n':out.append('\n');break;case 'r':out.append('\r');break;case 't':out.append('\t');break;
                        case 'u':
                            require(offset+4<=text.length(),"JSON Unicode");int code=0;
                            for (int i=0;i<4;i++) { int digit=Character.digit(text.charAt(offset++),16);require(digit>=0,"JSON Unicode");code=code*16+digit; }out.append((char)code);break;
                        default:throw new IOException("JSON escape");
                    }
                } else { out.append(c); }
            }
        }
    }
}
