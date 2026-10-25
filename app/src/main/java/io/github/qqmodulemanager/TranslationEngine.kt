package io.github.qqmodulemanager

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class TranslationEngine(private val client:OkHttpClient=OkHttpClient.Builder()
    .connectTimeout(15,TimeUnit.SECONDS).writeTimeout(45,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).callTimeout(180,TimeUnit.SECONDS)
    .eventListenerFactory{NetworkDiagnostics()}
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()) {
    private val json="application/json; charset=utf-8".toMediaType()
    internal suspend fun <T> request(request:Request,key:String="",acceptRedirect:Boolean=false,read:(Response)->T):T=suspendCancellableCoroutine { continuation->
        val trace=NetworkTrace(ToolTrace.current.get(),request.url.host)
        trace.trace?.event("http.start",JSONObject().put("host",request.url.host).put("path",request.url.encodedPath).put("method",request.method))
        val call=client.newCall(request.newBuilder().tag(NetworkTrace::class.java,trace).build())
        continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException){trace.trace?.event(if(call.isCanceled())"http.cancelled" else "http.failed",level=if(call.isCanceled())"INFO" else "ERROR",error=if(call.isCanceled())null else e);if(continuation.isActive)continuation.resumeWithException(IOException(networkFailure(e,request.url.host,trace.stage,call.isCanceled()),e))}
            override fun onResponse(call:Call,response:Response){
                try {
                    val value=response.use {
                        if(!it.isSuccessful && !(acceptRedirect && it.code in listOf(301,302,303,307,308))) {
                            val hint=when(it.code){401,403->"鉴权失败或访问被拒绝";429->"请求过于频繁，请稍后重试";404->"地址或模型不存在";in 500..599->"服务暂时不可用";else->"请求参数、模型或接口不受支持"}
                            val body=it.peekBody(2048).string()
                            val detail=runCatching{JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()}.getOrDefault("")
                            val redacted=if(key.isNotEmpty())detail.replace(key,"[已隐藏]") else detail
                            error("HTTP ${it.code}：$hint${if(redacted.isNotBlank())" · "+redacted.take(180) else ""}")
                        }
                        read(it)
                    }
                    if(continuation.isActive)continuation.resume(value)
                }catch(e:Exception){
                    trace.trace?.event("http.read-failed",level=if(call.isCanceled())"INFO" else "ERROR",error=if(call.isCanceled())null else e)
                    if(continuation.isActive)continuation.resumeWithException(if(e is IOException)IOException(networkFailure(e,request.url.host,trace.stage,call.isCanceled()),e) else e)
                }
            }
        })
    }
    internal fun endpoint(profile:JSONObject,suffix:String):HttpUrl {
        val raw=profile.optString("url").trim().trimEnd('/')
        require(raw.isNotBlank()){"请填写 API 地址"}
        val base=raw.toHttpUrl()
        require(base.username.isEmpty() && base.password.isEmpty() && base.query==null && base.fragment==null){"API 地址不能包含凭据、查询参数或片段"}
        val path=base.encodedPath.trimEnd('/')
        val known=listOf("/chat/completions","/responses","/messages","/models")
        val clean=known.firstOrNull{path.endsWith(it)}?.let{path.removeSuffix(it)}?:path
        return base.newBuilder().encodedPath(clean+"/"+suffix.trimStart('/')).build()
    }
    private fun api(profile:JSONObject,key:String,path:String,body:JSONObject?=null):Request {
        require(key.all{it.code in 33..126}){"API Key 包含空白或不支持的字符，请检查粘贴内容"}
        val builder=Request.Builder().url(endpoint(profile,path))
        when(profile.getString("protocol")) {
            "gemini"->builder.header("x-goog-api-key",key)
            "anthropic"->{builder.header("x-api-key",key);builder.header("anthropic-version","2023-06-01")}
            else->if(key.isNotEmpty())builder.header("Authorization","Bearer $key")
        }
        return if(body==null)builder.get().build() else builder.post(body.toString().toRequestBody(json)).build()
    }
    suspend fun models(profile:JSONObject,key:String):JSONArray {
        val ids=linkedSetOf<String>();var token="";val visited=HashSet<String>()
        do {
            require(visited.add(token) && visited.size<=100){"模型列表分页异常"}
            val request=api(profile,key,"models").newBuilder()
            val url=api(profile,key,"models").url.newBuilder()
            val protocol=profile.getString("protocol")
            if(protocol=="gemini"){url.addQueryParameter("pageSize","100");if(token.isNotEmpty())url.addQueryParameter("pageToken",token)}
            else if(token.isNotEmpty())url.addQueryParameter("after",token)
            val data=request(request.url(url.build()).build(),key){JSONObject(it.body!!.string())}
            val entries=data.optJSONArray(if(protocol=="gemini")"models" else "data")?:error("服务没有返回模型列表，可手动填写模型 ID")
            entries.objects().forEach { item->
                if(protocol!="gemini" || item.optJSONArray("supportedGenerationMethods")?.let{methods->(0 until methods.length()).any{methods.optString(it)=="generateContent"}}!=false) {
                    val id=item.optString(if(protocol=="gemini")"name" else "id").removePrefix("models/")
                    if(id.isNotBlank())ids.add(id)
                }
            }
            token=if(protocol=="gemini")data.optString("nextPageToken") else if(data.optBoolean("has_more"))data.optString("last_id").ifBlank{entries.optJSONObject(entries.length()-1)?.optString("id").orEmpty()} else ""
            if(data.optBoolean("has_more") && token.isEmpty())error("模型分页缺少游标")
        }while(token.isNotEmpty())
        return JSONArray(ids.toList())
    }
    internal fun reasoning(profile:JSONObject,body:JSONObject) {
        val level=profile.optString("reasoning","auto")
        if(level=="auto")return
        require(level in listOf("off","minimal","low","medium","high","xhigh","max","on")){"未知思考档位"}
        val protocol=profile.getString("protocol");val model=profile.optString("model").lowercase()
        val mode=reasoningMode(profile)
        require(reasoningChoices(profile).any{it.first==level} || mode in listOf("qwen","deepseek") && level=="high"){"此参数模式不支持 $level 档位，请更换档位或参数模式"}
        val budget=profile.optInt("budget",when(level){"off"->0;"low"->1024;"medium"->2048;"high"->8192;"xhigh"->16384;else->32768})
        require(budget in 0..32768){"思考预算应在 0～32768 之间"}
        when(mode) {
            "effort"->{require(protocol in listOf("chat","responses")){"effort 只适用于 OpenAI 协议"};val effort=if(level=="off")"none" else level;if(protocol=="responses")body.put("reasoning",JSONObject().put("effort",effort)) else body.put("reasoning_effort",effort)}
            "gemini-level","gemini-budget"->{
                require(protocol=="gemini"){"请选择 Gemini 协议"}
                if(mode=="gemini-budget" && level=="off" && model.contains("pro"))error("此 Gemini Pro 模型不能关闭思考，请使用模型默认或预算")
                val thinking=JSONObject().put("includeThoughts",false)
                if(mode=="gemini-level")thinking.put("thinkingLevel",level)
                else thinking.put("thinkingBudget",if(level=="off")0 else budget)
                body.put("generationConfig",JSONObject().put("thinkingConfig",thinking))
            }
            "claude-adaptive","claude-budget"->{
                require(protocol=="anthropic"){"请选择 Anthropic 协议"}
                if(level=="off")body.put("thinking",JSONObject().put("type","disabled"))
                else if(mode=="claude-adaptive"){body.put("thinking",JSONObject().put("type","adaptive"));body.put("output_config",JSONObject().put("effort",level))}
                else {require(budget>=1024){"Anthropic 思考预算至少为 1024"};body.put("thinking",JSONObject().put("type","enabled").put("budget_tokens",budget));body.put("max_tokens",budget+4096)}
            }
            "qwen"->{require(protocol=="chat"){"请选择 Chat Completions 协议"};body.put("enable_thinking",level!="off")}
            "deepseek"->{require(protocol=="chat"){"请选择 Chat Completions 协议"};body.put("thinking",JSONObject().put("type",if(level=="off")"disabled" else "enabled"))}
            else->error("未知思考参数模式")
        }
    }
    internal fun body(profile:JSONObject,text:String,target:String,prompt:String):JSONObject {
        return generationBody(profile,text,prompt.ifBlank{TRANSLATION_PROMPT}.replace("{target_lang}",target))
    }
    private fun generationBody(profile:JSONObject,text:String,instruction:String):JSONObject {
        val model=profile.optString("model").removePrefix("models/")
        require(model.isNotBlank()){"请先选择翻译模型"}
        val body=JSONObject().put("model",model).put("stream",true)
        when(profile.getString("protocol")) {
            "chat"->body.put("messages",JSONArray().put(JSONObject().put("role","system").put("content",instruction)).put(JSONObject().put("role","user").put("content",text)))
            "responses"->body.put("instructions",instruction).put("input",text)
            "gemini"->{body.remove("model");body.remove("stream");body.put("systemInstruction",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",instruction))));body.put("contents",JSONArray().put(JSONObject().put("role","user").put("parts",JSONArray().put(JSONObject().put("text",text)))))}
            "anthropic"->body.put("max_tokens",8192).put("system",instruction).put("messages",JSONArray().put(JSONObject().put("role","user").put("content",text)))
            else->error("不支持的 AI 协议")
        }
        reasoning(profile,body)
        return body
    }
    suspend fun translate(profile:JSONObject,key:String,text:String,target:String,prompt:String,onText:(String)->Unit={}):String {
        require(text.isNotBlank() && text.length<=50000){"待翻译文字为空或超过 50000 字符"}
        if(profile.getString("protocol") in listOf("microsoft","google"))return free(profile.getString("protocol"),text,target)
        return generate(profile,key,text,prompt.ifBlank{TRANSLATION_PROMPT}.replace("{target_lang}",target),onText)
    }
    suspend fun generate(profile:JSONObject,key:String,text:String,instruction:String,onText:(String)->Unit={}):String {
        require(text.isNotBlank() && text.length<=50000){"输入内容为空或超过 50000 字符"}
        val protocol=profile.getString("protocol")
        val body=generationBody(profile,text,instruction)
        val path=when(protocol){"chat"->"chat/completions";"responses"->"responses";"anthropic"->"messages";else->"models/${profile.getString("model").removePrefix("models/")}:streamGenerateContent"}
        val initial=api(profile,key,path,body)
        val request=if(protocol=="gemini")initial.newBuilder().url(initial.url.newBuilder().addQueryParameter("alt","sse").build()).build() else initial
        return request(request,key){response->
            val stream=TranslationStream(protocol,onText)
            if(response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                var data=StringBuilder()
                val source=response.body!!.source()
                while(true) {
                    val line=source.readUtf8Line()?:break
                    if(line.isEmpty()){if(data.isNotEmpty()){stream.accept(data.toString());data=StringBuilder()}}
                    else if(line.startsWith("data:")){if(data.isNotEmpty())data.append('\n');data.append(line.substring(5).trimStart())}
                }
                if(data.isNotEmpty())stream.accept(data.toString())
                stream.finish()
            }else stream.complete(JSONObject(response.body!!.string()))
        }
    }
    private suspend fun free(service:String,text:String,target:String):String {
        val pieces=split(text);val translated=ArrayList<String>()
        for(part in pieces) {
            if(part.isBlank()){translated.add(part);continue}
            val leading=part.takeWhile{it.isWhitespace()};val trailing=part.takeLastWhile{it.isWhitespace()};val input=part.trim()
            if(service=="microsoft") {
                val language=when(target){"zh-CN","zh"->"zh-Hans";"zh-TW"->"zh-Hant";else->target}
                val url="https://edge.microsoft.com/translate/translatetext".toHttpUrl().newBuilder().addQueryParameter("from","").addQueryParameter("to",language).addQueryParameter("isEnterpriseClient","false").build()
                val value=request(Request.Builder().url(url).post(JSONArray().put(input).toString().toRequestBody(json)).build()) {
                    val result=JSONArray(it.body!!.string());require(result.length()==1){"微软返回的译文数量异常"}
                    result.getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text")
                }
                require(value.isNotBlank()){"微软返回空译文"};translated.add(leading+value.trim()+trailing)
            }else {
                val language=when(target){"zh-Hans"->"zh-CN";"zh-Hant"->"zh-TW";else->target}
                val url="https://translate.googleapis.com/translate_a/t".toHttpUrl().newBuilder().addQueryParameter("client","gtx").addQueryParameter("sl","auto").addQueryParameter("tl",language).addQueryParameter("dt","t").build()
                val value=try {
                    request(Request.Builder().url(url).post(FormBody.Builder().add("q",input).build()).build()) {
                        val result=JSONArray(it.body!!.string());require(result.length()==1){"Google 返回的译文数量异常"}
                        if(result.get(0) is JSONArray)result.getJSONArray(0).getString(0) else result.getString(0)
                    }
                }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){googleRpc(input,language)}
                require(value.isNotBlank()){"Google 返回空译文"};translated.add(leading+value.trim()+trailing)
            }
        }
        return translated.joinToString("")
    }
    private suspend fun googleRpc(text:String,target:String):String {
        val args=JSONArray().put(JSONArray().put(text).put("auto").put(target).put(true)).put(JSONArray().put(JSONObject.NULL))
        val payload=JSONArray().put(JSONArray().put(JSONArray().put("MkEWBc").put(args.toString()).put(JSONObject.NULL).put("0")))
        return request(Request.Builder().url("https://translate.google.com/_/TranslateWebserverUi/data/batchexecute?rpcids=MkEWBc")
            .post(FormBody.Builder().add("f.req",payload.toString()).build()).build()) { response->
            for(line in response.body!!.string().lineSequence()) {
                val records=runCatching{JSONArray(line)}.getOrNull()?:continue
                for(i in 0 until records.length()) {
                    val row=records.optJSONArray(i)?:continue
                    if(row.optString(0)!="wrb.fr" || row.optString(1)!="MkEWBc")continue
                    val data=JSONArray(row.getString(2));val parts=data.getJSONArray(1).getJSONArray(0).getJSONArray(0).getJSONArray(5)
                    return@request (0 until parts.length()).joinToString(""){parts.getJSONArray(it).getString(0)}
                }
            }
            error("Google 网页接口格式变化或要求验证")
        }
    }
    internal fun split(text:String):List<String> {
        val result=ArrayList<String>();var start=0
        while(start<text.length) {
            if(text[start]=='\r' || text[start]=='\n') {
                val end=if(text[start]=='\r' && text.getOrNull(start+1)=='\n')start+2 else start+1
                result.add(text.substring(start,end));start=end;continue
            }
            var end=minOf(start+1800,text.length)
            val lineEnd=text.indexOfAny(charArrayOf('\r','\n'),start)
            if(lineEnd in start until end)end=lineEnd
            if(end<text.length && Character.isHighSurrogate(text[end-1]))end--
            if(end<text.length && lineEnd!=end){val space=text.lastIndexOf(' ',end-1);if(space>start+900)end=space+1}
            result.add(text.substring(start,end));start=end
        }
        return result
    }
}

/** Accept only final-answer text. A broken stream never becomes a saved translation. */
internal class TranslationStream(private val protocol:String,private val update:(String)->Unit) {
    private val text=StringBuilder();private var terminal=false;private var emitted=0L
    private fun append(value:String){require(text.length+value.length<=100000){"译文过长"};text.append(value);val now=System.nanoTime();if(now-emitted>60_000_000){emitted=now;update(text.toString())}}
    fun accept(data:String) {
        if(data=="[DONE]"){return}
        val event=JSONObject(data)
        if(event.has("error") || event.optString("type")=="error")error("翻译流返回错误，请重试或检查服务配置")
        when(protocol) {
            "chat"->{val choices=event.optJSONArray("choices")?:return;for(i in 0 until choices.length()) {
                val choice=choices.getJSONObject(i);if(choice.optInt("index",0)!=0)continue
                val content=choice.optJSONObject("delta")?.opt("content")
                if(content is String)append(content)
                else if(content is JSONArray)content.objects().filter{it.optString("type")=="text"}.forEach{append(it.optString("text"))}
                val finish=choice.opt("finish_reason");if(finish is String){require(finish=="stop"){"模型输出未完整结束：$finish"};terminal=true}
            }}
            "responses"->when(event.optString("type")) {
                "response.output_text.delta"->append(event.optString("delta"))
                "response.completed"->{require(event.optJSONObject("response")?.optString("status","completed")=="completed"){"模型输出不完整"};terminal=true}
                "response.failed","response.incomplete"->error("模型输出失败或被截断")
            }
            "gemini"->{val candidate=event.optJSONArray("candidates")?.optJSONObject(0)?:return
                candidate.optJSONObject("content")?.optJSONArray("parts")?.objects()?.filter{!it.optBoolean("thought")}?.forEach{append(it.optString("text"))}
                candidate.optString("finishReason").takeIf{it.isNotBlank()}?.let{require(it=="STOP"){"Gemini 输出未完成：$it"};terminal=true}
            }
            "anthropic"->when(event.optString("type")) {
                "content_block_delta"->event.optJSONObject("delta")?.let{if(it.optString("type")=="text_delta")append(it.optString("text"))}
                "message_delta"->event.optJSONObject("delta")?.optString("stop_reason")?.takeIf{it.isNotBlank()}?.let{require(it in listOf("end_turn","stop_sequence")){"Anthropic 输出未完成：$it"};terminal=true}
            }
        }
    }
    fun finish():String {require(terminal && text.isNotBlank()){"翻译流中断或没有完整译文"};update(text.toString());return text.toString()}
    fun complete(value:JSONObject):String {
        when(protocol) {
            "chat"->{val choice=value.getJSONArray("choices").getJSONObject(0);require(choice.optString("finish_reason")=="stop"){"模型输出不完整"};append(choice.getJSONObject("message").getString("content"))}
            "responses"->{require(value.optString("status")=="completed"){"模型输出不完整"};value.getJSONArray("output").objects().filter{it.optString("type")=="message"}.forEach{message->message.getJSONArray("content").objects().filter{it.optString("type")=="output_text"}.forEach{append(it.getString("text"))}}}
            "gemini"->{val candidate=value.getJSONArray("candidates").getJSONObject(0);require(candidate.optString("finishReason")=="STOP"){"模型输出不完整"};candidate.getJSONObject("content").getJSONArray("parts").objects().filter{!it.optBoolean("thought")}.forEach{append(it.optString("text"))}}
            "anthropic"->{require(value.optString("stop_reason") in listOf("end_turn","stop_sequence")){"模型输出不完整"};value.getJSONArray("content").objects().filter{it.optString("type")=="text"}.forEach{append(it.getString("text"))}}
        }
        terminal=true;return finish()
    }
}

internal fun reasoningMode(profile:JSONObject):String {
    val explicit=profile.optString("reasoningMode","auto")
    if(explicit!="auto")return explicit
    val protocol=profile.getString("protocol");val model=profile.optString("model").lowercase().removePrefix("models/")
    return when {
        protocol=="gemini" && Regex("gemini-[34]").containsMatchIn(model)->"gemini-level"
        protocol=="gemini" && model.contains("2.5")->"gemini-budget"
        protocol=="anthropic" && Regex("(opus|sonnet)[-_.]4[-_.][6-9]").containsMatchIn(model)->"claude-adaptive"
        protocol=="anthropic" && model.startsWith("claude-")->"claude-budget"
        protocol in listOf("chat","responses") && (model.startsWith("gpt-5") || Regex("^o[134]($|-)").containsMatchIn(model))->"effort"
        protocol=="chat" && model.contains("qwen3")->"qwen"
        protocol=="chat" && model.contains("deepseek")->"deepseek"
        else->"unknown"
    }
}
internal fun reasoningChoices(profile:JSONObject):List<Pair<String,String>> {
    val values=when(reasoningMode(profile)) {
        "qwen","deepseek"->listOf("off","on")
        "gemini-level"->listOf("minimal","low","medium","high")
        "gemini-budget"->listOf("off","low","medium","high","xhigh","max").filter{it!="off" || !profile.optString("model").contains("pro",true)}
        "claude-adaptive"->listOf("off","low","medium","high","max")
        else->listOf("off","minimal","low","medium","high","xhigh","max").filter{it!="minimal" || reasoningMode(profile) in listOf("effort","unknown")}
    }
    val labels=mapOf("auto" to "模型默认","off" to "关闭","on" to "开启","minimal" to "最低","low" to "低","medium" to "中","high" to "高","xhigh" to "更高","max" to "最高")
    return listOf("auto" to labels.getValue("auto"))+values.map{it to "$it · ${labels.getValue(it)}"}
}
