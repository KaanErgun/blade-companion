package com.kaanergun.bladecompanion.net;

import com.kaanergun.bladecompanion.core.CompanionProtocol;
import com.kaanergun.bladecompanion.core.MarketProtocol;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Arrays;
import java.util.Iterator;

final class MarketJson {
    private MarketJson() { }
    static JSONObject object(byte[] bytes) throws Exception {
        if (bytes.length > 4092) { throw new IOException("Oversized management JSON"); }
        return new JSONObject(CompanionProtocol.utf8Text(bytes));
    }
    static void fields(JSONObject value, String... expected) throws Exception {
        HashSet<String> actual = new HashSet<>(); Iterator<String> keys=value.keys();
        while (keys.hasNext()) { actual.add(keys.next()); }
        if (!actual.equals(new HashSet<>(Arrays.asList(expected)))) { throw new IOException("Management JSON fields"); }
    }
    static String string(JSONObject value, String key) throws Exception {
        Object item=value.get(key); if (!(item instanceof String)) { throw new IOException("Expected string"); } return (String)item;
    }
    static long number(JSONObject value, String key) throws Exception {
        Object item=value.get(key); if (!(item instanceof Integer || item instanceof Long)) { throw new IOException("Expected integer"); } return ((Number)item).longValue();
    }
    static int integer(JSONObject value, String key) throws Exception {
        long item=number(value,key); if (item<0 || item>Integer.MAX_VALUE) { throw new IOException("Integer range"); } return (int)item;
    }
    static boolean bool(JSONObject value, String key) throws Exception {
        Object item=value.get(key); if (!(item instanceof Boolean)) { throw new IOException("Expected boolean"); } return (Boolean)item;
    }
    static MarketProtocol.App app(JSONObject value) throws Exception {
        fields(value,"id","uid","version","revision","signer","permissions","run_as","enabled","installed","running");
        return new MarketProtocol.App(string(value,"id"),integer(value,"uid"),value.isNull("version")?null:string(value,"version"),
                string(value,"revision"),string(value,"signer"),integer(value,"permissions"),string(value,"run_as"),
                bool(value,"installed"),bool(value,"enabled"),bool(value,"running"));
    }
    static MarketProtocol.Preview preview(byte[] bytes) throws Exception {
        JSONObject value=object(bytes);
        fields(value,"id","version","revision","signer","permissions","run_as","files","package_bytes","installable");
        return new MarketProtocol.Preview(string(value,"id"),string(value,"version"),string(value,"revision"),string(value,"signer"),
                integer(value,"permissions"),string(value,"run_as"),integer(value,"files"),number(value,"package_bytes"),bool(value,"installable"));
    }
    static MarketClient.Page page(byte[] bytes, long offset) throws Exception {
        JSONObject value=object(bytes); fields(value,"items","next_offset");
        Object raw=value.get("items"); if (!(raw instanceof JSONArray)) { throw new IOException("Expected items"); }
        JSONArray items=(JSONArray)raw; if (items.length()>8) { throw new IOException("Page limit"); }
        List<MarketProtocol.App> apps=new ArrayList<>(); String previous=null;
        for (int i=0;i<items.length();i++) {
            MarketProtocol.App app=app(items.getJSONObject(i));
            if (previous!=null && previous.compareTo(app.id)>=0) { throw new IOException("App order"); }
            previous=app.id; apps.add(app);
        }
        Long next=value.isNull("next_offset")?null:number(value,"next_offset");
        if (next!=null && (items.length()==0 || next!=offset+items.length() || next>0xffffffffL)) { throw new IOException("Page offset"); }
        return new MarketClient.Page(apps,next);
    }
    static void receipt(byte[] bytes, String operation, String id, String version) throws Exception {
        JSONObject value=object(bytes);
        if (operation.equals("install")) {
            fields(value,"operation","id","version","uid");
            if (!id.equals(string(value,"id")) || !version.equals(string(value,"version"))) { throw new IOException("Install identity"); }
            integer(value,"uid");
        } else if (operation.equals("launch")) {
            fields(value,"operation","id","uid","worker");
            if (!id.equals(string(value,"id")) || integer(value,"worker")<1) { throw new IOException("Launch identity"); }
            integer(value,"uid");
        } else if (operation.startsWith("remove-")) {
            fields(value,"operation","data");
            if (!string(value,"data").equals(operation.equals("remove-keep")?"kept":"deleted")) { throw new IOException("Remove data choice"); }
            operation="remove";
        } else { fields(value,"operation"); }
        if (!operation.equals(string(value,"operation"))) { throw new IOException("Operation receipt"); }
    }
}
