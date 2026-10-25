package io.github.qqmodulemanager

import android.app.Activity
import android.app.Application
import android.content.*
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject

/** All QQ tool windows use the same Material theme as the manager. */
internal class ToolDialog private constructor(private val host:Activity,private val title:String,private val dismissed:()->Unit,
    private val content:@Composable (()->Unit)->Unit):ComponentDialog(EmbeddedManager.themedContext(host)) {
    private var notified=false
    private val activityCallbacks=object:Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity:Activity){if(activity===host)dismiss()}
        override fun onActivityCreated(activity:Activity,state:Bundle?){}
        override fun onActivityStarted(activity:Activity){}
        override fun onActivityResumed(activity:Activity){}
        override fun onActivityPaused(activity:Activity){}
        override fun onActivityStopped(activity:Activity){}
        override fun onActivitySaveInstanceState(activity:Activity,state:Bundle){}
    }
    override fun onCreate(state:Bundle?) {
        setOwnerActivity(host);requestWindowFeature(Window.FEATURE_NO_TITLE);super.onCreate(state)
        setContentView(ComposeView(context).apply{
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent{ManagerTheme {
                Surface(shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().heightIn(max=(context.resources.configuration.screenHeightDp*.85f).dp).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(title,style=MaterialTheme.typography.headlineSmall)
                        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){content(::dismiss)}
                    }
                }
            }}
        })
        window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        host.application.registerActivityLifecycleCallbacks(activityCallbacks)
    }
    override fun onStart(){super.onStart();val density=context.resources.displayMetrics.density;window!!.setLayout(minOf((560*density).toInt(),context.resources.displayMetrics.widthPixels-(32*density).toInt()),ViewGroup.LayoutParams.WRAP_CONTENT)}
    override fun dismiss(){super.dismiss();host.application.unregisterActivityLifecycleCallbacks(activityCallbacks);if(!notified){notified=true;dismissed()}}
    companion object {
        fun activity(context:Context)=generateSequence(context){(it as? ContextWrapper)?.baseContext}.filterIsInstance<Activity>().firstOrNull()?.takeUnless{it.isFinishing || it.isDestroyed}
        fun show(context:Context,title:String,onDismiss:()->Unit={},content:@Composable (()->Unit)->Unit):ToolDialog? {
            val host=activity(context)?:return null
            return ToolDialog(host,title,onDismiss,content).also{it.show()}
        }
        fun actions(context:Context,title:String,items:List<Pair<String,()->Unit>>) {
            show(context,title){dismiss->
                items.forEach{(label,action)->TextButton(onClick={dismiss();action()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(label,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyLarge)}}
                TextButton(onClick=dismiss,modifier=Modifier.fillMaxWidth()){Text("取消")}
            }
        }
        fun error(context:Context,title:String,error:Throwable,alternate:Pair<String,()->Unit>?=null,retry:(()->Unit)?=null) {
            val log=DiagnosticLog.get(context);val id=(error as? ToolFailure)?.requestId.orEmpty()
            val detail=log.errorText(error);var expanded by mutableStateOf(false)
            show(context,title){dismiss->
                SelectionContainer{Text(error.message?:error.javaClass.simpleName,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyLarge)}
                if(id.isNotBlank())Text("请求编号：$id",style=MaterialTheme.typography.bodySmall)
                if(expanded)SelectionContainer{Column(Modifier.heightIn(max=260.dp).verticalScroll(rememberScrollState())){Text(detail,style=MaterialTheme.typography.bodySmall)}}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起详情" else "查看详情")}
                    TextButton(onClick={copyDiagnostic(context,"请求编号：$id\n$detail")}){Text("复制错误")}
                }
                alternate?.let{(label,action)->OutlinedButton(onClick={dismiss();action()},modifier=Modifier.fillMaxWidth()){Text(label)}}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick=dismiss){Text("关闭")}
                    if(retry!=null)Button(onClick={dismiss();retry()}){Text("重试")}
                }
            }
        }
    }
}
internal fun copyDiagnostic(context:Context,text:String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("QIII诊断",text))
}
@Composable internal fun LensProgressContent(value:JSONObject,started:Long,cancel:()->Unit) {
    var elapsed by remember{mutableLongStateOf(0)}
    LaunchedEffect(started){while(true){elapsed=(SystemClock.elapsedRealtime()-started)/1000;delay(1000)}}
    val thumbnail=value.optString("preview")
    val bitmap=remember(thumbnail){if(thumbnail.isBlank())null else runCatching{val bytes=Base64.decode(thumbnail,Base64.NO_WRAP);BitmapFactory.decodeByteArray(bytes,0,bytes.size)}.getOrNull()}
    DisposableEffect(bitmap){onDispose{bitmap?.recycle()}}
    bitmap?.let{Image(it.asImageBitmap(),"本次实际上传的图片",Modifier.fillMaxWidth().heightIn(max=180.dp))}
    Text(value.optString("message","正在准备图片…"),style=MaterialTheme.typography.bodyLarge)
    LinearProgressIndicator(Modifier.fillMaxWidth())
    if(value.has("bytes"))Text("${value.optLong("bytes")/1024} KB · ${if(value.optBoolean("sourcePreserved"))"保留原文件" else "已优化图片"}",style=MaterialTheme.typography.bodySmall)
    Text("已用时 ${elapsed} 秒",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick=cancel,modifier=Modifier.fillMaxWidth()){Text("取消")}
}
