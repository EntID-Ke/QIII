package io.github.qqmodulemanager;

import android.content.*;
import android.app.Activity;
import java.lang.ref.WeakReference;
import android.database.ContentObserver;
import android.os.*;
import io.github.qqmodulemanager.bridge.BridgeClient;
import io.github.qqmodulemanager.bridge.FeatureCatalog;
import io.github.qqmodulemanager.core.FeatureGate;
import io.github.qqmodulemanager.core.ProviderSelector;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One instance in each QQ process. No module-specific type crosses this boundary. */
public final class HostCoordinator extends Binder implements IInterface {
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "QIII-policy"));
    private final Map<String, Provider> providers = new ConcurrentHashMap<>();
    private final Set<String> ready = ConcurrentHashMap.newKeySet();
    private final Map<String, String> versions = new ConcurrentHashMap<>();
    private final Map<String, Consumer<Object>> contributors = new ConcurrentHashMap<>();
    private final Map<Object, Bundle> tags = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, FeatureGate> gates = new LinkedHashMap<>();
    private final Map<String, String> phases = new ConcurrentHashMap<>();
    private final JSONObject bootPolicy;
    private volatile JSONObject policy;
    private final Set<String> restartRequired = ConcurrentHashMap.newKeySet();
    private volatile boolean menuReady, countReady, settingsReady;
    private final Map<String,String> hostErrors=new ConcurrentHashMap<>();
    private final android.content.SharedPreferences cache;
    private final int qqVersion;
    private final String qqVersionName;
    private volatile String nativeMenuState="等待长按消息";
    private final String process;
    private WeakReference<Activity> visibleActivity=new WeakReference<>(null);
    private boolean restartPending;
    private volatile Runnable nativeChangeListener;
    private volatile IBinder tools;

    private static final class Provider {
        final String module, feature, version;
        final Callable<Boolean> initializer;
        volatile boolean initialized, failed, hooksStarted;
        String error = "";
        Provider(String m, String f, String v, Callable<Boolean> i) { module=m; feature=f; version=v; initializer=i; }
    }

    public HostCoordinator(Context context, String process) throws Exception {
        this.context=context; attachInterface(this, BridgeClient.DESCRIPTOR);
        this.process=process;
        for(FeatureCatalog.Feature f:FeatureCatalog.ALL)gates.put(f.id,new FeatureGate());
        android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
        qqVersion = Build.VERSION.SDK_INT>=28 ? (int)info.getLongVersionCode() : info.versionCode;
        qqVersionName=info.versionName==null?"未知":info.versionName;
        cache = context.getSharedPreferences(BridgeClient.cacheName(process), 0);
        policy = new JSONObject(cache.getString("policy", "{}"));
        try {
            Bundle response = context.getContentResolver().call(PolicyStore.URI, "policy", null, null);
            if(response!=null)policy = new JSONObject(response.getString("policy", "{}"));
        }catch(RuntimeException ignored) { /* The provider can start after QQ's Application.attach. */ }
        bootPolicy = new JSONObject(policy.toString());
        cache.edit().putString("policy", policy.toString()).putString("bootPolicy", bootPolicy.toString()).apply();
        try { context.getContentResolver().registerContentObserver(PolicyStore.URI, false, new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean self) { worker.execute(() -> reload()); }
        }); }catch(RuntimeException ignored) { /* Periodic refresh also recovers missed notifications. */ }
        worker.execute(this::reload);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { worker.execute(HostCoordinator.this::reload);new Handler(Looper.getMainLooper()).postDelayed(this,15000); }
        },15000);
    }

    @Override public IBinder asBinder() { return this; }
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
        if(code!=HostRestart.TRANSACTION_RESTART)return super.onTransact(code,data,reply,flags);
        data.enforceInterface(BridgeClient.DESCRIPTOR);
        try {
            int managerUid=context.getPackageManager().getApplicationInfo("io.github.qqmodulemanager",0).uid;
            if(Binder.getCallingUid()!=managerUid)throw new SecurityException("Manager restart request required");
        }catch(android.content.pm.PackageManager.NameNotFoundException error){throw new SecurityException("Manager not installed");}
        if(!process.equals("com.tencent.mobileqq"))throw new IllegalStateException("Main process required");
        Handler main=new Handler(Looper.getMainLooper());
        main.post(()->{
            if(restartPending)return;
            restartPending=true;restartWhenVisible();
            main.postDelayed(()->{if(restartPending){restartPending=false;adapterFailed("restart",new IllegalStateException("QQ foreground unavailable"));}},5000);
        });
        reply.writeNoException();return true;
    }
    public void resumed(Activity activity){visibleActivity=new WeakReference<>(activity);new Handler(Looper.getMainLooper()).post(this::restartWhenVisible);}
    public void paused(Activity activity){if(visibleActivity.get()==activity)visibleActivity.clear();}
    private void restartWhenVisible(){
        Activity activity=visibleActivity.get();
        if(!restartPending || activity==null || activity.isFinishing())return;
        restartPending=false;
        try{HostRestart.restart(context);}catch(RuntimeException error){adapterFailed("restart",error);}
    }
    public void foreground() { worker.execute(this::reload); }
    public void toolsReady(IBinder endpoint){tools=endpoint;worker.execute(this::publish);}
    public FeatureGate countGate() { return gates.get(BridgeClient.COUNT); }
    private Set<String> incompatibleModules() {
        Set<String> incompatible=new HashSet<>(MenuCompat.strings(policy.optJSONArray("installedModules")));
        incompatible.removeAll(MenuCompat.strings(policy.optJSONArray("compatibleModules")));return incompatible;
    }
    public boolean menuEnabled() { return menuReady && qqVersion == 16580 && incompatibleModules().isEmpty() && policy.optBoolean("menusEnabled"); }
    public boolean nativeSupported() { return qqVersion == 16580; }
    public boolean nativeEnabled(String feature) { return nativeSupported() && policy.optBoolean(feature+"Enabled",true); }
    public void setNativeChangeListener(Runnable listener) { nativeChangeListener=listener; }
    public void tagNative(Object item,String feature,String title) {
        Bundle tag=new Bundle();tag.putString("key","manager:"+feature);tag.putString("source","manager");tag.putString("title",title);tags.put(item,tag);
    }
    public void adapterReady(String name) {
        if(name.equals("menu"))menuReady=true;else if(name.equals("count"))countReady=true;else if(name.equals("settings"))settingsReady=true;
        hostErrors.remove(name);worker.execute(this::reconcile);
    }
    public void adapterFailed(String name,Throwable error) {
        DiagnosticLog.Companion.get(context).event("ERROR","hook",name,"",new JSONObject(),error);
        String detail=error instanceof ReflectiveOperationException || error instanceof LinkageError ? Objects.toString(error.getMessage(),"") : "";
        hostErrors.put(name,error.getClass().getSimpleName()+(detail.isEmpty()?"":" · "+detail));worker.execute(this::publish);
    }
    public void nativeMenuState(String state) {if(!state.equals(nativeMenuState)){nativeMenuState=state;worker.execute(this::publish);}}
    public JSONObject menuRule(String profile) {
        JSONObject menus=policy.optJSONObject("menus");
        return menus==null ? new JSONObject() : menus.optJSONObject(profile)==null ? new JSONObject() : menus.optJSONObject(profile);
    }
    public Bundle itemTag(Object item) { return tags.get(item); }
    public void contribute(Object menu) {
        // A stable module order makes the unconfigured tail deterministic.
        for (String module : List.of("qauxv", "ono", "qfun")) {
            Consumer<Object> callback=contributors.get(module);
            if (callback!=null)callback.accept(menu);
        }
    }
    public void capture(JSONObject snapshot) {
        worker.execute(() -> {
            try { Bundle b=new Bundle();b.putString("snapshot",snapshot.toString());context.getContentResolver().call(PolicyStore.URI,"catalog",null,b); }
            catch (RuntimeException ignored) { }
        });
    }

    @SuppressWarnings("unchecked")
    public Bundle invoke(String op, Bundle args, Object object) {
        String module=args.getString("module", ""), feature=args.getString("feature", "");
        Bundle out=new Bundle();
        switch(op) {
            case "register": {
                if (!gates.containsKey(feature) || !List.of("qauxv","ono","qfun").contains(module)) throw new IllegalArgumentException("Unknown provider");
                String version=args.getString("version", ""); versions.put(module, version);
                providers.putIfAbsent(feature+"/"+module,new Provider(module,feature,version,(Callable<Boolean>)object));
                worker.execute(this::reconcile); break;
            }
            case "ready": ready.add(module); worker.execute(this::reconcile); break;
            case "hooksStarting": {
                Provider p=providers.get(feature+"/"+module);
                if(p!=null && ("initializing:"+module).equals(phases.get(feature)))p.hooksStarted=true;
                break;
            }
            case "configuration": {
                Provider p=providers.get(feature+"/"+module);
                if(p!=null && ("initializing:"+module).equals(phases.get(feature)))p.error="NeedsConfiguration";
                break;
            }
            case "state": {
                out.putBoolean("managed",managed(feature));
                FeatureGate gate=gates.get(feature);
                out.putBoolean("effective",gate!=null && module.equals(gate.owner()));
                out.putBoolean("reportable",gate!=null && (module.equals(gate.owner()) || ("initializing:"+module).equals(phases.get(feature))));break;
            }
            case "menuState": out.putBoolean("enabled",menuEnabled()); break;
            case "tag": tags.put(object,new Bundle(args)); break;
            case "contributor": contributors.put(module,(Consumer<Object>)object); break;
            case "failure": worker.execute(() -> {
                Provider p=providers.get(feature+"/"+module);
                if(p!=null && !p.failed) {
                    fail(p,args.getString("error","CompatibilityError"));gates.get(feature).switchTo("");
                    if(FeatureCatalog.get(feature).coldSwitch())restartRequired.add(feature);
                    reconcile();
                }
            }); break;
            default: throw new IllegalArgumentException("Unsupported local operation");
        }
        return out;
    }

    private JSONObject featurePolicy(String feature) {
        FeatureCatalog.Feature f=FeatureCatalog.get(feature);
        JSONObject root=f!=null && f.coldSwitch() ? bootPolicy : policy;
        JSONObject fs=root.optJSONObject("features");
        return fs==null || fs.optJSONObject(feature)==null ? new JSONObject() : fs.optJSONObject(feature);
    }
    private boolean managed(String feature) { return gates.containsKey(feature) && featurePolicy(feature).optBoolean("enabled"); }
    private String quarantineTag(Provider p) { return p.version+"@"+qqVersion+"@"+featurePolicy(p.feature).optInt("retryEpoch"); }
    private void fail(Provider p,String error) {
        p.failed=true;if(!p.error.equals("NeedsConfiguration"))p.error=error;
        cache.edit().putString("failed."+p.feature+"/"+p.module,quarantineTag(p))
                .putString("reason."+p.feature+"/"+p.module,p.error).apply();
    }
    private void reload() {
        try {
            Bundle request=new Bundle();request.putBinder("host",this);
            Bundle b=context.getContentResolver().call(PolicyStore.URI,"publish",null,request);
            JSONObject next=new JSONObject(b.getString("policy","{}"));
            JSONObject before=bootPolicy.optJSONObject("features"), after=next.optJSONObject("features");
            for(FeatureCatalog.Feature f:FeatureCatalog.ALL)if(f.coldSwitch()) {
                JSONObject old=before==null?null:before.optJSONObject(f.id), nextFeature=after==null?null:after.optJSONObject(f.id);
                if(((old!=null && old.optBoolean("enabled")) || (nextFeature!=null && nextFeature.optBoolean("enabled")))
                        && !String.valueOf(old).equals(String.valueOf(nextFeature)))restartRequired.add(f.id);
            }
            policy=next;cache.edit().putString("policy",policy.toString()).apply();reconcile();
            if(nativeChangeListener!=null)nativeChangeListener.run();
        } catch(Exception ignored) { /* Retain the last complete policy. */ }
    }
    private void reconcile() {
        for(FeatureCatalog.Feature definition:FeatureCatalog.ALL) {
            String feature=definition.id;
            FeatureGate gate=gates.get(feature);
            if(restartRequired.contains(feature)) { phases.put(feature,"restart_required");continue; }
            if(!managed(feature)) { gate.switchTo(""); phases.put(feature,"manual");continue; }
            if(!process.equals("com.tencent.mobileqq") && !definition.allProcesses) { gate.switchTo("");phases.put(feature,"managed_in_main");continue; }
            if(feature.equals(BridgeClient.COUNT) && !countReady) {gate.switchTo("");phases.put(feature,"host_adapter_unavailable");continue;}
            Set<String> unadapted=incompatibleModules();unadapted.retainAll(MenuCompat.strings(featurePolicy(feature).optJSONArray("order")));
            if(!unadapted.isEmpty()){gate.switchTo("");phases.put(feature,"incompatible_adapter");continue;}
            if(qqVersion!=16580) { gate.switchTo("");phases.put(feature,"unsupported_qq");continue; }
            JSONArray order=featurePolicy(feature).optJSONArray("order");
            JSONArray participants=featurePolicy(feature).optJSONArray("participants");
            Set<String> expected=new HashSet<>();
            if(participants!=null)for(int i=0;i<participants.length();i++)expected.add(participants.optString(i));
            expected.retainAll(new HashSet<>(MenuCompat.strings(policy.optJSONArray("compatibleModules"))));
            ProviderSelector.Result result=ProviderSelector.select(MenuCompat.strings(order),expected,module->{
                Provider p=providers.get(feature+"/"+module);
                if(p==null)return null;
                return new ProviderSelector.Candidate() {
                    public boolean ready(){return ready.contains(module);}
                    public boolean failed(){return p.failed || quarantineTag(p).equals(cache.getString("failed."+feature+"/"+module,""));}
                    public boolean initialized(){return p.initialized;}
                    public boolean initialize()throws Exception{phases.put(feature,"initializing:"+module);publish();boolean ok=p.initializer.call();p.initialized=ok;return ok;}
                    public void recordFailure(Throwable t){fail(p,t.getClass().getSimpleName());}
                    public boolean dirtyOnFailure(){return p.hooksStarted || (feature.equals(BridgeClient.RECALL)&&module.equals("qauxv"));}
                };
            });
            phases.put(feature,result.phase);
            if(result.phase.equals("restart_required"))restartRequired.add(feature);
            else if(!result.phase.equals("waiting")){gate.switchTo(result.owner);cache.edit().putString("owner."+feature,result.owner).apply();}
        }
        publish();
    }
    private void publish() {
        try {
            JSONObject report=new JSONObject();report.put("qqVersionCode",qqVersion);report.put("revision",policy.optLong("revision"));report.put("process",process);
            report.put("qqVersionName",qqVersionName);report.put("managerVersion",BuildConfig.VERSION_NAME);report.put("nativeMenuState",nativeMenuState);
            report.put("coldPending",!restartRequired.isEmpty());report.put("menusEnabled",menuEnabled());report.put("settingsReady",settingsReady);
            report.put("hostErrors",new JSONObject(hostErrors));
            report.put("incompatibleModules",new JSONArray(incompatibleModules()));
            report.put("modules",new JSONObject(versions));
            JSONObject fs=new JSONObject();
            for(String feature:gates.keySet()) {
                JSONObject f=new JSONObject();f.put("owner",gates.get(feature).owner());f.put("phase",phases.getOrDefault(feature,"waiting"));
                JSONObject ps=new JSONObject();
                for(Provider p:providers.values())if(p.feature.equals(feature)) {
                    JSONObject s=new JSONObject();s.put("ready",ready.contains(p.module));s.put("initialized",p.initialized);
                    boolean quarantined=quarantineTag(p).equals(cache.getString("failed."+feature+"/"+p.module,""));
                    s.put("failed",p.failed || quarantined);
                    s.put("error",p.error.isEmpty() && quarantined ? cache.getString("reason."+feature+"/"+p.module,"") : p.error);ps.put(p.module,s);
                }
                f.put("providers",ps);fs.put(feature,f);
            }
            report.put("features",fs);
            report.put("toolExecutor",tools==null?"not-ready":"com.tencent.mobileqq");
            Bundle b=new Bundle();b.putBinder("host",this);b.putBinder("tools",tools);b.putString("report",report.toString());context.getContentResolver().call(PolicyStore.URI,"report",null,b);
        }catch(Exception ignored) { }
    }
}
