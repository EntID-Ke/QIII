package io.github.qqmodulemanager

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class FeatureConnectionTest {
    @org.junit.Before fun prepareHost(){resetHostTestState()}
    @org.junit.After fun closeHost(){resetHostTestState()}
    @Test fun theQqClientExecutesLocallyWithoutBindingOrLaunchingTheManager()=runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val provider=Robolectric.buildContentProvider(PolicyProvider::class.java).create().get()
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(io.github.qqmodulemanager.bridge.BridgeClient.AUTHORITY,provider)
        var bound=0;var unbound=0
        val qq=object:ContextWrapper(app) {
            override fun getPackageName()="com.tencent.mobileqq"
            override fun getApplicationContext():Context=this
            override fun bindService(intent:Intent,connection:ServiceConnection,flags:Int):Boolean {
                assertEquals(ComponentName(FeatureWire.PACKAGE,"${FeatureWire.PACKAGE}.FeatureService"),intent.component)
                bound++;throw AssertionError("QQ must not bind the manager service")
            }
            override fun unbindService(connection:ServiceConnection){unbound++}
            override fun startActivity(intent:Intent){throw AssertionError("The launcher must not be opened")}
        }
        val client=FeatureConnection(qq)
        assertEquals("microsoft",client.call("settings").getJSONObject("settings").getString("provider"))
        assertEquals(0,bound);client.close();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,unbound);Unit
    }
    @Test fun aDeadBackendFailsPendingRequestsAndAllowsTheNextRequestToReconnect()=runBlocking {
        val app=RuntimeEnvironment.getApplication();val entered=CountDownLatch(1);var connection:ServiceConnection?=null;var bindings=0
        val remote=object:Binder(){override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {data.enforceInterface(FeatureWire.DESCRIPTOR);val op=data.readString();data.readBundle();data.readStrongBinder();if(op!="cancel")entered.countDown();reply?.writeNoException();return true}}
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun bindService(intent:Intent,conn:ServiceConnection,flags:Int):Boolean{bindings++;connection=conn;conn.onServiceConnected(intent.component!!,remote);return true}
            override fun unbindService(conn:ServiceConnection){}
        }
        val client=FeatureConnection(context)
        val first=async(start=CoroutineStart.UNDISPATCHED){runCatching{client.call("settings")}}
        withTimeout(3000){while(entered.count>0)delay(5)}
        connection!!.onServiceDisconnected(ComponentName(FeatureWire.PACKAGE,"${FeatureWire.PACKAGE}.FeatureService"))
        assertTrue(withTimeout(3000){first.await()}.isFailure)
        val second=async(start=CoroutineStart.UNDISPATCHED){runCatching{client.call("settings")}}
        withTimeout(3000){while(bindings<2)delay(5)}
        client.close();Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(withTimeout(3000){second.await()}.isFailure)
    }
}
