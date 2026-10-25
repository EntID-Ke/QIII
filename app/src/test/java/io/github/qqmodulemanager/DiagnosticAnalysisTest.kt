package io.github.qqmodulemanager

import android.os.Bundle
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DiagnosticAnalysisTest {
    @After fun reset(){resetHostTestState()}
    private fun row(id:String,level:String="INFO",error:String="")=JSONObject().put("requestId",id).put("level",level).put("time",123).put("stage",id).put("error",error)
    @Test fun summariesAreBoundedRedactedAndKeepErrorsAheadOfRoutineMessages() {
        val log=DiagnosticLog.get(RuntimeEnvironment.getApplication());log.secret("private-credential")
        val error=row("critical","ERROR","Authorization: Bearer private-credential https://example.com/path?key=private")
        val rows=(1..400).map{row("routine-$it",error="x".repeat(2000))}+error
        val text=diagnosticAnalysisInput(rows,null,log)
        assertTrue(text.length<32000);assertTrue(text.contains("critical"));assertTrue(text.indexOf("critical")<text.indexOf("routine-"))
        assertFalse(text.contains("private-credential"));assertFalse(text.contains("key=private"));assertTrue(text.contains("省略"));assertTrue(text.contains("错误 1"))
    }
    @Test fun aSingleEntryIncludesItsRequestContextAndExcludesUnrelatedRequests() {
        val error=row("selected","ERROR","failure");val context=row("selected").put("stage","network-context")
        val log=DiagnosticLog.get(RuntimeEnvironment.getApplication())
        val input=diagnosticAnalysisInput(listOf(error,context,row("unrelated")),error,log)
        assertTrue(input.contains("network-context"));assertFalse(input.contains("unrelated"))
        val anonymous=row("","ERROR","one-entry")
        assertFalse(diagnosticAnalysisInput(listOf(anonymous,row("").put("stage","not-related")),anonymous,log).contains("not-related"))
    }
    @Test fun evenAnOversizedSelectedEntryIsIncludedWithAnExplicitTruncationMarker() {
        val selected=row("chosen","ERROR","a".repeat(60000))
        val text=diagnosticAnalysisInput(listOf(selected),selected,DiagnosticLog.get(RuntimeEnvironment.getApplication()))
        assertTrue(text.length<32000);assertTrue(text.contains("chosen"));assertTrue(text.contains("已截断"))
    }
    @Test fun analysisUsesTheSavedAiKeyModelAndEffortWithoutUsingTheTranslationPromptOrCache()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val store=TranslationStore(context,CredentialVault(context,SecretKeySpec(ByteArray(32){4},"AES")))
        MockWebServer().use{server->
            store.saveProvider(JSONObject().put("id","ai").put("name","我的 AI").put("protocol","responses").put("url",server.url("/v1").toString()).put("model","gpt-5-custom").put("reasoningMode","effort").put("reasoning","max").put("keyChanged",true).put("key","saved-secret"))
            server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"结论：需要核对证据"}]}]}"""))
            val runtime=FeatureRuntime(context,store)
            try {
                val response=runtime.perform("analyzeDiagnostics",Bundle().apply{putString("document",JSONObject().put("provider","ai").put("text","ERROR Authorization: Bearer saved-secret").toString())})
                assertEquals("结论：需要核对证据",response.getString("text"));assertEquals("我的 AI",response.getString("providerName"))
                val request=server.takeRequest();val body=JSONObject(request.body.readUtf8())
                assertEquals("Bearer saved-secret",request.getHeader("Authorization"));assertEquals("gpt-5-custom",body.getString("model"));assertEquals("max",body.getJSONObject("reasoning").getString("effort"))
                assertEquals(DIAGNOSTIC_PROMPT,body.getString("instructions"));assertFalse(body.getString("input").contains("saved-secret"));assertEquals(0,store.accounts().length())
                assertFalse(DiagnosticLog.get(context).read().toString().contains("saved-secret"))
            }finally{runtime.close()}
        }
    }
    @Test fun freeTranslationProvidersCannotBeMisusedForAiAnalysis()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val runtime=FeatureRuntime(context,TranslationStore(context))
        try {
            val error=runCatching{runtime.perform("analyzeDiagnostics",Bundle().apply{putString("document",JSONObject().put("provider","microsoft").put("text","error").toString())})}.exceptionOrNull()
            assertNotNull(error);assertTrue(error!!.message!!.contains("AI 服务"))
        }finally{runtime.close()}
    }
}
