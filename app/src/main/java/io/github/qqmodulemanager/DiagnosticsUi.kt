package io.github.qqmodulemanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun DiagnosticsUi(context:Context,modifier:Modifier) {
    val client=remember{FeatureConnection(context)};val scope=rememberCoroutineScope()
    var rows by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var busy by remember{mutableStateOf(false)};var notice by remember{mutableStateOf("")}
    var errorsOnly by remember{mutableStateOf(false)};var selected by remember{mutableStateOf<JSONObject?>(null)}
    var analysis by remember{mutableStateOf(false)};var analysisEntry by remember{mutableStateOf<JSONObject?>(null)}
    fun work(block:suspend()->Unit){if(!busy){busy=true;scope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){notice=e.message?:"读取日志失败"}finally{busy=false}}}}
    suspend fun refresh() {
        val local=withContext(Dispatchers.IO){DiagnosticLog.get(context).read().objects()}
        val remote=try{withTimeoutOrNull(5000){client.call("diagnostics").getJSONArray("records").objects()}?:run{notice="QQ 日志读取超时，已显示本进程日志";emptyList()}}
            catch(e:CancellationException){throw e}catch(e:Exception){notice="QQ 日志暂不可读取：${e.message}";emptyList()}
        rows=(local+remote).distinctBy{it.toString()}.sortedByDescending{it.optLong("time")}
    }
    fun text()=diagnosticText(JSONArray(rows.asReversed()))
    fun export(share:Boolean)=work {
        val content=text()
        val response=withContext(Dispatchers.IO){coroutineScope {
            val pipe=android.os.ParcelFileDescriptor.createPipe()
            val writer=async(Dispatchers.IO){android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).bufferedWriter().use{it.write(content)}}
            try{val result=context.contentResolver.call(PolicyStore.URI,"diagnosticExport",null,Bundle().apply{putParcelable("content",pipe[0])})?:error("日志导出接口不可用");writer.await();result}
            finally{pipe[0].close();writer.cancel()}
        }}
        val uri=Uri.parse(response.getString("uri"))
        if(share)context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=android.content.ClipData.newRawUri("诊断日志",uri)},"分享诊断日志"))
        else context.startActivity(Intent().setClassName(FeatureWire.PACKAGE,"${FeatureWire.PACKAGE}.DiagnosticExportActivity").setData(uri))
    }
    LaunchedEffect(Unit){work{refresh()}}
    DisposableEffect(client){onDispose{client.close()}}
    LazyColumn(modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=28.dp)) {
        item{Text("查看请求的执行进程、取图与压缩、上传字节、耗时、HTTP 状态和异常原因。日志不保存消息正文、图片内容或 API Key。",style=MaterialTheme.typography.bodyMedium)}
        if(notice.isNotBlank())item{SelectionContainer{Text(notice,color=MaterialTheme.colorScheme.error)}}
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled=!busy,onClick={work{notice="";refresh()}}){Text("刷新")}
            OutlinedButton(onClick={copyDiagnostic(context,text());notice="已复制日志"}){Text("复制全部")}
            OutlinedButton(enabled=!busy,onClick={export(false)}){Text("导出文件")}
            OutlinedButton(enabled=!busy,onClick={export(true)}){Text("分享日志")}
            OutlinedButton(enabled=!busy && rows.isNotEmpty(),onClick={analysisEntry=null;analysis=true}){Text("AI 总结")}
            TextButton(enabled=!busy,onClick={work{client.call("clearDiagnostics");withContext(Dispatchers.IO){DiagnosticLog.get(context).clear()};rows=emptyList();notice="日志已清空"}}){Text("清空")}
        }}
        item{FilterChip(selected=errorsOnly,onClick={errorsOnly=!errorsOnly},label={Text(if(errorsOnly)"仅显示错误与警告" else "全部记录")})}
        if(busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        val shown=rows.filter{!errorsOnly || it.optString("level") in listOf("ERROR","WARN")}
        if(shown.isEmpty())item{Text("暂无符合条件的日志。可先在 QQ 中重试一次，再刷新。")}
        items(shown){row->Card(Modifier.fillMaxWidth().clickable{selected=row}){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text("${row.optString("operation")} · ${row.optString("stage")}",style=MaterialTheme.typography.titleMedium,color=if(row.optString("level")=="ERROR")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("${java.text.SimpleDateFormat("HH:mm:ss.SSS",java.util.Locale.ROOT).format(java.util.Date(row.optLong("time")))} · ${row.optString("process")} · ${row.optString("level")}",style=MaterialTheme.typography.bodySmall)
            if(row.has("error"))Text(row.optString("error").lineSequence().first(),style=MaterialTheme.typography.bodySmall)
        }}}
    }
    selected?.let{row->AlertDialog(onDismissRequest={selected=null},title={Text("日志详情")},text={
        Column {
            TextButton(onClick={analysisEntry=row;selected=null;analysis=true}){Text("AI 分析此条")}
            SelectionContainer{Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState())){Text(row.toString(2),style=MaterialTheme.typography.bodySmall)}}
        }
    },confirmButton={TextButton(onClick={copyDiagnostic(context,row.toString(2))}){Text("复制此条")}},dismissButton={TextButton(onClick={selected=null}){Text("关闭")}})}
    if(analysis)DiagnosticAnalysisDialog(context,client,rows,analysisEntry){analysis=false}
}
