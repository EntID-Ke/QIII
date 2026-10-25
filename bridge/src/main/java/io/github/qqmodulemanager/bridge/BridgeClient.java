package io.github.qqmodulemanager.bridge;

import android.content.Context;
import android.net.Uri;
import android.os.*;
import android.os.Process;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Vendored into each adapter. Only platform types cross module class loaders. */
public final class BridgeClient {
    public static final String AUTHORITY = "io.github.qqmodulemanager.bridge";
    public static final int VERSION = 2;
    public static final String DESCRIPTOR = AUTHORITY + ".host.v2";
    public static final String RECALL = "anti_revoke", COUNT = "message_count";
    private static volatile Context context;
    private static volatile Object coordinator;
    private static volatile Method invoke;
    private static String module = "", version = "";
    private static String cacheName = cacheName("com.tencent.mobileqq");
    private static final ThreadLocal<String> initializing = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, Callable<Boolean>> registrations = new ConcurrentHashMap<>();
    private static volatile boolean moduleReady;
    private static volatile Consumer<Object> menuContributor;
    private static long nextConnectionAttempt;
    private static int retries;
    private static boolean retryScheduled;

    private BridgeClient() {}

    public static void initialize(Context ctx, String id, String build) {
        if (!"com.tencent.mobileqq".equals(ctx.getPackageName())) return;
        context = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        String process = Build.VERSION.SDK_INT >= 28 ? android.app.Application.getProcessName() : null;
        if (process == null) {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getRunningAppProcesses() != null) for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses())
                if (p.pid == Process.myPid()) { process = p.processName; break; }
        }
        cacheName = cacheName(process == null ? ctx.getPackageName() : process);
        module = id; version = build;
        connect();
    }

    private static synchronized void connect() {
        if (context == null || coordinator != null || SystemClock.uptimeMillis() < nextConnectionAttempt) return;
        nextConnectionAttempt = SystemClock.uptimeMillis() + 1000;
        try {
            Bundle request = new Bundle(); request.putInt("pid", Process.myPid());
            Bundle result = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY), "host", null, request);
            IBinder binder = result == null ? null : result.getBinder("host");
            Object endpoint = binder == null ? null : binder.queryLocalInterface(DESCRIPTOR);
            if (endpoint != null) {
                Method method = endpoint.getClass().getMethod("invoke", String.class, Bundle.class, Object.class);
                invoke = method; coordinator = endpoint;
                for (String feature : registrations.keySet()) {
                    Bundle registration = new Bundle(); registration.putString("feature", feature);
                    registration.putString("module", module); registration.putString("version", version);
                    method.invoke(endpoint, "register", registration, registrations.get(feature));
                }
                if (moduleReady) { Bundle ready = new Bundle(); ready.putString("module", module); method.invoke(endpoint, "ready", ready, null); }
                if (menuContributor != null) { Bundle contribution = new Bundle(); contribution.putString("module", module); method.invoke(endpoint, "contributor", contribution, menuContributor); }
            }
        } catch (RuntimeException | ReflectiveOperationException ignored) {
            coordinator = null;
            // No management has ever been enabled: preserve the module's normal behavior.
        }
        if (coordinator == null && !retryScheduled) {
            retryScheduled=true;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                synchronized(BridgeClient.class){retryScheduled=false;}
                connect();
            },retries++ < 15 ? 1000 : 15000);
        }
    }

    private static Bundle call(String operation, Bundle args, Object object) {
        if (coordinator == null) connect();
        args.putString("module", module); args.putString("version", version);
        try {
            Object endpoint = coordinator;
            if (endpoint != null) return (Bundle) invoke.invoke(endpoint, operation, args, object);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            coordinator = null;
        }
        return null;
    }

    public static String featureForClass(String name) {
        return FeatureCatalog.forClass(name);
    }

    public static String cacheName(String process) { return "qqmm_bridge_v2_" + process.replace(':', '_'); }

    /** Called immediately before an initializer may install hooks or mutate host state. */
    public static void hooksStarting(String feature) {
        if (!feature.isEmpty() && feature.equals(initializing.get())) {
            Bundle args = new Bundle(); args.putString("feature", feature);
            call("hooksStarting", args, null);
        }
    }
    public static void configurationRequired(String feature) {
        if(feature.equals(initializing.get())) {
            Bundle args=new Bundle();args.putString("feature",feature);call("configuration",args,null);
        }
    }

    public static void registerFeature(String feature, Callable<Boolean> initializer) {
        Bundle args = new Bundle(); args.putString("feature", feature);
        Callable<Boolean> guarded = () -> {
            initializing.set(feature);
            try { return initializer.call(); } finally { initializing.remove(); }
        };
        registrations.put(feature, guarded);
        call("register", args, guarded);
    }

    public static void ready() { moduleReady = true; call("ready", new Bundle(), null); }

    private static Bundle state(String feature) {
        Bundle args = new Bundle(); args.putString("feature", feature);
        Bundle result = call("state", args, null);
        if (result != null) return result;
        Bundle cached = new Bundle();
        if (context != null) try {
            android.content.SharedPreferences saved = context.getSharedPreferences(cacheName, 0);
            FeatureCatalog.Feature definition = FeatureCatalog.get(feature);
            JSONObject policy = new JSONObject(saved.getString(definition != null && definition.coldSwitch() ? "bootPolicy" : "policy", "{}"));
            cached.putBoolean("managed", policy.optJSONObject("features") != null && policy.getJSONObject("features").optJSONObject(feature) != null
                    && policy.getJSONObject("features").getJSONObject(feature).optBoolean("enabled"));
            cached.putBoolean("effective", module.equals(saved.getString("owner." + feature, "")));
        } catch (Exception ignored) { }
        return cached;
    }

    public static boolean isManaged(String feature) { return !feature.isEmpty() && state(feature).getBoolean("managed"); }
    public static boolean effective(String feature, boolean original) {
        if (feature.isEmpty()) return original;
        Bundle result = state(feature);
        return !result.getBoolean("managed") ? original : feature.equals(initializing.get()) || result.getBoolean("effective");
    }
    public static boolean deferInitialization(String feature) {
        return !feature.isEmpty() && !feature.equals(initializing.get()) && isManaged(feature);
    }
    public static boolean menuManaged() {
        Bundle result = call("menuState", new Bundle(), null);
        if (result != null) return result.getBoolean("enabled");
        if (context != null) try {
            return new JSONObject(context.getSharedPreferences(cacheName, 0).getString("policy", "{}")).optBoolean("menusEnabled");
        } catch (Exception ignored) { }
        return false;
    }

    public static void reportFailure(String feature, Throwable failure) {
        // Bad message contents and transient application errors do not prove incompatibility.
        if (!(failure instanceof LinkageError || failure instanceof ReflectiveOperationException || failure instanceof ClassCastException)) return;
        reportConfirmedFailure(feature, failure);
    }
    public static void reportConfirmedFailure(String feature, Throwable failure) {
        if (feature.isEmpty()) return;
        Bundle status = state(feature);
        if (!status.getBoolean("managed") || !status.getBoolean("reportable")) return;
        Bundle args = new Bundle(); args.putString("feature", feature);
        args.putString("error", failure.getClass().getSimpleName());
        call("failure", args, null);
    }

    public static void registerMenuContributor(Consumer<Object> contributor) {
        menuContributor = contributor;
        call("contributor", new Bundle(), contributor);
    }

    public static void tagMenuItem(Object item, String namespace, String action) {
        tagMenuItem(item, namespace, action, null);
    }
    public static void tagMenuItem(Object item, String namespace, String action, String title) {
        Bundle args = new Bundle(); args.putString("key", namespace + ":" + action);
        if (title != null) args.putString("title", title);
        args.putString("source", namespace); call("tag", args, item);
    }

    public static void tagResourceItem(Object item, String namespace, Class<?> ids, int id, String title) {
        String action = null;
        try {
            for (Field field : ids.getFields()) if (field.getType() == int.class && Modifier.isStatic(field.getModifiers()) && field.getInt(null) == id) {
                action = field.getName(); break;
            }
        } catch (ReflectiveOperationException ignored) { }
        // Keep an unresolved ID local to this build rather than assigning it another action's rules.
        tagMenuItem(item, namespace, action == null ? version + ":" + id + ":" + title : action, title);
    }
}
