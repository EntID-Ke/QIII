package io.github.qqmodulemanager

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.qqmodulemanager.bridge.BridgeClient
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionUiTest {
    @get:Rule(order=0) val activityManifest=object:ExternalResource() {
        override fun before(){val app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app,ComponentActivity::class.java))}
    }
    @get:Rule(order=1) val compose=createComposeRule()
    @After fun cleanup(){ShadowBinder.reset()}
    @Test fun theMenuEditorShowsManagerActionsWithoutLiveCaptureOrPriorityTakeover() {
        val app=RuntimeEnvironment.getApplication()
        val provider=Robolectric.buildContentProvider(PolicyProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,provider)
        compose.setContent{ManagerUi(app).Content()}
        compose.onNodeWithText("消息长按菜单").performScrollTo().performClick()
        compose.onNodeWithText("图片",useUnmergedTree=true).performClick()
        compose.waitUntil(5000){compose.onAllNodes(hasText("编辑当前菜单") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("编辑当前菜单").performClick()
        compose.onNodeWithText("翻译").assertExists();compose.onNodeWithText("一键翻译").assertExists();compose.onNodeWithText("Google 识图").assertExists()
        val choices=compose.onAllNodes(isToggleable() and hasAnyAncestor(isDialog()))
        choices.assertCountEquals(3);choices[2].performClick()
        compose.waitUntil(5000){PolicyStore(app).read().optJSONObject("menus")?.optJSONObject("group/image")?.optJSONArray("hidden")?.toString()?.contains("manager:lens")==true}
        org.junit.Assert.assertFalse(PolicyStore(app).read().optBoolean("menusEnabled"))
    }
    @Test fun homeShowsMessageToolsAndMovesProviderPrioritiesIntoModuleManagement() {
        val app=RuntimeEnvironment.getApplication()
        val provider=Robolectric.buildContentProvider(PolicyProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,provider)
        compose.setContent{ManagerUi(app).Content()}
        compose.onNodeWithText("消息翻译").assertExists()
        compose.onNodeWithText("搜索功能").assertDoesNotExist()
        compose.onNodeWithText("模块管理").performScrollTo().performClick()
        compose.onNodeWithText("搜索功能").assertExists()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("消息翻译").assertExists()
    }
    @Test fun nativeLensSettingsAreAvailableWithoutModulePriorityTakeover() {
        val app=RuntimeEnvironment.getApplication()
        val provider=Robolectric.buildContentProvider(PolicyProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,provider)
        compose.setContent{ManagerUi(app).Content()}
        compose.onNodeWithText("Google 识图").performScrollTo().performClick()
        compose.onNodeWithText("长按普通图片，交给浏览器上传并打开 Google Lens 结果。").assertExists()
        compose.onNode(isToggleable()).performClick()
        compose.waitUntil(5000){!PolicyStore(app).read().optBoolean("lensEnabled",true)}
        assert(!PolicyStore(app).read().optBoolean("menusEnabled"))
    }
    @Test fun aBackgroundServiceDoesNotMasqueradeAsTheChatProcess() {
        val app=RuntimeEnvironment.getApplication();val store=PolicyStore(app)
        val report=JSONObject().put("process","com.tencent.mobileqq:MSF").put("seenAt",System.currentTimeMillis())
        store.runtime(JSONObject().put("101",report))
        val provider=Robolectric.buildContentProvider(PolicyProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,provider)
        val hosts=PolicyProvider::class.java.getDeclaredField("hosts").apply{isAccessible=true}.get(provider) as java.util.concurrent.ConcurrentHashMap<Int,android.os.IBinder>
        hosts[101]=Binder()
        compose.setContent{ManagerUi(app).Content()}
        compose.waitUntil(5000){compose.onAllNodesWithText("等待 QQ 主进程恢复").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("QQ 已连接").assertDoesNotExist()
    }
    @Test fun anOldHeartbeatWithALiveBinderStillShowsConnectedEvenBesideANewerDeadProcess() {
        val app=RuntimeEnvironment.getApplication()
        val info=PackageInfo().apply {
            packageName="com.tencent.mobileqq"
            applicationInfo=ApplicationInfo().apply{packageName="com.tencent.mobileqq";uid=android.os.Process.myUid()+50}
        }
        Shadows.shadowOf(app.packageManager).installPackage(info)
        val provider=Robolectric.buildContentProvider(PolicyProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal(BridgeClient.AUTHORITY,provider)
        ShadowBinder.setCallingUid(info.applicationInfo!!.uid)
        for(pid in listOf(101,202)) {
            ShadowBinder.setCallingPid(pid)
            val report=Bundle().apply {
                putBinder("host",if(pid==101)Binder() else object:Binder(){override fun isBinderAlive()=false})
                putString("report","{\"process\":\"com.tencent.mobileqq\"}")
            }
            provider.call("report",null,report)
        }
        val store=PolicyStore(app)
        val runtime=store.runtime();runtime.getJSONObject("101").put("seenAt",1L);store.runtime(runtime)
        ShadowBinder.reset()
        compose.setContent{ManagerUi(app).Content()}
        compose.waitUntil(5000){compose.onAllNodesWithText("QQ 已连接").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("QQ 已连接").assertExists()
        compose.onNodeWithText("重启 QQ").assertExists()
        compose.onNodeWithText("等待 QQ 连接").assertDoesNotExist()
    }
}
