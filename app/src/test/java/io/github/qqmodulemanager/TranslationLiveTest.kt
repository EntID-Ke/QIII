package io.github.qqmodulemanager

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Opt in explicitly. Uses fixed text and a public Wikimedia sample, never user messages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationLiveTest {
    @Test fun microsoftAndGoogleTranslateUsingTheProductionAdapters()=runBlocking {
        assumeTrue(System.getenv("QIII_LIVE_TESTS")=="1")
        val engine=TranslationEngine();val evidence=JSONObject()
        for(service in listOf("microsoft","google")) {
            val profile=JSONObject().put("protocol",service)
            val chinese=engine.translate(profile,"","Hello, how are you?\nThis is a test.","zh-Hans",TRANSLATION_PROMPT)
            val english=engine.translate(profile,"","你好，最近怎么样？","en",TRANSLATION_PROMPT)
            assertTrue(chinese.contains('\n'));assertTrue(chinese.any{it.code in 0x4e00..0x9fff});assertTrue(english.any{it in 'A'..'Z' || it in 'a'..'z'})
            evidence.put(service,JSONObject().put("toChinese",chinese).put("toEnglish",english))
        }
        File("build/reports").mkdirs();File("build/reports/live-translation.json").writeText(evidence.toString(2))
    }
    @Test fun googleLensPreparesTheExactPublicSampleForTheBrowser()=runBlocking {
        assumeTrue(System.getenv("QIII_LIVE_TESTS")=="1")
        val engine=TranslationEngine();val context=RuntimeEnvironment.getApplication();val file=File.createTempFile("public-lens-sample-",".jpg",context.cacheDir)
        try {
            engine.request(Request.Builder().url("https://upload.wikimedia.org/wikipedia/commons/3/3a/Cat03.jpg").header("User-Agent","QIII integration test (public sample)").build()){response->file.outputStream().use{response.body!!.byteStream().copyTo(it)}}
            val progress=ArrayList<JSONObject>()
            val url=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{LensSearch(context).search(it){value->progress.add(value)}}
            assertTrue(url.startsWith("http://127.0.0.1:"))
            val connection=java.net.URL(url).openConnection();assertEquals("no-referrer",connection.getHeaderField("Referrer-Policy"))
            val html=connection.getInputStream().bufferedReader().use{it.readText()}
            val encoded=Regex("src=\"data:image/jpeg;base64,([^\"]+)\"").find(html)!!.groupValues[1]
            assertArrayEquals(file.readBytes(),android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP))
            File("build/reports").mkdirs();File("build/reports/browser-lens-fixture.html").writeText(html)
            File("build/reports/browser-lens-source.jpg").writeBytes(file.readBytes())
            File("build/reports/live-lens.json").writeText(JSONObject().put("sample","Wikimedia Commons Cat03.jpg").put("sourceBytes",file.length()).put("sourcePreserved",true)
                .put("sha256",java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}).put("referrerPolicy","no-referrer").put("mode","browser-native-upload").toString(2))
        }finally{file.delete()}
    }
}
