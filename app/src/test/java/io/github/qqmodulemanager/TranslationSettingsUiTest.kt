package io.github.qqmodulemanager

import android.content.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationSettingsUiTest {
    @get:Rule(order=0) val manifest=object:ExternalResource(){override fun before(){val app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app,ComponentActivity::class.java))}}
    @get:Rule(order=1) val compose=createComposeRule()
    @Test fun aProviderCanBeConfiguredAndSavedThroughTheSameBackendUsedInsideQq() {
        val app=RuntimeEnvironment.getApplication();val service=Robolectric.buildService(FeatureService::class.java).create()
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun bindService(intent:Intent,connection:ServiceConnection,flags:Int):Boolean{connection.onServiceConnected(intent.component!!,service.get().onBind(intent));return true}
            override fun unbindService(connection:ServiceConnection){}
        }
        compose.setContent{NativeFeatureSettings(context,Modifier,"translate",JSONObject()) {}}
        compose.waitUntil(5000){compose.onAllNodesWithText("默认翻译服务").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("添加 AI 服务").performScrollTo().performClick()
        compose.onNodeWithText("服务名称").performTextInput("测试中转服务")
        compose.onNodeWithText("模型 ID，可手动填写").performScrollTo().performTextInput("custom-model")
        compose.onNodeWithText("按模型识别").performScrollTo().performClick()
        compose.onNodeWithText("OpenAI effort").performClick()
        compose.onNodeWithText("模型默认").performScrollTo().performClick()
        compose.onNodeWithText("xhigh · 更高").performClick()
        compose.onNodeWithText("xhigh · 更高").performScrollTo().performClick()
        compose.onNodeWithText("max · 最高").performClick()
        compose.onNodeWithText("保存服务").performScrollTo().performClick()
        compose.waitUntil(5000){compose.onAllNodesWithText("服务已保存").fetchSemanticsNodes().isNotEmpty()}
        val store=TranslationStore(app)
        val profile=store.settings().getJSONArray("providers").getJSONObject(0)
        org.junit.Assert.assertEquals("测试中转服务",profile.getString("name"));org.junit.Assert.assertEquals("custom-model",profile.getString("model"));org.junit.Assert.assertEquals("max",profile.getString("reasoning"));org.junit.Assert.assertEquals("effort",profile.getString("reasoningMode"));org.junit.Assert.assertFalse(profile.has("key"))
        store.saveSettings(store.settings().put("target","ja"))
        compose.onNodeWithText("普通消息目标语言").performScrollTo()
        compose.waitUntil(5000){compose.onAllNodesWithText("日语").fetchSemanticsNodes().isNotEmpty()}
        store.close();service.destroy()
    }
}
