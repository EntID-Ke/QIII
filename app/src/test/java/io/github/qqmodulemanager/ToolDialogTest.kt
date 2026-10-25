package io.github.qqmodulemanager

import android.content.ComponentName
import android.content.Context
import android.content.ClipboardManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
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
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolDialogTest {
    @get:Rule(order=0) val activityManifest=object:ExternalResource(){override fun before(){val app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app,ComponentActivity::class.java))}}
    @get:Rule(order=1) val compose=createComposeRule()
    @Test @Config(qualifiers="w400dp-h800dp-night-mdpi") fun errorDetailsCanBeExpandedAndCopiedInTheManagerStyle() {
        compose.setContent{val context=LocalContext.current;Text("host");LaunchedEffect(Unit){ToolDialog.error(context,"识图失败",java.io.IOException("DNS 解析失败 · lens.google.com",java.net.UnknownHostException("lens.google.com")))}}
        compose.onNodeWithText("识图失败").assertExists();compose.onNodeWithText("查看详情").performClick()
        compose.onNodeWithText("复制错误").performClick()
        val clipboard=org.robolectric.shadows.ShadowDialog.getLatestDialog().context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().contains("UnknownHostException"))
        val directory=java.io.File("build/reports/ui").apply{mkdirs()}
        compose.runOnIdle{
            val decor=org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap=android.graphics.Bitmap.createBitmap(decor.width,decor.height,android.graphics.Bitmap.Config.ARGB_8888)
            decor.draw(android.graphics.Canvas(bitmap));java.io.File(directory,"tool-error.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        compose.onNodeWithText("关闭").performClick();compose.waitForIdle()
    }
    @Test fun imagePreparationDistinguishesBrowserHandoffFromUploadSuccess() {
        val state=mutableStateOf(JSONObject().put("stage","prepared").put("bytes",50000).put("sourcePreserved",true).put("message","正在准备所选图片…"))
        compose.setContent{ManagerTheme{androidx.compose.foundation.layout.Column{LensProgressContent(state.value,SystemClock.elapsedRealtime(),{})}}}
        compose.onNodeWithText("48 KB · 保留原文件").assertExists()
        compose.runOnIdle{state.value=state.value.copy().put("stage","browser").put("message","正在交给浏览器；Google 上传与结果页由同一浏览器完成…")}
        compose.onNodeWithText("正在交给浏览器；Google 上传与结果页由同一浏览器完成…").assertExists()
        compose.onNodeWithText("上传完成",substring=true).assertDoesNotExist()
    }
}
