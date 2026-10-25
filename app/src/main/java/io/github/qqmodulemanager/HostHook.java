package io.github.qqmodulemanager;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HostHook implements IXposedHookLoadPackage {
    private static HostCoordinator coordinator;
    private Context context;
    private String process;
    private boolean settingsInstalled,settingsReported,menuInstalled,countInstalled,retryScheduled;
    private int startupRetries;
    private NativeFeatures nativeFeatures;
    private boolean nativeMenuFailed;
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp)throws Throwable {
        if(!"com.tencent.mobileqq".equals(lpp.packageName))return;
        process=lpp.processName;
        XposedHelpers.findAndHookMethod(Application.class,"attach",Context.class,new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if(context!=null)return;
                context=(Context)param.args[0];
                Application app=(Application)param.thisObject;
                app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityPreCreated(Activity activity,Bundle state){install(activity.getClassLoader());}
                    @Override public void onActivityCreated(Activity activity,Bundle state){install(activity.getClassLoader());}
                    @Override public void onActivityResumed(Activity activity){install(activity.getClassLoader());if(coordinator!=null){coordinator.resumed(activity);coordinator.foreground();}}
                    @Override public void onActivityStarted(Activity activity){}
                    @Override public void onActivityPaused(Activity activity){if(coordinator!=null)coordinator.paused(activity);}
                    @Override public void onActivityStopped(Activity activity){}
                    @Override public void onActivitySaveInstanceState(Activity activity,Bundle state){}
                    @Override public void onActivityDestroyed(Activity activity){}
                });
                install(context.getClassLoader());
            }
        });
    }
    private synchronized void install(ClassLoader loader) {
        if(coordinator==null)try{coordinator=new HostCoordinator(context,process);}catch(Exception | LinkageError error){XposedBridge.log("QIII: host setup: "+error.getClass().getSimpleName());}
        if(!"com.tencent.mobileqq".equals(process))return;
        // QQ can expose its final class loader after Application.attach. Retry only unfinished hooks.
        if(!settingsInstalled)try{installSettings(loader);settingsInstalled=true;}catch(Exception | LinkageError error){failed("settings",error);}
        if(coordinator!=null) {
            if(nativeFeatures==null && coordinator.nativeSupported())try{nativeFeatures=new NativeFeatures(context,loader,coordinator);coordinator.adapterReady("translation");}catch(Exception | LinkageError error){failed("translation",error);}
            if(settingsInstalled && !settingsReported){coordinator.adapterReady("settings");settingsReported=true;}
            if(!menuInstalled)try{installMenu(loader);menuInstalled=true;coordinator.adapterReady("menu");}catch(Exception | LinkageError error){failed("menu",error);}
            if(!countInstalled)try{installInvocationGate(loader);countInstalled=true;coordinator.adapterReady("count");}catch(Exception | LinkageError error){failed("count",error);}
        }
        if((coordinator==null || !settingsInstalled || !menuInstalled || !countInstalled || (coordinator.nativeSupported() && nativeFeatures==null)) && !retryScheduled && startupRetries++<15) {
            retryScheduled=true;
            new Handler(Looper.getMainLooper()).postDelayed(()->{retryScheduled=false;install(context.getClassLoader());},2000);
        }
    }
    private void failed(String name,Throwable error){if(coordinator!=null)coordinator.adapterFailed(name,error);}
    private void installSettings(ClassLoader loader)throws Exception {
        SettingsEntry entry=new SettingsEntry(loader,ctx->{if(coordinator!=null)coordinator.foreground();EmbeddedManager.open(ctx);});
        XposedHelpers.findAndHookMethod("com.tencent.mobileqq.setting.main.b",loader,"f",Context.class,new XC_MethodHook(Integer.MAX_VALUE) {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {param.setResult(entry.insert((Context)param.args[0],(java.util.List<?>)param.getResult()));if(coordinator!=null)coordinator.adapterReady("settings");}
                catch(Exception | LinkageError error){failed("settings",error);}
            }
        });
    }
    private void installMenu(ClassLoader loader)throws Exception {
        MenuCompat compat=new MenuCompat(loader,coordinator);
        Class<?> model=loader.loadClass("com.tencent.qqnt.aio.menu.ui.c");
        // Resolve both signatures first so a later retry cannot double-install the first callback.
        java.util.List<java.lang.reflect.Method> methods=new java.util.ArrayList<>();
        for(String name:new String[]{"QQCustomMenuExpandableLayout","QQCustomMenuNoIconLayout"})
            methods.add(loader.loadClass("com.tencent.qqnt.aio.menu.ui."+name).getDeclaredMethod("setMenu",model,android.view.View.class));
        for(java.lang.reflect.Method method:methods)XposedBridge.hookMethod(method,new XC_MethodHook(Integer.MIN_VALUE) {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if(nativeFeatures==null)coordinator.nativeMenuState(coordinator.nativeSupported()?"菜单已触发，新功能接入未完成，请查看接入错误":"当前 QQ 版本未适配消息工具");
                if(nativeFeatures!=null)try{nativeFeatures.append(param.args[0],((android.view.View)param.thisObject).getContext());}
                catch(Exception | LinkageError error){if(!nativeMenuFailed){nativeMenuFailed=true;failed("translationMenu",error);}}
                try {compat.apply(param.args[0]);}
                catch(Exception | LinkageError error){XposedBridge.log("QIII: menu unchanged: "+error.getClass().getSimpleName());}
            }
        });
    }
    private void installInvocationGate(ClassLoader loader)throws Exception {
        XposedHelpers.findAndHookMethod("com.tencent.mobileqq.quibadge.QUIBadge",loader,"updateNum",int.class,new XC_MethodHook(Integer.MAX_VALUE) {
            @Override protected void beforeHookedMethod(MethodHookParam param) {param.setObjectExtra("qqmm.gate",coordinator.countGate().enter());}
            @Override protected void afterHookedMethod(MethodHookParam param) {coordinator.countGate().leave(param.getObjectExtra("qqmm.gate"));}
        });
    }
}
