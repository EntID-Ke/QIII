package io.github.qqmodulemanager

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class FeatureServiceTest {
    @After fun reset(){ShadowBinder.reset()}
    @Test fun aForeignAppCannotUseTheTranslationServiceOrReadItsConfiguration() {
        val controller=Robolectric.buildService(FeatureService::class.java).create();val binder=controller.get().onBind(Intent())
        ShadowBinder.setCallingUid(99999)
        val failure=runCatching{FeatureWire.send(binder,"settings",Bundle().apply{putString("requestId","foreign")},Binder())}.exceptionOrNull()
        assertTrue(failure is SecurityException);controller.destroy()
    }
    @Test fun qqCanColdStartTheServiceAndReadPublicConfigurationWithoutOpeningTheManagerUi()=runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val qq=PackageInfo().apply{packageName="com.tencent.mobileqq";applicationInfo=ApplicationInfo().apply{packageName="com.tencent.mobileqq";uid=12345}}
        Shadows.shadowOf(app.packageManager).installPackage(qq)
        val controller=Robolectric.buildService(FeatureService::class.java).create();val binder=controller.get().onBind(Intent())
        val result=CompletableDeferred<JSONObject>()
        val callback=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface(FeatureWire.CALLBACK);val state=data.readString();val value=JSONObject(data.readString()!!)
            if(state=="done")result.complete(value) else if(state=="error")result.completeExceptionally(IllegalStateException(value.toString()))
            return true
        }}
        ShadowBinder.setCallingUid(12345)
        FeatureWire.send(binder,"settings",Bundle().apply{putString("requestId","cold")},callback)
        val settings=withTimeout(5000){result.await()}.getJSONObject("settings")
        assertEquals("microsoft",settings.getString("provider"));assertTrue(settings.getBoolean("chineseToEnglish"));assertFalse(settings.toString().contains("apiKey"))
        assertNull(Shadows.shadowOf(app).nextStartedActivity);controller.destroy();Unit
    }
    @Test fun settingsAndClearingStayResponsiveWhenAllNetworkSlotsAreOccupied()=runBlocking {
        val controller=Robolectric.buildService(FeatureService::class.java).create();val service=controller.get()
        val permits=(service.onBind(Intent()) as FeatureEndpoint).concurrency
        repeat(4){permits.acquire()}
        try {
            for(operation in listOf("settings","clear")) {
                val result=CompletableDeferred<JSONObject>()
                val callback=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                    data.enforceInterface(FeatureWire.CALLBACK);val state=data.readString();val value=JSONObject(data.readString()!!)
                    if(state=="done")result.complete(value) else if(state=="error")result.completeExceptionally(IllegalStateException(value.toString()))
                    return true
                }}
                FeatureWire.send(service.onBind(Intent()),operation,Bundle().apply{putString("requestId",operation)},callback)
                assertNotNull(withTimeout(2000){result.await()})
            }
        }finally{repeat(4){permits.release()};controller.destroy()}
        Unit
    }
}
