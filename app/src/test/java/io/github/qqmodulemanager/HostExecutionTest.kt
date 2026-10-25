package io.github.qqmodulemanager

import android.content.*
import android.os.Bundle
import io.github.qqmodulemanager.bridge.BridgeClient
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HostExecutionTest {
    @org.junit.Before fun prepareHost(){resetHostTestState()}
    @org.junit.After fun closeHost(){resetHostTestState()}
    class ManagerProvider:android.content.ContentProvider() {
        val unavailable=AtomicBoolean(false)
        override fun onCreate()=true
        override fun call(method:String,arg:String?,args:Bundle?):Bundle {
            check(!unavailable.get()){"Manager process is unavailable"}
            return FeatureConfigBridge.call(context!!,method,args)
        }
        override fun query(uri:android.net.Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,order:String?)=null
        override fun getType(uri:android.net.Uri)=null
        override fun insert(uri:android.net.Uri,values:ContentValues?)=null
        override fun delete(uri:android.net.Uri,selection:String?,args:Array<out String>?)=0
        override fun update(uri:android.net.Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
    }
    @Test fun syncedQqKeepsTranslatingAndSavingResultsWhenTheManagerIsUnavailable()=runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val provider=Robolectric.buildContentProvider(ManagerProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,provider)
        MockWebServer().use{server->
            TranslationStore(app).use{store->
                store.saveProvider(JSONObject().put("id","test-ai").put("name","测试翻译").put("protocol","chat").put("url",server.url("/v1").toString()).put("model","test-model"))
                store.saveSettings(store.settings().put("provider","test-ai").put("chineseToEnglish",false))
            }
            var bindings=0
            val qq=object:ContextWrapper(app) {
                override fun getPackageName()="com.tencent.mobileqq"
                override fun getApplicationContext():Context=this
                override fun bindService(intent:Intent,connection:ServiceConnection,flags:Int):Boolean{bindings++;throw AssertionError("A chat request must not bind the manager")}
                override fun startActivity(intent:Intent){throw AssertionError("A chat request must not open the manager")}
            }
            val client=FeatureConnection(qq)
            assertEquals("test-ai",withTimeout(5000){client.call("settings")}.getJSONObject("settings").getString("provider"))
            provider.unavailable.set(true)
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val args=Bundle().apply{putString("account","test-account");putString("message","test-message");putString("source",digest("hello"));putString("text","hello")}
            val translated=withTimeout(5000){client.call("translate",args=args)}
            assertEquals("你好",translated.getString("text"));assertEquals("test-ai",translated.getString("provider"))
            assertEquals("你好",client.call("lookup",args=args).getJSONObject("result").getString("text"));assertEquals(0,bindings)
            withTimeout(2000){client.call("clear",args=Bundle().apply{putString("account","test-account");putString("message","test-message")})}
            assertTrue(client.call("lookup",args=args).isNull("result"))
            assertTrue(app.getSharedPreferences("qqmm-feature-sync",0).getString("pendingClears","[]")!!.contains("test-message"))
            client.close()
        }
    }
}
