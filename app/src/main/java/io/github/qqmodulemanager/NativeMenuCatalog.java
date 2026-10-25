package io.github.qqmodulemanager;

import org.json.*;
import java.util.HashSet;
import java.util.Set;

/** Known manager actions can be configured before QQ has produced its first live menu. */
public final class NativeMenuCatalog {
    public static String withBuiltins(String saved) {
        try {
            JSONObject catalog=new JSONObject(saved);
            for(String chat:new String[]{"group","private"})for(String kind:new String[]{"text","image","voice","video","file"}) {
                String profile=chat+"/"+kind;
                JSONObject snapshot=catalog.optJSONObject(profile);
                if(snapshot==null){snapshot=new JSONObject().put("profile",profile).put("capturedAt",0);catalog.put(profile,snapshot);}
                JSONArray items=snapshot.optJSONArray("items");if(items==null){items=new JSONArray();snapshot.put("items",items);}
                Set<String> keys=new HashSet<>();for(int i=0;i<items.length();i++)keys.add(items.getJSONObject(i).optString("key"));
                if(kind.equals("text") || kind.equals("image"))append(items,keys,"translation","翻译");
                append(items,keys,"translation_visible","一键翻译");
                if(kind.equals("image"))append(items,keys,"lens","Google 识图");
            }
            return catalog.toString();
        }catch(JSONException error){throw new IllegalStateException("Invalid menu catalog",error);}
    }
    private static void append(JSONArray items,Set<String> keys,String action,String title)throws JSONException {
        String key="manager:"+action;
        if(keys.add(key))items.put(new JSONObject().put("key",key).put("title",title).put("source","manager").put("editable",true));
    }
    private NativeMenuCatalog(){}
}
