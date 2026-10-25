package io.github.qqmodulemanager

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.Closeable
import kotlin.coroutines.resume

/** Executes in the caller's process. QQ owns its network calls, cache and encrypted mirror. */
internal class FeatureRuntime(private val context:Context,val store:TranslationStore,
    private val engine:TranslationEngine=TranslationEngine(),
    private val saveConfiguration:(suspend(String,JSONObject)->Unit)?=null,
    private val clearCaches:(suspend(Bundle)->Unit)?=null):Closeable {
    private val log=DiagnosticLog.get(context)
    private val detectorDelegate=lazy{com.google.mlkit.common.MlKit.initialize(context);LanguageIdentification.getClient()}
    private var languageFailureReported=false
    private suspend fun language(text:String):String=suspendCancellableCoroutine { continuation->
        fun fallback(error:Throwable?) {
            if(!languageFailureReported && error!=null){languageFailureReported=true;ToolTrace.current.get()?.event("language.fallback",level="WARN",error=error)}
            if(continuation.isActive)continuation.resume(fallbackLanguage(text))
        }
        try{detectorDelegate.value.identifyLanguage(text).addOnSuccessListener{if(continuation.isActive)continuation.resume(if(it=="und")fallbackLanguage(text) else it)}.addOnFailureListener{fallback(it)}}
        catch(e:Exception){fallback(e)}catch(e:LinkageError){fallback(e)}
    }
    suspend fun perform(op:String,args:Bundle,progress:(JSONObject)->Unit={}):JSONObject {
        val trace=log.trace(op,args.getString("requestId")?:java.util.UUID.randomUUID().toString())
        return trace.run {
            val recordSuccess=op !in listOf("lookup","expand","diagnostics")
            if(recordSuccess)trace.event("start",JSONObject().put("executor",context.packageName))
            try{performOperation(op,args,progress).also{if(recordSuccess)trace.event("complete")}}
            catch(e:CancellationException){trace.event("cancelled");throw e}
            catch(e:Exception){trace.event("failed",level="ERROR",error=e);throw ToolFailure(trace.id,e.message?:e.javaClass.simpleName,e)}
        }
    }
    private suspend fun performOperation(op:String,args:Bundle,progress:(JSONObject)->Unit):JSONObject {
        fun input()=JSONObject(args.getString("document","{}"))
        val account=args.getString("account","");val message=args.getString("message","");val source=args.getString("source","")
        return when(op) {
            "diagnostics"->JSONObject().put("records",log.read())
            "clearDiagnostics"->{log.clear();JSONObject()}
            "settings"->JSONObject().put("settings",store.settings()).put("accounts",store.accounts()).put("executor",context.packageName)
            "saveSettings","saveProvider"->{
                val document=input()
                if(saveConfiguration!=null)saveConfiguration.invoke(op,document)
                else if(op=="saveSettings")store.saveSettings(document) else store.saveProvider(document)
                JSONObject().put("settings",store.settings())
            }
            "models","test"->{
                val profile=input();val key=store.credential(profile);log.secret(key)
                if(op=="models")JSONObject().put("models",engine.models(profile,key))
                else JSONObject().put("text",engine.translate(profile,key,"Hello, how are you?","zh-Hans",store.settings().getString("prompt")))
            }
            "analyzeDiagnostics"->{
                val document=input();val profile=store.provider(document.getString("provider"))
                require(profile.getString("protocol") in listOf("chat","responses","gemini","anthropic")){"请选择翻译器中配置的 AI 服务"}
                val key=store.credential(profile);log.secret(key)
                val text=log.redact(document.getString("text"))
                require(text.isNotBlank() && text.length<=32000){"日志内容为空或超过分析长度限制"}
                val meta=JSONObject().put("providerName",profile.getString("name")).put("model",profile.optString("model"))
                ToolTrace.current.get()?.event("analysis.request",JSONObject().put("provider",profile.getString("id")).put("model",profile.optString("model")).put("characters",text.length).put("reasoning",profile.optString("reasoning","auto")))
                val answer=engine.generate(profile,key,text,DIAGNOSTIC_PROMPT){partial->progress(meta.copy().put("text",partial))}
                meta.put("text",answer)
            }
            "lookup"->JSONObject().put("result",store.lookup(account,message,source)?:JSONObject.NULL)
            "expand"->{store.expand(account,message,source,args.getBoolean("expanded"));JSONObject()}
            "clear"->{
                if(clearCaches!=null)clearCaches.invoke(args) else {FeatureConfigBridge.call(context,"toolClear",args);store.clear(args.getString("account"),args.getString("message"))}
                JSONObject().put("accounts",store.accounts())
            }
            "translate"->{
                val generation=store.generationFor(account,message)
                val settings=store.settings();val text=args.getString("text","")
                require(account.isNotBlank() && message.isNotBlank() && digest(text)==source){"消息身份或正文指纹无效"}
                val profile=store.provider(args.getString("provider")?.takeIf{it.isNotBlank()}?:settings.getString("provider"))
                val key=store.credential(profile);log.secret(key)
                val detected=if(args.getString("target").isNullOrBlank() && settings.getBoolean("chineseToEnglish"))language(text) else "und"
                val target=args.getString("target")?.takeIf{it.isNotBlank()}?:settings.getString(if(detected=="zh")"chineseTarget" else "target")
                ToolTrace.current.get()?.event("translation.request",JSONObject().put("provider",profile.getString("id")).put("protocol",profile.getString("protocol")).put("model",profile.optString("model")).put("characters",text.length).put("target",target).put("sourceLanguage",detected))
                val meta=JSONObject().put("provider",profile.getString("id")).put("providerName",profile.getString("name"))
                    .put("model",profile.optString("model")).put("target",target).put("sourceLanguage",detected)
                    .put("fingerprint",digest(profile.toString()+settings.getString("prompt")+target)).put("createdAt",System.currentTimeMillis())
                val translated=engine.translate(profile,key,text,target,profile.optString("prompt").ifBlank{settings.getString("prompt")}){partial->progress(meta.copy().put("text",partial))}
                currentCoroutineContext().ensureActive()
                val result=meta.put("text",translated).put("expanded",true)
                store.put(account,message,source,result,generation);result
            }
            "lens"->{
                @Suppress("DEPRECATION") val descriptor=args.getParcelable<ParcelFileDescriptor>("image")?:error("没有收到图片")
                JSONObject().put("url",LensSearch(context).search(descriptor,progress))
            }
            else->error("未知QIII功能请求")
        }
    }
    override fun close(){if(detectorDelegate.isInitialized())detectorDelegate.value.close();store.close()}
}
internal fun fallbackLanguage(text:String):String {
    if(text.any{it.code in 0x3040..0x30ff || it.code in 0xac00..0xd7af})return "und"
    val letters=text.count{it.isLetter()};val han=text.count{it.code in 0x3400..0x9fff}
    return if(han>0 && han*2>=letters)"zh" else "und"
}
