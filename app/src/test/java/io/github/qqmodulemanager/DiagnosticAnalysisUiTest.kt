package io.github.qqmodulemanager

import android.content.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
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
class DiagnosticAnalysisUiTest {
    @get:Rule(order=0) val manifest=object:ExternalResource(){override fun before(){val app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app,ComponentActivity::class.java));resetHostTestState()}}
    @get:Rule(order=1) val compose=createComposeRule()
    @After fun reset(){resetHostTestState()}
    @Test fun overviewAndSingleEntryCanAnalyzeAndCopyWithTheSavedTranslationAi() {
        val app=RuntimeEnvironment.getApplication();val service=Robolectric.buildService(FeatureService::class.java).create()
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun bindService(intent:Intent,connection:ServiceConnection,flags:Int):Boolean{connection.onServiceConnected(intent.component!!,service.get().onBind(intent));return true}
            override fun unbindService(connection:ServiceConnection){}
        }
        MockWebServer().use{server->
            val store=TranslationStore(app)
            store.saveProvider(JSONObject().put("id","analysis-ai").put("name","我的翻译 AI").put("protocol","chat").put("url",server.url("/v1").toString()).put("model","gpt-5-test").put("reasoning","xhigh"));store.close()
            DiagnosticLog.get(app).event("ERROR","test","failed","test-request",error=java.io.IOException("test failure"))
            compose.setContent{ManagerTheme{DiagnosticsUi(context,Modifier)}}
            compose.waitUntil(5000){compose.onAllNodesWithText("test · failed").fetchSemanticsNodes().isNotEmpty()}
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"总览分析结果"},"finish_reason":"stop"}]}"""))
            compose.onNodeWithText("AI 总结").performClick()
            compose.waitUntil(5000){compose.onAllNodesWithText("我的翻译 AI").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("开始分析").performClick()
            compose.waitUntil(5000){compose.onAllNodesWithText("总览分析结果").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("复制分析").performClick()
            val clipboard=app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertTrue(clipboard.primaryClip!!.getItemAt(0).text.contains("总览分析结果"))
            val overview=JSONObject(server.takeRequest().body.readUtf8());assertEquals("xhigh",overview.getString("reasoning_effort"));assertTrue(overview.getJSONArray("messages").getJSONObject(1).getString("content").contains("日志总览"))
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithText("test · failed").performClick()
            compose.onNodeWithText("AI 分析此条").performClick()
            compose.onNodeWithText("范围：此条日志及同一请求编号的上下文。").assertExists()
            compose.waitUntil(5000){compose.onAllNodesWithText("我的翻译 AI").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("开始分析").assertIsEnabled()
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"单条分析结果"},"finish_reason":"stop"}]}"""))
            compose.onNodeWithText("开始分析").performClick()
            compose.waitUntil(5000){compose.onAllNodesWithText("单条分析结果").fetchSemanticsNodes().isNotEmpty()}
            val single=JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(single.contains("test-request"));assertTrue(single.contains("单条日志"))
            compose.onNodeWithText("关闭").performClick()
        }
        service.destroy()
    }
    @Test fun logsWithoutAnAiConfigurationExplainWhereToConfigureOne() {
        val app=RuntimeEnvironment.getApplication();val service=Robolectric.buildService(FeatureService::class.java).create()
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun bindService(intent:Intent,connection:ServiceConnection,flags:Int):Boolean{connection.onServiceConnected(intent.component!!,service.get().onBind(intent));return true}
            override fun unbindService(connection:ServiceConnection){}
        }
        compose.setContent{ManagerTheme{DiagnosticAnalysisDialog(context,FeatureConnection(context),listOf(JSONObject()),null){}}}
        compose.waitUntil(5000){compose.onAllNodesWithText("请先在消息翻译设置中添加 AI 服务。",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("开始分析").assertIsNotEnabled()
        service.destroy()
    }
}
