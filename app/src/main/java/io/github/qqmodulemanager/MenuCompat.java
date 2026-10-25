package io.github.qqmodulemanager;

import android.os.Bundle;
import io.github.qqmodulemanager.core.MenuOrder;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;

/** QQ 9.3.75 adapter; signatures and IDs are checked against raw DEX. */
public final class MenuCompat {
    private final HostCoordinator coordinator;
    private final Field items;
    private final Method title, id, message;
    public MenuCompat(ClassLoader loader,HostCoordinator coordinator)throws Exception {
        this.coordinator=coordinator;
        Class<?> model=loader.loadClass("com.tencent.qqnt.aio.menu.ui.c"), item=loader.loadClass("com.tencent.qqnt.aio.menu.ui.e");
        items=model.getDeclaredField("a");items.setAccessible(true);
        title=item.getMethod("h");id=item.getMethod("c");message=item.getMethod("d");
    }
    @SuppressWarnings("unchecked")
    public void apply(Object model)throws Exception {
        if(!coordinator.menuEnabled())return;
        Object saved=items.get(model);
        // Work on a fresh model list; original menu-item instances and callbacks survive.
        List<Object> originals=new ArrayList<>((List<Object>)saved);
        List<Object> baseline=null;
        try {
            List<Object> working=new java.util.concurrent.CopyOnWriteArrayList<>(originals);items.set(model,working);
            coordinator.contribute(model);
            working=new ArrayList<>((List<Object>)items.get(model));
            baseline=new ArrayList<>(working);
            if(working.isEmpty())return;
            String profile=profile(working.get(0));
            if(profile==null)return;
            IdentityHashMap<Object,String> keys=new IdentityHashMap<>();
            JSONArray catalog=new JSONArray();Map<String,Integer> occurrences=new HashMap<>();
            for(Object item:working) {
                Bundle tag=coordinator.itemTag(item);
                String key=tag==null ? "qq:16580:"+item.getClass().getName()+":"+id.invoke(item) : tag.getString("key");
                keys.put(item,key);occurrences.merge(key,1,Integer::sum);
                JSONObject entry=new JSONObject();entry.put("key",key);entry.put("title",tag!=null && tag.containsKey("title") ? tag.getString("title") : title.invoke(item));
                entry.put("source",tag==null ? (item.getClass().getName().startsWith("com.tencent.")?"QQ":"unknown") : tag.getString("source"));
                catalog.put(entry);
            }
            for(int i=0;i<catalog.length();i++)catalog.getJSONObject(i).put("editable",occurrences.get(catalog.getJSONObject(i).getString("key"))==1);
            JSONObject snapshot=new JSONObject();snapshot.put("profile",profile);snapshot.put("items",catalog);snapshot.put("capturedAt",System.currentTimeMillis());
            coordinator.capture(snapshot);
            JSONObject rule=coordinator.menuRule(profile);
            List<String> order=strings(rule.optJSONArray("order"));Set<String> hidden=new HashSet<>(strings(rule.optJSONArray("hidden")));
            List<Object> result=MenuOrder.apply(working,keys::get,order,hidden);
            items.set(model,new java.util.concurrent.CopyOnWriteArrayList<>(result));
        }catch(Exception | LinkageError e) {
            // Contribution already happened: preserve its actions even if policy processing fails.
            items.set(model,baseline==null ? items.get(model) : new java.util.concurrent.CopyOnWriteArrayList<>(baseline));
            throw e;
        }
    }
    private String profile(Object item)throws Exception {
        Object aio=message.invoke(item);
        Object record=aio.getClass().getMethod("getMsgRecord").invoke(aio);
        int chat=((Number)record.getClass().getMethod("getChatType").invoke(record)).intValue();
        if(chat!=1 && chat!=2)return null;
        Iterable<?> elements=(Iterable<?>)record.getClass().getMethod("getElements").invoke(record);
        Set<Integer> types=new HashSet<>();
        for(Object element:elements)types.add(((Number)element.getClass().getMethod("getElementType").invoke(element)).intValue());
        String kind;
        if(types.contains(5))kind="video";
        else if(types.contains(4))kind="voice";
        else if(types.contains(3))kind="file";
        else if(types.contains(2))kind="image";
        else if(types.contains(1))kind="text";
        else return null;
        return (chat==2?"group":"private")+"/"+kind;
    }
    public static List<String> strings(JSONArray array) {
        List<String> result=new ArrayList<>();if(array!=null)for(int i=0;i<array.length();i++)result.add(array.optString(i));return result;
    }
}
