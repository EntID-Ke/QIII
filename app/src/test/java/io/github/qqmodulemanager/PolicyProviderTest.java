package io.github.qqmodulemanager;

import io.github.qqmodulemanager.bridge.BridgeClient;

import android.app.Application;
import android.content.pm.*;
import android.os.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class PolicyProviderTest {
    private Application app;
    private PolicyProvider provider;
    private int qqUid;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("policy",0).edit().clear().commit();
        app.getSharedPreferences("catalog",0).edit().clear().commit();
        qqUid=android.os.Process.myUid()+50;
        PackageInfo info=new PackageInfo();info.packageName="com.tencent.mobileqq";
        info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=info.packageName;info.applicationInfo.uid=qqUid;
        Shadows.shadowOf(app.getPackageManager()).installPackage(info);
        provider=Robolectric.buildContentProvider(PolicyProvider.class).create().get();
    }
    @After public void cleanup(){ShadowBinder.reset();}
    @Test(expected=SecurityException.class) public void unrelatedUidCannotReadPolicy(){ShadowBinder.setCallingUid(qqUid+1);provider.call("policy",null,null);}
    @Test(expected=SecurityException.class) public void unrelatedUidCannotExportTranslationCredentials(){ShadowBinder.setCallingUid(qqUid+1);provider.call("toolConfig",null,null);}
    @Test public void theManagerCanFindOnlyTheMainQqToolEndpoint()throws Exception{
        ShadowBinder.setCallingUid(qqUid);ShadowBinder.setCallingPid(101);
        Bundle background=new Bundle();background.putBinder("host",new Binder());background.putBinder("tools",new Binder());background.putString("report","{\"process\":\"com.tencent.mobileqq:MSF\"}");provider.call("report",null,background);
        assertNull(provider.call("tools",null,null).getBinder("tools"));
        ShadowBinder.setCallingPid(102);Binder tools=new Binder();Bundle main=new Bundle();main.putBinder("host",new Binder());main.putBinder("tools",tools);main.putString("report","{\"process\":\"com.tencent.mobileqq\"}");provider.call("report",null,main);
        ShadowBinder.reset();assertSame(tools,provider.call("tools",null,null).getBinder("tools"));
    }
    @Test public void qqEmbeddedPageAndLauncherSharePolicy()throws Exception{
        ShadowBinder.setCallingUid(qqUid);assertNotNull(provider.call("policy",null,null).getString("policy"));
        Bundle args=new Bundle();args.putString("policy",new JSONObject().put("menusEnabled",true).toString());
        provider.call("save",null,args);
        ShadowBinder.setCallingUid(android.os.Process.myUid());
        JSONObject snapshot=new JSONObject(provider.call("snapshot",null,null).getString("policy"));
        assertTrue(snapshot.getBoolean("menusEnabled"));assertEquals(1,snapshot.getInt("revision"));
    }
    @Test public void managerMenuItemsCanBeEditedBeforeAnyLiveMenuIsCaptured()throws Exception{
        JSONObject catalog=new JSONObject(provider.call("snapshot",null,null).getString("catalog"));
        for(String chat:new String[]{"group","private"}){
            JSONArray text=catalog.getJSONObject(chat+"/text").getJSONArray("items");
            assertEquals(2,text.length());assertEquals("manager:translation",text.getJSONObject(0).getString("key"));
            assertEquals("manager:translation_visible",text.getJSONObject(1).getString("key"));
            JSONArray image=catalog.getJSONObject(chat+"/image").getJSONArray("items");
            assertEquals(3,image.length());assertEquals("manager:lens",image.getJSONObject(2).getString("key"));
            assertEquals("manager",image.getJSONObject(2).getString("source"));assertTrue(image.getJSONObject(2).getBoolean("editable"));
            assertEquals(0,catalog.getJSONObject(chat+"/image").getLong("capturedAt"));
            for(String kind:new String[]{"voice","video","file"}){
                JSONArray other=catalog.getJSONObject(chat+"/"+kind).getJSONArray("items");
                assertEquals(1,other.length());assertEquals("manager:translation_visible",other.getJSONObject(0).getString("key"));
            }
        }
        assertFalse(new JSONObject(app.getSharedPreferences("catalog",0).getString("document","{}")).has("group/text"));
    }
    @Test public void seedingKnownItemsPreservesLiveQqEntriesAndNeverDuplicatesManagerActions()throws Exception{
        capture("group/text","qq:copy","复制");capture("group/text","manager:translation","翻译");
        JSONObject catalog=new JSONObject(provider.call("snapshot",null,null).getString("catalog"));
        JSONArray text=catalog.getJSONObject("group/text").getJSONArray("items");assertEquals(3,text.length());
        assertEquals("qq:copy",text.getJSONObject(0).getString("key"));assertEquals("manager:translation",text.getJSONObject(1).getString("key"));
    }
    @Test(expected=SecurityException.class) public void unrelatedUidCannotWritePolicy(){
        ShadowBinder.setCallingUid(qqUid+1);Bundle args=new Bundle();args.putString("policy","{}");provider.call("save",null,args);
    }
    @Test public void liveBinderStaysConnectedAfterAHeartbeatPause()throws Exception{
        ShadowBinder.setCallingUid(qqUid);ShadowBinder.setCallingPid(101);
        Binder binder=new Binder();Bundle report=new Bundle();report.putBinder("host",binder);report.putString("report","{\"process\":\"com.tencent.mobileqq\"}");provider.call("report",null,report);
        PolicyStore store=new PolicyStore(app);JSONObject runtime=store.runtime();runtime.getJSONObject("101").put("seenAt",1L);store.runtime(runtime);
        JSONObject snapshot=new JSONObject(provider.call("snapshot",null,null).getString("runtime"));
        assertTrue(snapshot.getJSONObject("101").getBoolean("connected"));
    }
    @Test public void reportsRestoreBinderRegistryAfterProviderRestart()throws Exception{
        ShadowBinder.setCallingUid(qqUid);ShadowBinder.setCallingPid(101);
        Bundle args=new Bundle();Binder host=new Binder();args.putBinder("host",host);args.putString("report","{}");
        provider.call("report",null,args);provider=Robolectric.buildContentProvider(PolicyProvider.class).create().get();
        assertNull(provider.call("host",null,null).getBinder("host"));
        assertFalse(new JSONObject(provider.call("snapshot",null,null).getString("runtime")).getJSONObject("101").getBoolean("connected"));
        provider.call("report",null,args);assertSame(host,provider.call("host",null,null).getBinder("host"));
    }
    @Test public void recentDeadHistoryCannotEvictALivingMainProcess()throws Exception{
        ShadowBinder.setCallingUid(qqUid);ShadowBinder.setCallingPid(101);
        Bundle live=new Bundle();live.putBinder("host",new Binder());live.putString("report","{\"process\":\"com.tencent.mobileqq\"}");provider.call("report",null,live);
        for(int pid=102;pid<115;pid++) {
            ShadowBinder.setCallingPid(pid);Bundle report=new Bundle();report.putString("report","{}");provider.call("report",null,report);
        }
        JSONObject runtime=new JSONObject(provider.call("snapshot",null,null).getString("runtime"));
        assertTrue(runtime.getJSONObject("101").getBoolean("connected"));assertEquals(8,runtime.length());
    }
    @Test public void onlyDeclaredBridgeVersionIsCompatible()throws Exception{
        PackageInfo info=new PackageInfo();info.packageName="io.github.qauxv";info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=info.packageName;
        Shadows.shadowOf(app.getPackageManager()).installPackage(info);
        ShadowBinder.setCallingUid(qqUid);JSONObject old=new JSONObject(provider.call("policy",null,null).getString("policy"));
        assertEquals(1,old.getJSONArray("installedModules").length());assertEquals(0,old.getJSONArray("compatibleModules").length());
        info.applicationInfo.metaData=new Bundle();info.applicationInfo.metaData.putInt("io.github.qqmodulemanager.bridgeVersion",BridgeClient.VERSION);Shadows.shadowOf(app.getPackageManager()).installPackage(info);
        JSONObject adapted=new JSONObject(provider.call("policy",null,null).getString("policy"));assertEquals("qauxv",adapted.getJSONArray("compatibleModules").getString(0));
    }
    @Test public void hostLookupUsesActualCallingPid(){
        ShadowBinder.setCallingUid(qqUid);ShadowBinder.setCallingPid(101);
        Binder host=new Binder();Bundle publish=new Bundle();publish.putBinder("host",host);provider.call("publish",null,publish);
        Bundle forged=new Bundle();forged.putInt("pid",101);
        ShadowBinder.setCallingPid(102);assertNull(provider.call("host",null,forged).getBinder("host"));
        ShadowBinder.setCallingPid(101);assertSame(host,provider.call("host",null,forged).getBinder("host"));
    }
    @Test public void oldAdapterCannotClaimTheNewCatalogProtocol()throws Exception{
        PackageInfo info=new PackageInfo();info.packageName="moe.ono";info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=info.packageName;
        info.applicationInfo.metaData=new Bundle();info.applicationInfo.metaData.putInt("io.github.qqmodulemanager.bridgeVersion",1);
        Shadows.shadowOf(app.getPackageManager()).installPackage(info);ShadowBinder.setCallingUid(qqUid);
        JSONObject policy=new JSONObject(provider.call("policy",null,null).getString("policy"));
        assertEquals(1,policy.getJSONArray("installedModules").length());assertEquals(0,policy.getJSONArray("compatibleModules").length());
    }
    @Test public void catalogMergesConditionalActions()throws Exception{
        ShadowBinder.setCallingUid(qqUid);
        capture("group/text","qq:copy","复制");capture("group/text","qq:recall","撤回");
        JSONObject catalog=new JSONObject(app.getSharedPreferences("catalog",0).getString("document","{}"));
        JSONArray items=catalog.getJSONObject("group/text").getJSONArray("items");
        assertEquals(2,items.length());assertEquals("qq:copy",items.getJSONObject(0).getString("key"));
        assertFalse(catalog.has("private/text"));
    }
    @Test(expected=IllegalArgumentException.class) public void invalidProfileRejected()throws Exception{ShadowBinder.setCallingUid(qqUid);capture("group/unsupported","x","x");}
    @Test public void independentProfilesAndRevisionSurviveSave()throws Exception{
        PolicyStore store=new PolicyStore(app);JSONObject policy=store.read();
        policy.put("menus",new JSONObject().put("group/text",new JSONObject().put("hidden",new JSONArray().put("qq:copy"))));store.save(policy);
        JSONObject next=store.read();next.getJSONObject("menus").put("private/image",new JSONObject().put("order",new JSONArray().put("qq:save")));store.save(next);
        JSONObject restored=new PolicyStore(app).read();assertEquals(2,restored.getLong("revision"));assertEquals(2,restored.getJSONObject("menus").length());
    }
    private void capture(String profile,String key,String title)throws Exception{
        JSONObject snapshot=new JSONObject().put("profile",profile).put("items",new JSONArray().put(new JSONObject().put("key",key).put("title",title).put("source","QQ")));
        Bundle b=new Bundle();b.putString("snapshot",snapshot.toString());provider.call("catalog",null,b);
    }
}
