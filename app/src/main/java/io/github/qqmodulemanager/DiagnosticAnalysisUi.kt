package io.github.qqmodulemanager

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable internal fun DiagnosticAnalysisDialog(context:Context,client:FeatureConnection,rows:List<JSONObject>,selected:JSONObject?,dismiss:()->Unit) {
    val scope=rememberCoroutineScope()
    var providers by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var provider by remember{mutableStateOf("")}
    var loading by remember{mutableStateOf(true)}
    var job by remember{mutableStateOf<Job?>(null)}
    var result by remember{mutableStateOf("")}
    var failure by remember{mutableStateOf<Throwable?>(null)}
    var picker by remember{mutableStateOf(false)}
    val log=remember{DiagnosticLog.get(context)}
    val input=remember(rows,selected){diagnosticAnalysisInput(rows,selected,log)}
    val profile=providers.firstOrNull{it.optString("id")==provider}
    var running by remember{mutableStateOf(false)}
    LaunchedEffect(Unit) {
        try {
            val settings=client.call("settings").getJSONObject("settings")
            providers=settings.getJSONArray("providers").objects()
            provider=providers.firstOrNull{it.optString("id")==settings.optString("provider")}?.getString("id")?:providers.firstOrNull()?.getString("id").orEmpty()
        }catch(e:CancellationException){throw e}catch(e:Exception){failure=e}finally{loading=false}
    }
    fun analyze() {
        if(running || profile==null)return
        val id=provider;result="";failure=null;running=true
        job=scope.launch {
            try {
                val response=client.call("analyzeDiagnostics",JSONObject().put("provider",id).put("text",input)) { partial->scope.launch{result=partial.optString("text")} }
                result=response.getString("text")
            }catch(e:CancellationException){throw e}catch(e:Exception){failure=e}finally{running=false}
        }
    }
    AlertDialog(onDismissRequest=dismiss,title={Text(if(selected==null)"AI 日志总结" else "AI 分析此条")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("复用翻译器的 AI 服务、模型和思考档位。点击分析会将本次脱敏日志发送给所选服务；分析结果仅供排查参考。",style=MaterialTheme.typography.bodySmall)
            if(loading || running)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(!loading && providers.isEmpty())Text("请先在消息翻译设置中添加 AI 服务。微软和 Google 免费翻译接口不提供日志分析。")
            if(providers.isNotEmpty())Box {
                OutlinedButton(enabled=!running,onClick={picker=true}){Text(profile?.optString("name").orEmpty())}
                DropdownMenu(picker,onDismissRequest={picker=false}){providers.forEach{p->DropdownMenuItem(text={Text("${p.optString("name")} · ${p.optString("model")}")},onClick={provider=p.getString("id");result="";failure=null;picker=false})}}
            }
            profile?.let{Text("${it.optString("model").ifBlank{"尚未设置模型"}} · 思考 ${it.optString("reasoning","auto")}",style=MaterialTheme.typography.bodySmall)}
            Text(if(selected==null)"范围：已读取的 ${rows.size} 条日志，优先错误与警告，最多约 3 万字符。" else "范围：此条日志及同一请求编号的上下文。",style=MaterialTheme.typography.bodySmall)
            if(result.isNotBlank())SelectionContainer{Text(result)}
            failure?.let{error->Text(error.message?:"AI 分析失败",color=MaterialTheme.colorScheme.error);TextButton(onClick={ToolDialog.error(context,"AI 分析失败",error)}){Text("查看／复制错误详情")}}
        }
    },confirmButton={if(running)TextButton(onClick={job?.cancel()}){Text("停止分析")} else TextButton(enabled=!loading && profile!=null,onClick=::analyze){Text(if(result.isBlank())"开始分析" else "重新分析")}},dismissButton={
        Row{if(result.isNotBlank())TextButton(onClick={copyDiagnostic(context,"${profile?.optString("name")} · ${profile?.optString("model")}\n\n$result")}){Text("复制分析")};TextButton(onClick=dismiss){Text("关闭")}}
    })
}
