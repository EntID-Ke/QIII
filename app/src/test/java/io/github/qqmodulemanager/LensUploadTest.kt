package io.github.qqmodulemanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LensUploadTest {
    @get:Rule val folder=TemporaryFolder()
    private fun handoff(url:String):String=URL(url).openConnection().apply{connectTimeout=2000;readTimeout=2000}.let{connection->
        assertEquals("no-referrer",connection.getHeaderField("Referrer-Policy"))
        connection.getInputStream().bufferedReader().use{it.readText()}
    }
    private fun imageBytes(html:String):ByteArray=android.util.Base64.decode(Regex("src=\"data:image/[^;]+;base64,([^\"]+)\"").find(html)!!.groupValues[1],android.util.Base64.NO_WRAP)
    @Test fun largeImagesAreCompressedAndTheBrowserReceivesTheActualPreparedBytes()=runBlocking {
        val bitmap=Bitmap.createBitmap(2400,1200,Bitmap.Config.ARGB_8888)
        val random=java.util.Random(42);val pixels=IntArray(bitmap.width*bitmap.height){Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256))}
        bitmap.setPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val original=folder.newFile("source.png").also{try{it.outputStream().use{out->assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out))}}finally{bitmap.recycle()}}
        val events=ArrayList<JSONObject>()
        val url=ParcelFileDescriptor.open(original,ParcelFileDescriptor.MODE_READ_ONLY).use{LensSearch(RuntimeEnvironment.getApplication()).search(it){event->events.add(event)}}
        val html=handoff(url);val sent=imageBytes(html);val uploaded=BitmapFactory.decodeByteArray(sent,0,sent.size)
        try{assertEquals(1600,uploaded.width);assertEquals(800,uploaded.height)}finally{uploaded.recycle()}
        val prepared=events.first{it.optString("stage")=="prepared"};assertEquals(sent.size,prepared.getInt("bytes"));assertFalse(prepared.getBoolean("sourcePreserved"));assertTrue(sent.size<original.length())
        assertEquals("browser",events.last().getString("stage"));assertFalse(html.contains("udm="))
    }
    @Test fun ordinaryJpegPngAndWebpAreNotReencodedBeforeBrowserUpload()=runBlocking {
        for((extension,format) in listOf("jpg" to Bitmap.CompressFormat.JPEG,"png" to Bitmap.CompressFormat.PNG,"webp" to Bitmap.CompressFormat.WEBP_LOSSLESS)) {
            val source=folder.newFile("source.$extension");val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.CYAN)}
            try{source.outputStream().use{bitmap.compress(format,90,it)}}finally{bitmap.recycle()}
            val url=BrowserLensUpload.prepare(RuntimeEnvironment.getApplication(),source.path)
            val html=handoff(url);assertArrayEquals(source.readBytes(),imageBytes(html));assertTrue(html.contains("'image.$extension'"))
            if(extension=="jpg") {
                java.io.File("build/reports").mkdirs();java.io.File("build/reports/browser-lens-fixture.html").writeText(html)
                java.io.File("build/reports/browser-lens-source.jpg").writeBytes(source.readBytes())
            }
        }
    }
    @Test fun imageOrientationAndContentArePreservedInThePreparedUpload() {
        val source=folder.newFile("rotated.jpg");val output=folder.newFile("prepared.jpg")
        val bitmap=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(Color.BLUE);canvas.drawRect(0f,0f,32f,32f,android.graphics.Paint().apply{color=Color.RED})
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)};bitmap.recycle()
        android.media.ExifInterface(source.path).apply{setAttribute(android.media.ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}
        LensSearch(RuntimeEnvironment.getApplication()).prepare(source,output){}
        val uploaded=BitmapFactory.decodeFile(output.path)
        try{assertEquals(32,uploaded.width);assertEquals(64,uploaded.height);assertTrue(Color.red(uploaded.getPixel(16,8))>200);assertTrue(Color.blue(uploaded.getPixel(16,56))>200)}finally{uploaded.recycle()}
    }
    @Test fun invalidImageDataNeverOpensAnEmptyGoogleSearch()=runBlocking {
        val source=folder.newFile("not-image").apply{writeText("not an image")}
        assertNotNull(runCatching{BrowserLensUpload.prepare(RuntimeEnvironment.getApplication(),source.path)}.exceptionOrNull())
    }
    @Test fun theBrowserPageRejectsUnexpectedMimeTypesRatherThanInterpolatingThemIntoHtml() {
        assertNotNull(runCatching{BrowserLensUpload.serve(byteArrayOf(1),"image/svg+xml")}.exceptionOrNull())
        assertNotNull(runCatching{BrowserLensUpload.serve(byteArrayOf(1),"image/jpeg'bad")}.exceptionOrNull())
    }
    @Test fun theHandoffEmbedsEachImageOnlyOnceInsteadOfDuplicatingItInJavaScript() {
        val bytes=ByteArray(128000){(it%251).toByte()};val encoded=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
        val html=handoff(BrowserLensUpload.serve(bytes))
        assertEquals(1,Regex(Regex.escape(encoded)).findAll(html).count())
        assertTrue(html.contains("id=\"elapsed\""));assertTrue(html.contains("id=\"stop\""))
    }
    @Test fun parallelHandoffsUseSeparateFreePortsAndNeverSwapTheirImages() {
        val first="first-image".toByteArray();val second="second-image".toByteArray()
        val one=BrowserLensUpload.serve(first);val two=BrowserLensUpload.serve(second)
        assertNotEquals(URL(one).port,URL(two).port)
        assertArrayEquals(second,imageBytes(handoff(two)));assertArrayEquals(first,imageBytes(handoff(one)))
    }
    @Test fun anAbortedProbeAndAWrongPathDoNotConsumeTheOneUseImage() {
        val bytes="selected-image".toByteArray();val url=URL(BrowserLensUpload.serve(bytes))
        java.net.Socket(url.host,url.port).use{it.getOutputStream().write("GET /probe HTTP/1.1\r\n".toByteArray())}
        val wrong=URL("http://${url.host}:${url.port}/wrong-path").openConnection() as java.net.HttpURLConnection
        try{assertEquals(404,wrong.responseCode);wrong.errorStream?.use{it.readBytes()}}finally{wrong.disconnect()}
        assertArrayEquals(bytes,imageBytes(handoff(url.toString())))
    }
    @Test fun browserHandoffOnlyServesTheChosenBytesOnceAtARandomLoopbackPath() {
        val chosen="the-selected-image".toByteArray();val url=BrowserLensUpload.serve(chosen)
        assertEquals("127.0.0.1",URL(url).host)
        val html=handoff(url)
        assertArrayEquals(chosen,imageBytes(html));assertTrue(html.contains("new File([data],'image.jpg'"));assertTrue(html.contains("https://lens.google.com/v3/upload"));assertTrue(html.contains("name=\"referrer\" content=\"no-referrer\""))
        assertNotNull(runCatching{URL(url).openConnection().apply{connectTimeout=1000;readTimeout=1000}.getInputStream().close()}.exceptionOrNull())
    }
}
