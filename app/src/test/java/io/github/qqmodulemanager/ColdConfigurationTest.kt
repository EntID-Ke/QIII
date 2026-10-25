package io.github.qqmodulemanager

import android.app.Activity
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import io.github.qqmodulemanager.bridge.BridgeClient

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ColdConfigurationTest {
    private class ColdProvider(private val delegate:PolicyProvider):ContentProvider() {
        var ready=false
        var beforeCall:(String)->Unit={}
        override fun onCreate()=true
        override fun call(method:String,arg:String?,extras:Bundle?):Bundle? {
            if(!ready)throw IllegalArgumentException("Unknown authority: ${BridgeClient.AUTHORITY}")
            beforeCall(method)
            if(method=="restart")return Bundle().apply{putBoolean("running",true)}
            return delegate.call(method,arg,extras)
        }
        override fun query(u:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?=null
        override fun getType(u:Uri):String?=null
        override fun insert(u:Uri,v:ContentValues?):Uri?=null
        override fun delete(u:Uri,s:String?,a:Array<out String>?)=0
        override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
    }
    @Test fun qqCanStartTheConfigurationProcessWithoutOpeningTheLauncherPage()=runBlocking {
        val app=RuntimeEnvironment.getApplication();val real=Robolectric.buildContentProvider(PolicyProvider::class.java).create().get()
        val cold=ColdProvider(real);ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,cold)
        var launch:Intent?=null
        val qq=object:ContextWrapper(app) {
            override fun getPackageName()="com.tencent.mobileqq"
            override fun getApplicationContext():Context=this
            override fun startActivity(intent:Intent){launch=intent;cold.ready=true}
        }
        val repo=ManagerRepository(qq);repo.update{it.put("menusEnabled",true)}
        assertTrue(repo.read().policy.getBoolean("menusEnabled"))
        assertEquals(ComponentName("io.github.qqmodulemanager","io.github.qqmodulemanager.BridgeBootstrapActivity"),launch!!.component)
        assertTrue(launch!!.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION!=0)
    }
    @Test fun bootstrapDoesNotShowTheManagerPageOrChangeSavedConfiguration() {
        val app=RuntimeEnvironment.getApplication();PolicyStore(app).save(org.json.JSONObject().put("menusEnabled",true))
        val controller=Robolectric.buildActivity(BridgeBootstrapActivity::class.java).create()
        assertTrue(controller.get().isFinishing)
        assertTrue(PolicyStore(app).read().getBoolean("menusEnabled"))
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        controller.destroy()
    }
    @Test fun restartWaitsForThePreviouslyAcceptedConfigurationWrite()=runBlocking {
        val app=RuntimeEnvironment.getApplication();val real=Robolectric.buildContentProvider(PolicyProvider::class.java).create().get()
        val activity=ComponentName("com.tencent.mobileqq","com.tencent.mobileqq.activity.SplashActivity")
        val info=Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(activity).apply{exported=true}
        Shadows.shadowOf(app.packageManager).addIntentFilterForActivity(activity,IntentFilter(Intent.ACTION_MAIN).apply{addCategory(Intent.CATEGORY_LAUNCHER)})
        val provider=ColdProvider(real).apply{ready=true};ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,provider)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        var restartedWithSavedPolicy=false
        provider.beforeCall={method->
            if(method=="save"){entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}
            if(method=="restart")restartedWithSavedPolicy=PolicyStore(app).read().optBoolean("menusEnabled")
        }
        val repo=ManagerRepository(app)
        val write=async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED){repo.update{it.put("menusEnabled",true)}}
        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
        val restart=async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED){repo.restart()}
        assertFalse(restartedWithSavedPolicy);release.countDown();write.await();restart.await()
        assertTrue(restartedWithSavedPolicy)
    }
}
