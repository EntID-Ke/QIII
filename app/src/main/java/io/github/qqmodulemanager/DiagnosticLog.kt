package io.github.qqmodulemanager

import android.content.Context
import android.os.Process
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID

/** Bounded local events. Message bodies, image data and credential values are never fields. */
internal class DiagnosticLog(context:Context) {
    private val directory=File(context.filesDir,"qqmm-diagnostics").apply{mkdirs()}
    private val file=File(directory,"events-${Process.myPid()}.jsonl")
    private val old=File(directory,"events-${Process.myPid()}.previous.jsonl")
    private val process=if(android.os.Build.VERSION.SDK_INT>=28)android.app.Application.getProcessName()?:context.packageName else context.packageName
    init{directory.listFiles{_,name->name.startsWith("events-") && name.endsWith(".jsonl")}?.sortedByDescending{it.lastModified()}?.drop(16)?.forEach{it.delete()}}
    private val secrets=java.util.concurrent.CopyOnWriteArraySet<String>()
    fun secret(value:String){if(value.isNotBlank())secrets.add(value)}
    fun redact(value:String):String {
        var text=value
        secrets.forEach{text=text.replace(it,"[已隐藏]")}
        text=Regex("https?://[^\\s<>\"']+").replace(text){match->runCatching{
            val uri=URI(match.value);"${uri.scheme}://${uri.host}${uri.path.orEmpty()}"+(if(uri.rawQuery!=null)"?[已隐藏参数]" else "")
        }.getOrDefault("[已隐藏链接]")}
        text=Regex("(?i)(bearer\\s+)[^\\s,;]+").replace(text,"$1[已隐藏]")
        return Regex("(?i)((?:api[-_]?key|authorization|cookie|token|secret)\\s*[:=]\\s*)[^\\s,;]+").replace(text,"$1[已隐藏]")
    }
    @Synchronized fun event(level:String,operation:String,stage:String,id:String="",fields:JSONObject=JSONObject(),error:Throwable?=null) {
        try {
            val row=JSONObject().put("time",System.currentTimeMillis()).put("level",level).put("process",process).put("pid",Process.myPid())
                .put("version",BuildConfig.VERSION_NAME).put("operation",operation).put("stage",stage).put("requestId",id).put("details",fields)
            if(error!=null)row.put("error",errorText(error))
            if(file.length()>768*1024){old.delete();file.renameTo(old)}
            file.appendText(sanitize(row).toString()+"\n")
        }catch(_:java.io.IOException){/* Logging must not replace the original operation's result. */}
    }
    private fun sanitize(value:Any?):Any?=when(value) {
        is String->redact(value)
        is JSONObject->JSONObject().apply{value.keys().forEach{put(it,sanitize(value.get(it)))}}
        is JSONArray->JSONArray((0 until value.length()).map{sanitize(value.get(it))})
        else->value
    }
    fun redactedRecord(row:JSONObject)=sanitize(row) as JSONObject
    fun errorText(error:Throwable):String {
        val chain=generateSequence(error){it.cause}.take(8).toList()
        return redact(chain.joinToString("\nCaused by: "){cause->
            val message=if(cause is org.json.JSONException)"JSON 响应解析失败" else cause.message.orEmpty()
            cause.javaClass.name+": "+message+"\n"+cause.stackTrace.take(24).joinToString("\n"){"    at $it"}
        }).take(16000)
    }
    @Synchronized fun read():JSONArray {
        val records=directory.listFiles{_,name->name.startsWith("events-") && name.endsWith(".jsonl")}?.flatMap{it.readLines().takeLast(400)}
            ?.mapNotNull{runCatching{JSONObject(it)}.getOrNull()}?.sortedBy{it.optLong("time")}?.takeLast(400).orEmpty()
        val selected=ArrayList<JSONObject>();var size=0
        for(row in records.asReversed()) {
            val line=row.toString()
            if(size+line.length>240000)break
            size+=line.length;selected.add(row)
        }
        return JSONArray(selected.asReversed())
    }
    @Synchronized fun clear(){directory.listFiles{_,name->name.startsWith("events-") && name.endsWith(".jsonl")}?.forEach{it.delete()}}
    fun trace(operation:String,id:String=UUID.randomUUID().toString())=ToolTrace(this,id,operation)
    companion object {
        @Volatile private var instance:DiagnosticLog?=null
        fun get(context:Context):DiagnosticLog=instance?:synchronized(this){instance?:DiagnosticLog(context.applicationContext?:context).also{instance=it}}
    }
}
internal class ToolTrace(val log:DiagnosticLog,val id:String,val operation:String) {
    private val start=android.os.SystemClock.elapsedRealtime()
    fun event(stage:String,fields:JSONObject=JSONObject(),level:String="INFO",error:Throwable?=null) {
        log.event(level,operation,stage,id,fields.put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start),error)
    }
    suspend fun <T> run(block:suspend()->T):T=withContext(current.asContextElement(this)){block()}
    companion object {val current=ThreadLocal<ToolTrace?>()}
}
internal class ToolFailure(val requestId:String,message:String,cause:Throwable):java.io.IOException(message,cause)
internal fun diagnosticText(records:JSONArray):String=buildString {
    append("QIII ${BuildConfig.VERSION_NAME} 诊断日志\nAndroid ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}\n")
    append("记录不含消息正文、图片内容和 API Key。\n\n")
    for(row in records.objects())append(row.toString(2)).append("\n\n")
}
