package io.github.qqmodulemanager

import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DiagnosticLogTest {
    @Test fun errorsRetainTheirRootCauseAndStackWhileCredentialsAndUrlQueriesAreRemoved() {
        val log=DiagnosticLog(RuntimeEnvironment.getApplication());log.clear();log.secret("secret-key-123")
        val failure=java.io.IOException("request https://example.com/api?q=private-token Authorization: Bearer secret-key-123",java.net.UnknownHostException("example.com"))
        log.event("ERROR","translate","failed","request-1",JSONObject().put("nested",JSONObject().put("url","https://example.com/a?key=secret-key-123")),failure)
        val rows=log.read();assertEquals(1,rows.length());val text=rows.toString()
        assertTrue(text.contains("UnknownHostException"));assertTrue(text.contains("DiagnosticLogTest"));assertTrue(text.contains("request-1"))
        assertFalse(text.contains("secret-key-123"));assertFalse(text.contains("private-token"));assertTrue(text.contains("example.com"));assertTrue(rows.getJSONObject(0).getJSONObject("details").has("nested"))
    }
    @Test fun smallCredentialValuesCannotCorruptTheJsonStructure() {
        val log=DiagnosticLog(RuntimeEnvironment.getApplication());log.clear();log.secret("a")
        log.event("INFO","translate","start","r",JSONObject().put("data","a"))
        val row=log.read().getJSONObject(0);assertEquals("[已隐藏]",row.getJSONObject("details").getString("data"));assertTrue(row.has("stage"))
    }
    @Test fun credentialNamespacesAreIsolatedAndStoredKeysStayEncrypted() {
        val context=RuntimeEnvironment.getApplication();val key=javax.crypto.spec.SecretKeySpec(ByteArray(32){it.toByte()},"AES")
        val manager=CredentialVault(context,key);val qq=CredentialVault(context,key,"qqmm-")
        manager.write("provider","manager-secret");qq.write("provider","qq-secret")
        assertEquals("manager-secret",manager.read("provider"));assertEquals("qq-secret",qq.read("provider"))
        assertFalse(context.getSharedPreferences("qqmm-translation-secrets",0).getString("provider","")!!.contains("qq-secret"))
    }
    @Test fun exportedFilesAreReadOnlyAndRejectUnknownUidsAndPathTraversal() {
        val app=RuntimeEnvironment.getApplication();val bundle=DiagnosticExports.create(app,"a diagnostic record")
        val uri=android.net.Uri.parse(bundle.getString("uri"))
        assertEquals("a diagnostic record",DiagnosticExports.file(app,uri).readText())
        ShadowBinder.setCallingUid(54321)
        ShadowBinder.setCallingPid(12345)
        val denied=object:android.content.ContextWrapper(app){override fun checkUriPermission(uri:android.net.Uri,pid:Int,uid:Int,flags:Int)=android.content.pm.PackageManager.PERMISSION_DENIED}
        val granted=object:android.content.ContextWrapper(app){override fun checkUriPermission(uri:android.net.Uri,pid:Int,uid:Int,flags:Int)=android.content.pm.PackageManager.PERMISSION_GRANTED}
        try{
            assertTrue(runCatching{DiagnosticExports.open(denied,uri,"r")}.exceptionOrNull() is SecurityException)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(DiagnosticExports.open(granted,uri,"r")).use{assertEquals("a diagnostic record",it.bufferedReader().readText())}
        }finally{ShadowBinder.reset()}
        assertTrue(runCatching{DiagnosticExports.open(app,uri,"w")}.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching{DiagnosticExports.file(app,android.net.Uri.parse("content://io.github.qqmodulemanager.bridge/diagnostics/../../secret"))}.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun networkFailuresNameTheActualPhaseWithoutBlamingTheManagerApp() {
        assertTrue(networkFailure(java.net.UnknownHostException("host"),"host","dns",false).contains("DNS"))
        assertTrue(networkFailure(java.net.SocketTimeoutException(),"host","upload",false).contains("上传超时"))
        assertTrue(networkFailure(javax.net.ssl.SSLHandshakeException("failed"),"host","tls",false).contains("TLS"))
        assertTrue(networkFailure(javax.net.ssl.SSLHandshakeException("failed").apply{initCause(java.security.cert.CertificateException("invalid"))},"host","tls",false).contains("TLS"))
        assertFalse(networkFailure(java.net.ConnectException(),"host","connect",false).contains("QIII APP"))
    }
    @Test fun largeLogExportsUseAFileDescriptorInsteadOfABinderSizedTextArgument() {
        val app=RuntimeEnvironment.getApplication();val content="日志 record\n".repeat(150000)
        val source=java.io.File.createTempFile("large-test-log-",".txt",app.cacheDir).apply{writeText(content)}
        try{val result=DiagnosticExports.fromDescriptor(app,android.os.ParcelFileDescriptor.open(source,android.os.ParcelFileDescriptor.MODE_READ_ONLY))
            assertEquals(content,DiagnosticExports.file(app,android.net.Uri.parse(result.getString("uri"))).readText())
        }finally{source.delete()}
    }
    @Test fun languageFallbackKeepsChineseDirectionWithoutRequiringManagerInitialization() {
        assertEquals("zh",fallbackLanguage("你好，最近怎么样？"));assertEquals("und",fallbackLanguage("こんにちは、日本語です"));assertEquals("und",fallbackLanguage("Hello there"))
    }
}
