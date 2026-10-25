package io.github.qqmodulemanager;

import android.content.Context;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** QQ 9.3.75's native setting item/group models; no module resource IDs enter QQ. */
public final class SettingsEntry {
    private final Class<?> itemClass;
    private final Method onClick;
    private final Consumer<Context> open;
    private final Set<Object> injectedGroups=Collections.newSetFromMap(new WeakHashMap<>());

    public SettingsEntry(ClassLoader loader,Consumer<Context> open)throws Exception {
        this.open=open;
        itemClass=loader.loadClass("com.tencent.mobileqq.setting.processor.i");
        onClick=itemClass.getDeclaredMethod("B",loader.loadClass("kotlin.jvm.functions.Function0"));
        itemClass.getDeclaredConstructor(Context.class,int.class,CharSequence.class,int.class,String.class);
    }

    public List<Object> insert(Context context,List<?> original)throws Exception {
        for(Object group:original)if(injectedGroups.contains(group))return new ArrayList<>(original);
        if(original.isEmpty())return new ArrayList<>(original);
        int icon=context.getResources().getIdentifier("qui_tuning","drawable","com.tencent.mobileqq");
        Object item=itemClass.getDeclaredConstructor(Context.class,int.class,CharSequence.class,int.class,String.class)
                .newInstance(context,0x514d4d,"QIII",icon,null);
        Class<?> callback=onClick.getParameterTypes()[0];
        Object unit=callback.getClassLoader().loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        Object listener=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(proxy,method,args)->{
            switch(method.getName()) {
                case "invoke": open.accept(context);return unit;
                case "toString": return "QIII settings entry";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy==args[0];
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
        onClick.invoke(item,listener);
        Class<?> groupType=original.get(0).getClass();
        Object group=groupType.getDeclaredConstructor(List.class,CharSequence.class,CharSequence.class,int.class,
                itemClass.getClassLoader().loadClass("kotlin.jvm.internal.DefaultConstructorMarker"))
                .newInstance(Collections.singletonList(item),"","",6,null);
        injectedGroups.add(group);
        List<Object> result=new ArrayList<>(original);result.add(Math.min(1,result.size()),group);return result;
    }
}
