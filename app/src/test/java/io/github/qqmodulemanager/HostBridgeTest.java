package io.github.qqmodulemanager;

import android.app.Application;
import android.content.*;
import android.content.pm.*;
import android.os.Bundle;
import io.github.qqmodulemanager.bridge.BridgeClient;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;
import java.util.function.Consumer;
import com.tencent.qqnt.aio.menu.ui.c;
import com.tencent.qqnt.aio.menu.ui.e;
import com.tencent.mobileqq.aio.msg.AIOMsgItem;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import static org.junit.Assert.*;

/** Exercise real Binder discovery and the vendored bridge in separate module class loaders. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class HostBridgeTest {
    private Application app;
    private Context qq;
    private HostCoordinator host;
    private PolicyStore store;
    @Before public void setup()throws Exception{
        app=RuntimeEnvironment.getApplication();store=new PolicyStore(app);
        for(String name:new String[]{"policy","runtime",BridgeClient.cacheName("com.tencent.mobileqq")})app.getSharedPreferences(name,0).edit().clear().commit();
        for(String name:new String[]{"com.tencent.mobileqq","io.github.qauxv","me.yxp.qfun"}){
            PackageInfo p=new PackageInfo();p.packageName=name;p.versionCode=16580;
            p.applicationInfo=new ApplicationInfo();p.applicationInfo.packageName=name;p.applicationInfo.uid=android.os.Process.myUid()+50;
            p.applicationInfo.metaData=new Bundle();p.applicationInfo.metaData.putInt("io.github.qqmodulemanager.bridgeVersion",BridgeClient.VERSION);
            Shadows.shadowOf(app.getPackageManager()).installPackage(p);
        }
        PolicyProvider provider=Robolectric.buildContentProvider(PolicyProvider.class).create().get();
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,provider);
        ShadowBinder.setCallingUid(android.os.Process.myUid()+50);ShadowBinder.setCallingPid(731);
        qq=new ContextWrapper(app){@Override public String getPackageName(){return "com.tencent.mobileqq";}};
    }
    @After public void cleanup()throws Exception{
        if(host!=null)executor().shutdownNow();ShadowBinder.reset();
    }
    private ExecutorService executor()throws Exception{
        java.lang.reflect.Field f=HostCoordinator.class.getDeclaredField("worker");f.setAccessible(true);return (ExecutorService)f.get(host);
    }
    private void flush()throws Exception{executor().submit(()->{}).get(10,TimeUnit.SECONDS);}
    private void policy(String feature,boolean enabled,String...order)throws Exception{
        JSONObject root=store.read();JSONObject fs=root.optJSONObject("features");if(fs==null)fs=new JSONObject();
        fs.put(feature,new JSONObject().put("enabled",enabled).put("order",new JSONArray(order)).put("participants",new JSONArray(order)));
        root.put("features",fs);store.save(root);
    }
    private void host()throws Exception{host=new HostCoordinator(qq,"com.tencent.mobileqq");host.adapterReady("count");flush();}
    private static final class Module {
        private final Class<?> bridge;
        Module(Context context,String id)throws Exception{
            ClassLoader loader=new ClassLoader(BridgeClient.class.getClassLoader()){
                @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                    if(!name.equals(BridgeClient.class.getName()))return super.loadClass(name,resolve);
                    Class<?> existing=findLoadedClass(name);if(existing!=null)return existing;
                    try(InputStream in=getParent().getResourceAsStream(name.replace('.','/')+".class")){
                        byte[] bytes=in.readAllBytes();Class<?> type=defineClass(name,bytes,0,bytes.length);if(resolve)resolveClass(type);return type;
                    }catch(IOException e){throw new ClassNotFoundException(name,e);}
                }
            };
            bridge=loader.loadClass(BridgeClient.class.getName());
            bridge.getMethod("initialize",Context.class,String.class,String.class).invoke(null,context,id,"test-v1");
        }
        void register(String feature,Callable<Boolean> init)throws Exception{bridge.getMethod("registerFeature",String.class,Callable.class).invoke(null,feature,init);}
        void ready()throws Exception{bridge.getMethod("ready").invoke(null);}
        boolean enabled(String feature)throws Exception{return (boolean)bridge.getMethod("effective",String.class,boolean.class).invoke(null,feature,false);}
        boolean originalEnabled(String feature,boolean original)throws Exception{return (boolean)bridge.getMethod("effective",String.class,boolean.class).invoke(null,feature,original);}
        void failure(String feature)throws Exception{bridge.getMethod("reportFailure",String.class,Throwable.class).invoke(null,feature,new NoSuchFieldError());}
        void tag(Object item,String action)throws Exception{bridge.getMethod("tagMenuItem",Object.class,String.class,String.class,String.class).invoke(null,item,"qfun",action,"复制");}
        void contributor(Consumer<Object> callback)throws Exception{bridge.getMethod("registerMenuContributor",Consumer.class).invoke(null,callback);}
        void installing(String feature)throws Exception{bridge.getMethod("hooksStarting",String.class).invoke(null,feature);}
        void needsConfiguration(String feature)throws Exception{bridge.getMethod("configurationRequired",String.class).invoke(null,feature);}
    }
    @Test public void separateClassLoadersShareOneOwnerAndHotFailover()throws Exception{
        policy(BridgeClient.COUNT,true,"qauxv","qfun");host();AtomicInteger qaCalls=new AtomicInteger(),qfunCalls=new AtomicInteger();
        Module qa=new Module(qq,"qauxv"),qfun=new Module(qq,"qfun");
        qa.register(BridgeClient.COUNT,()->{qaCalls.incrementAndGet();return true;});
        qfun.register(BridgeClient.COUNT,()->{qfunCalls.incrementAndGet();return true;});
        qfun.ready();flush();assertEquals(0,qfunCalls.get()); // Higher-ranked module is still preparing.
        qa.ready();flush();assertTrue(qa.enabled(BridgeClient.COUNT));assertFalse(qfun.enabled(BridgeClient.COUNT));
        qa.failure(BridgeClient.COUNT);flush();assertFalse(qa.enabled(BridgeClient.COUNT));assertTrue(qfun.enabled(BridgeClient.COUNT));
        assertEquals(1,qaCalls.get());assertEquals(1,qfunCalls.get());
    }
    @Test public void unmanagedQfunTextActionsRetainTheirOriginalSwitches()throws Exception{
        policy("edit_resend",false,"qfun","qauxv");policy("local_text",false,"qfun","ono");host();
        Module qfun=new Module(qq,"qfun");AtomicInteger calls=new AtomicInteger();
        for(String feature:List.of("edit_resend","local_text"))qfun.register(feature,()->{calls.incrementAndGet();return true;});
        qfun.ready();flush();
        for(String feature:List.of("edit_resend","local_text")){
            assertTrue(qfun.originalEnabled(feature,true));assertFalse(qfun.originalEnabled(feature,false));
        }
        assertEquals(0,calls.get());
    }
    @Test public void registrationCanPrecedeHostPublication()throws Exception{
        policy(BridgeClient.COUNT,true,"qauxv");JSONObject root=store.read();root.put("menusEnabled",true);store.save(root);
        Module qa=new Module(qq,"qauxv");AtomicInteger calls=new AtomicInteger();
        e extra=new e(2,"备用动作",message(2,1));qa.contributor(model->((c)model).a.add(extra));
        qa.register(BridgeClient.COUNT,()->{calls.incrementAndGet();return true;});qa.ready();
        host();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));flush();
        assertTrue(qa.enabled(BridgeClient.COUNT));assertEquals(1,calls.get());
        host.adapterReady("menu");flush();c menu=new c(List.of(new e(1,"复制",message(2,1))));
        new MenuCompat(getClass().getClassLoader(),host).apply(menu);assertTrue(menu.a.contains(extra));
    }
    @Test public void latePublicationStillReconnectsAfterTheStartupRetryWindow()throws Exception{
        policy(BridgeClient.COUNT,true,"qauxv");Module qa=new Module(qq,"qauxv");AtomicInteger calls=new AtomicInteger();
        qa.register(BridgeClient.COUNT,()->{calls.incrementAndGet();return true;});qa.ready();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(22));
        host();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));flush();
        assertTrue(qa.enabled(BridgeClient.COUNT));assertEquals(1,calls.get());
    }
    @Test public void providerRestartDoesNotReinitializeHooksOrLoseTheRegistry()throws Exception{
        policy(BridgeClient.COUNT,true,"qauxv");host();Module qa=new Module(qq,"qauxv");AtomicInteger calls=new AtomicInteger();
        qa.register(BridgeClient.COUNT,()->{calls.incrementAndGet();return true;});qa.ready();flush();
        PolicyProvider restarted=Robolectric.buildContentProvider(PolicyProvider.class).create().get();
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,restarted);
        assertNull(restarted.call("host",null,null).getBinder("host"));
        host.foreground();flush();assertSame(host,restarted.call("host",null,null).getBinder("host"));
        assertTrue(qa.enabled(BridgeClient.COUNT));assertEquals(1,calls.get());
    }
    @Test public void startupWithoutTheProviderStillCreatesTheHostAndRecovers()throws Exception{
        android.content.ContentProvider unavailable=new android.content.ContentProvider(){
            public boolean onCreate(){return true;}
            public Bundle call(String m,String a,Bundle b){throw new IllegalStateException("Provider starting");}
            public android.database.Cursor query(android.net.Uri u,String[] p,String s,String[] a,String o){return null;}
            public String getType(android.net.Uri u){return null;}
            public android.net.Uri insert(android.net.Uri u,ContentValues v){return null;}
            public int delete(android.net.Uri u,String s,String[] a){return 0;}
            public int update(android.net.Uri u,ContentValues v,String s,String[] a){return 0;}
        };
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,unavailable);
        host();host.adapterReady("settings");flush();assertNotNull(host);
        PolicyProvider recovered=Robolectric.buildContentProvider(PolicyProvider.class).create().get();
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,recovered);
        host.foreground();flush();assertSame(host,recovered.call("host",null,null).getBinder("host"));
        assertTrue(store.runtime().getJSONObject("731").getBoolean("settingsReady"));
    }
    @Test public void managerCanRequestNativeRestartThroughTheProvider()throws Exception{
        host();
        host.resumed(Robolectric.buildActivity(android.app.Activity.class).setup().get());
        Shadows.shadowOf(app.getPackageManager()).addActivityIfNotPresent(new ComponentName("com.tencent.mobileqq",HostRestart.ACTIVITY));
        ShadowBinder.setCallingUid(android.os.Process.myUid());
        Bundle result=app.getContentResolver().call(PolicyStore.URI,"restart",null,null);assertTrue(result.getBoolean("running"));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Intent intent=Shadows.shadowOf(app).getNextStartedActivity();assertNotNull(intent);
        assertEquals(new ComponentName("com.tencent.mobileqq",HostRestart.ACTIVITY),intent.getComponent());
    }
    @Test public void aBackgroundRestartRequestWaitsUntilQqHasAResumedActivity()throws Exception{
        host();Shadows.shadowOf(app.getPackageManager()).addActivityIfNotPresent(new ComponentName("com.tencent.mobileqq",HostRestart.ACTIVITY));
        ShadowBinder.setCallingUid(android.os.Process.myUid());
        assertTrue(app.getContentResolver().call(PolicyStore.URI,"restart",null,null).getBoolean("running"));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        host.resumed(Robolectric.buildActivity(android.app.Activity.class).setup().get());
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(new ComponentName("com.tencent.mobileqq",HostRestart.ACTIVITY),Shadows.shadowOf(app).getNextStartedActivity().getComponent());
    }
    @Test public void unrelatedUidCannotInvokeTheRestartBinder()throws Exception{
        host();ShadowBinder.setCallingUid(android.os.Process.myUid()+200);
        android.os.Parcel data=android.os.Parcel.obtain(),reply=android.os.Parcel.obtain();
        try{
            data.writeInterfaceToken(BridgeClient.DESCRIPTOR);data.setDataPosition(0);
            try{host.onTransact(HostRestart.TRANSACTION_RESTART,data,reply,0);fail("Unrelated caller must be rejected");}catch(SecurityException expected){}
        }finally{data.recycle();reply.recycle();}
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void partialNativeFailureDoesNotInitializeBackup()throws Exception{
        policy(BridgeClient.RECALL,true,"qauxv","qfun");host();Module qa=new Module(qq,"qauxv"),qfun=new Module(qq,"qfun");AtomicInteger backups=new AtomicInteger();
        qa.register(BridgeClient.RECALL,()->false);qfun.register(BridgeClient.RECALL,()->{backups.incrementAndGet();return true;});
        qfun.ready();qa.ready();flush();assertFalse(qa.enabled(BridgeClient.RECALL));assertFalse(qfun.enabled(BridgeClient.RECALL));assertEquals(0,backups.get());
    }
    @Test public void coldChangeDoesNotPauseAnUnrelatedFeature()throws Exception{
        policy("default_bubble",true,"qauxv","qfun");policy(BridgeClient.COUNT,true,"qauxv");host();
        Module qa=new Module(qq,"qauxv"),qfun=new Module(qq,"qfun");
        qa.register("default_bubble",()->true);qfun.register("default_bubble",()->true);qa.register(BridgeClient.COUNT,()->true);
        qa.ready();qfun.ready();flush();assertTrue(qa.enabled("default_bubble"));assertTrue(qa.enabled(BridgeClient.COUNT));
        policy("default_bubble",true,"qfun","qauxv");Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();flush();
        assertTrue(qa.enabled("default_bubble"));assertFalse(qfun.enabled("default_bubble"));assertTrue(qa.enabled(BridgeClient.COUNT));
        JSONObject report=store.runtime().getJSONObject("731").getJSONObject("features");
        assertEquals("restart_required",report.getJSONObject("default_bubble").getString("phase"));
        assertEquals("active",report.getJSONObject(BridgeClient.COUNT).getString("phase"));
    }
    @Test public void failureAfterInstallingHooksDoesNotStackABackup()throws Exception{
        policy("default_font",true,"qauxv","qfun");host();Module qa=new Module(qq,"qauxv"),qfun=new Module(qq,"qfun");AtomicInteger calls=new AtomicInteger();
        qa.register("default_font",()->{qa.installing("default_font");return false;});qfun.register("default_font",()->{calls.incrementAndGet();return true;});
        qa.ready();qfun.ready();flush();assertEquals(0,calls.get());
        assertEquals("restart_required",store.runtime().getJSONObject("731").getJSONObject("features").getJSONObject("default_font").getString("phase"));
    }
    @Test public void unsupportedProviderCanFallThroughBeforeInstallingHooks()throws Exception{
        policy("default_bubble",true,"qauxv","qfun");host();Module qa=new Module(qq,"qauxv"),qfun=new Module(qq,"qfun");
        qa.register("default_bubble",()->false);qfun.register("default_bubble",()->true);qa.ready();qfun.ready();flush();
        assertFalse(qa.enabled("default_bubble"));assertTrue(qfun.enabled("default_bubble"));
    }
    @Test public void missingConfigurationIsReportedAndSurvivesRestart()throws Exception{
        policy("device_model",true,"qauxv","qfun");host();Module qa=new Module(qq,"qauxv"),qfun=new Module(qq,"qfun");
        qa.register("device_model",()->{qa.needsConfiguration("device_model");return false;});qfun.register("device_model",()->true);
        qa.ready();qfun.ready();flush();assertTrue(qfun.enabled("device_model"));
        assertEquals("NeedsConfiguration",store.runtime().getJSONObject("731").getJSONObject("features").getJSONObject("device_model").getJSONObject("providers").getJSONObject("qauxv").getString("error"));
        executor().shutdownNow();host();Module nextQa=new Module(qq,"qauxv"),nextQfun=new Module(qq,"qfun");
        nextQa.register("device_model",()->{throw new AssertionError("Quarantined provider must be skipped");});nextQfun.register("device_model",()->true);
        nextQa.ready();nextQfun.ready();flush();assertTrue(nextQfun.enabled("device_model"));
        assertEquals("NeedsConfiguration",store.runtime().getJSONObject("731").getJSONObject("features").getJSONObject("device_model").getJSONObject("providers").getJSONObject("qauxv").getString("error"));
    }
    private AIOMsgItem message(int chat,int kind){return new AIOMsgItem(new MsgRecord(chat,List.of(new MsgElement(kind))));}
    @Test public void menuKeepsClickObjectsAndSeparatesSameTitles()throws Exception{
        e nativeCopy=new e(1,"复制",message(2,1)),moduleCopy=new e(2,"复制",message(2,1)),extra=new e(3,"复制",message(2,1));
        String nativeKey="qq:16580:"+e.class.getName()+":1";
        JSONObject root=store.read();root.put("menusEnabled",true).put("menus",new JSONObject().put("group/text",new JSONObject().put("hidden",new JSONArray().put(nativeKey)).put("order",new JSONArray().put("qfun:copy"))));store.save(root);
        host();host.adapterReady("menu");flush();Module qfun=new Module(qq,"qfun");qfun.tag(moduleCopy,"copy");qfun.tag(extra,"extra");qfun.contributor(model->((c)model).a.add(extra));
        c model=new c(Collections.unmodifiableList(List.of(nativeCopy,moduleCopy)));new MenuCompat(getClass().getClassLoader(),host).apply(model);
        assertEquals(2,model.a.size());assertSame(moduleCopy,model.a.get(0));assertSame(extra,model.a.get(1));model.a.get(0).k();assertEquals(1,moduleCopy.clicks);assertEquals(0,nativeCopy.clicks);
    }
    @Test public void failedPolicyProcessingPreservesContributedActions()throws Exception{
        JSONObject root=store.read();root.put("menusEnabled",true);store.save(root);host();host.adapterReady("menu");flush();
        Module qfun=new Module(qq,"qfun");e original=new e(1,"复制",new AIOMsgItem(null)),extra=new e(2,"复读",message(2,1));qfun.contributor(model->((c)model).a.add(extra));
        c model=new c(Collections.unmodifiableList(List.of(original)));
        try{new MenuCompat(getClass().getClassLoader(),host).apply(model);fail("Invalid record must fail transformation");}catch(NullPointerException expected){}
        assertEquals(List.of(original,extra),model.a);model.a.get(1).k();assertEquals(1,extra.clicks);
    }
    @Test public void allTenMenuProfilesAreCapturedWithoutMessageContents()throws Exception{
        JSONObject root=store.read();root.put("menusEnabled",true);store.save(root);host();host.adapterReady("menu");flush();MenuCompat compat=new MenuCompat(getClass().getClassLoader(),host);
        for(int chat:new int[]{1,2})for(int type:new int[]{1,2,3,4,5}){e item=new e(10,"动作",message(chat,type));c model=new c(List.of(item));compat.apply(model);assertSame(item,model.a.get(0));}
        flush();JSONObject catalog=new JSONObject(app.getSharedPreferences("catalog",0).getString("document","{}"));assertEquals(10,catalog.length());
        assertFalse(catalog.toString().contains("getText"));assertTrue(catalog.has("group/text"));assertTrue(catalog.has("private/file"));
    }
}
