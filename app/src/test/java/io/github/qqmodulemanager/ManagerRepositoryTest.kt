package io.github.qqmodulemanager

import android.content.ContextWrapper
import io.github.qqmodulemanager.bridge.BridgeClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ManagerRepositoryTest {
    @Test fun launcherAndEmbeddedPagesShareEditsWithoutAccessingHostPrivatePreferences()=runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val provider=Robolectric.buildContentProvider(PolicyProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,provider)
        val qq=object:ContextWrapper(app) {
            override fun getPackageName()="com.tencent.mobileqq"
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences=throw AssertionError("The embedded page must not store policy in QQ")
        }
        val embedded=ManagerRepository(qq);val launcher=ManagerRepository(app)
        embedded.update{it.put("menusEnabled",true)}
        assertTrue(launcher.read().policy.getBoolean("menusEnabled"))
        launcher.update{it.put("menusEnabled",false)}
        assertFalse(embedded.read().policy.getBoolean("menusEnabled"))
        assertEquals(2L,embedded.read().policy.getLong("revision"))
    }
}
