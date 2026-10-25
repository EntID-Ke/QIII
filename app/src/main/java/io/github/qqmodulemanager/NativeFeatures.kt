package io.github.qqmodulemanager

import android.app.Activity
import android.content.*
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

internal val translationLanguages=linkedMapOf("zh-Hans" to "简体中文","zh-Hant" to "繁体中文","en" to "英语","ja" to "日语","ko" to "韩语","fr" to "法语","de" to "德语","es" to "西班牙语","ru" to "俄语","pt" to "葡萄牙语","it" to "意大利语","ar" to "阿拉伯语","hi" to "印地语","th" to "泰语","vi" to "越南语","id" to "印尼语","tr" to "土耳其语","uk" to "乌克兰语","nl" to "荷兰语","pl" to "波兰语")
internal data class ChatPicture(val record:Any,val element:Any)
internal data class ChatMessage(val account:String,val message:String,val text:String,val outgoing:Boolean,val pictures:List<ChatPicture>) {
    val source=digest(text)
    val key="$account/$message/$source"
    fun args(includeText:Boolean=false)=Bundle().apply{putString("account",account);putString("message",message);putString("source",source);if(includeText)putString("text",text)}
}
internal class QQMessageAccess(private val context:Context,loader:ClassLoader) {
    private val getters=java.util.concurrent.ConcurrentHashMap<String,Method>()
    private val mobile=loader.loadClass("mqq.app.MobileQQ")
    private val mobileInstance=mobile.getMethod("getMobileQQ")
    private val runtime=mobile.getMethod("peekAppRuntime")
    private val account=loader.loadClass("mqq.app.AppRuntime").getMethod("getCurrentAccountUin")
    private val faces=loader.loadClass("com.tencent.mobileqq.emoticon.QQSysFaceUtil")
    private val localFace=faces.getMethod("convertToLocal",Int::class.javaPrimitiveType)
    private val faceText=faces.getMethod("getFaceString",Int::class.javaPrimitiveType)
    var unavailableReason="";private set
    private fun unavailable(reason:String):ChatMessage?{unavailableReason=reason;return null}
    private fun get(value:Any,name:String):Any?=getters.getOrPut(value.javaClass.name+"#"+name){value.javaClass.getMethod(name)}.invoke(value)
    fun read(aio:Any):ChatMessage? {
        unavailableReason=""
        // QQ's manifest Application is an RFix proxy; the live MobileQQ is its delegate.
        val app=mobileInstance.invoke(null)?:context.applicationContext?.takeIf{mobile.isInstance(it)}?:return unavailable("QQ 真实 Application 尚未创建")
        val current=runtime.invoke(app)?:return unavailable("QQ 账号运行时尚未就绪")
        val uin=account.invoke(current)?.toString()?.takeIf{it.isNotBlank() && it!="0"}?:return unavailable("QQ 账号尚未登录")
        val record=get(aio,"getMsgRecord")?:return null
        val chat=(get(record,"getChatType") as Number).toInt();if(chat !in 1..2)return unavailable("当前会话类型不在本版范围")
        val msgId=(get(record,"getMsgId") as Number).toLong();if(msgId==0L)return unavailable("当前消息尚无稳定 ID")
        val peer=get(record,"getPeerUid")?.toString()?.takeIf{it.isNotBlank()}
            ?: (get(record,"getPeerUin") as Number).toLong().takeIf{it>0}?.toString()
            ?:return unavailable("当前消息会话标识尚未就绪")
        val text=StringBuilder();val pictures=ArrayList<ChatPicture>()
        for(element in get(record,"getElements") as Iterable<*>) {
            if(element==null)continue
            when((get(element,"getElementType") as Number).toInt()) {
                1->get(element,"getTextElement")?.let{get(it,"getContent")?.let{text.append(it)}}
                6->get(element,"getFaceElement")?.let{face->
                    val index=(get(face,"getFaceIndex") as Number).toInt()
                    text.append(faceText.invoke(null,localFace.invoke(null,index))?.toString()?.takeIf{it.isNotEmpty()}?:get(face,"getFaceText")?.toString().orEmpty())
                }
                2->get(element,"getPicElement")?.let{pic->
                    if(get(pic,"getIsFlashPic")==true)return@let
                    pictures.add(ChatPicture(record,element))
                }
            }
        }
        return ChatMessage(uin,digest("$chat\u0000$peer\u0000$msgId"),text.toString(),get(aio,"isSelf")==true,pictures)
    }
}

/** QQ 9.3.75 native menu items and native message rows; no dependency on the other modules. */
class NativeFeatures(private val context:Context,private val loader:ClassLoader,private val coordinator:HostCoordinator) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val connection=FeatureConnection(context)
    private val access=QQMessageAccess(context,loader)
    private val pictures by lazy{QQPictureAccess(loader)}
    private val model=loader.loadClass("com.tencent.qqnt.aio.menu.ui.c").getDeclaredField("a").apply{isAccessible=true}
    private val base=loader.loadClass("com.tencent.qqnt.aio.menu.ui.e")
    private val item=loader.loadClass("com.tencent.qqnt.aio.menu.h")
    private val aio=loader.loadClass("com.tencent.mobileqq.aio.msg.AIOMsgItem")
    private val constructor=item.getDeclaredConstructor(Context::class.java,aio)
    private val getMessage=base.getMethod("d")
    private val holder=loader.loadClass("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB")
    private val rowMessage=holder.getMethod("Z1")
    private val rowRoot=holder.getMethod("a2")
    private val rowContent=holder.getMethod("Y1")
    private val bubble=loader.loadClass("com.tencent.mobileqq.aio.msglist.holder.component.c").getMethod("T1")
    private val state=holder.getDeclaredMethod("handleUIState",loader.loadClass("com.tencent.mvi.base.mvi.MviUIState"))
    private val render=loader.loadClass("com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout").getDeclaredMethod("r",Int::class.javaPrimitiveType,base,Boolean::class.javaPrimitiveType,FloatArray::class.java)
    private data class Action(val kind:String,val title:String,val id:Int,val message:ChatMessage,val context:WeakReference<Context>)
    private val actions=Collections.synchronizedMap(WeakHashMap<Any,Action>())
    private data class Row(val message:ChatMessage,val root:WeakReference<ViewGroup>,val bubble:WeakReference<View>,val placement:TranslationPlacement,var card:WeakReference<TranslationCard> = WeakReference(null))
    private data class Result(var value:JSONObject?=null,var busy:Boolean=false,var error:String="",var expanded:Boolean=true,var provider:String?=null,var target:String?=null,var complete:Boolean=false,var failure:Throwable?=null)
    private val rows=WeakHashMap<ViewGroup,Row>()
    private val results=object:LinkedHashMap<String,Result>(32,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Result>):Boolean {
        if(size<=256)return false
        if(!eldest.value.busy)return true
        entries.firstOrNull{!it.value.busy}?.key?.let{remove(it)};return false
    }}
    private val lookups=HashSet<String>()
    private val requests=HashMap<String,Job>()
    private var visibleTranslation:Job?=null
    private var generation=0
    private var lastEnabled=true
    private var rowFailureReported=false
    private val unhooks=ArrayList<XC_MethodHook.Unhook>()
    private var observerRegistered=false
    private val invalidationObserver=object:ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange:Boolean,uri:Uri?) {
            val account=uri?.getQueryParameter("account")?:"*";val message=uri?.getQueryParameter("message")?:"*"
            fun matches(key:String)=key.split('/').let{(account=="*" || it[0]==account) && (message=="*" || it[1]==message)}
            generation++;requests.keys.filter(::matches).forEach{requests.remove(it)?.cancel()};results.keys.removeAll(::matches);lookups.clear()
            rows.values.toList().forEach{row->if(matches(row.message.key)){row.placement.detach();row.card.clear()};lookup(row.message)}
        }
    }
    private fun hook(method:Method,callback:XC_MethodHook){unhooks.add(XposedBridge.hookMethod(method,callback))}
    private fun observe() {
        if(!observerRegistered)try{context.contentResolver.registerContentObserver(FEATURE_URI,false,invalidationObserver);observerRegistered=true;invalidationObserver.onChange(false,null)}
        catch(_:RuntimeException){/* The provider can be cold. Retry on the next coordinator refresh. */}
    }
    init {
        try {
        HostFeatures.install(context,coordinator)
        for((method,value) in listOf("h" to "title","c" to "id","k" to "click","g" to "class","f" to "subtitle")) {
            hook(item.getDeclaredMethod(method),object:XC_MethodHook(){override fun beforeHookedMethod(param:MethodHookParam){
                val action=actions[param.thisObject]?:return
                when(value){"title"->param.result=action.title;"id"->param.result=action.id;"class"->param.result="QIII";"subtitle"->param.result=null
                    "click"->{param.result=null;action.context.get()?.let{host->try{when(action.kind){"translation"->translate(action.message,host);"translation_visible"->translateVisible(host);else->search(action.message,host)}}catch(e:Exception){toast(host,e.message?:"当前功能不可用")}}}}
            }})
        }
        hook(item.getDeclaredMethod("b"),object:XC_MethodHook(){override fun beforeHookedMethod(param:MethodHookParam){
            val action=actions[param.thisObject]?:return
            if(action.kind=="lens")context.resources.getIdentifier("qui_ai_recognition","drawable","com.tencent.mobileqq").takeIf{it!=0}?.let{param.result=it}
        }})
        hook(render,object:XC_MethodHook(){override fun afterHookedMethod(param:MethodHookParam){
            val action=actions[param.args[1]]?:return
            if(action.kind.startsWith("translation"))try {
                val root=param.result as? ViewGroup?:return
                val image=root.getChildAt(0) as? ImageView?:return
                val resources=context.createPackageContext(FeatureWire.PACKAGE,0).resources
                val drawable=resources.getDrawable(R.drawable.ic_translate,null).mutate()
                drawable.setTint((root.getChildAt(1) as? TextView)?.currentTextColor?:android.graphics.Color.WHITE)
                image.setImageDrawable(drawable);image.layoutParams=image.layoutParams.apply{if(height>0)width=height}
            }catch(_:Exception){coordinator.adapterFailed("translationIcon",IllegalStateException("Icon unavailable"))}
        }})
        hook(state,object:XC_MethodHook(){override fun afterHookedMethod(param:MethodHookParam){
            try{bind(param.thisObject)}catch(e:Exception){if(!rowFailureReported){rowFailureReported=true;coordinator.adapterFailed("translation",e)}}
        }})
        observe()
        coordinator.setNativeChangeListener {
            Handler(Looper.getMainLooper()).post {
                observe()
                val enabled=coordinator.nativeEnabled("translation")
                if(enabled!=lastEnabled){lastEnabled=enabled;if(!enabled){visibleTranslation?.cancel();requests.values.toList().forEach{it.cancel()};rows.values.toList().forEach{it.placement.detach()}}else rows.values.toList().forEach{draw(it)}}
            }
        }
        }catch(error:Throwable){unhooks.forEach{runCatching{it.unhook()}};if(observerRegistered)context.contentResolver.unregisterContentObserver(invalidationObserver);connection.close();scope.cancel();throw error}
    }
    @Suppress("UNCHECKED_CAST") fun append(menu:Any,host:Context) {
        if(!coordinator.nativeEnabled("translation") && !coordinator.nativeEnabled("lens")){coordinator.nativeMenuState("翻译和识图均已关闭");return}
        val items=model.get(menu) as MutableList<Any>
        val original=items.firstOrNull()?:run{coordinator.nativeMenuState("QQ 返回空菜单");return};val msg=getMessage.invoke(original)?:return
        val message=access.read(msg)?:run{coordinator.nativeMenuState("未注入："+access.unavailableReason);return}
        val working=java.util.concurrent.CopyOnWriteArrayList(items)
        val addedTitles=ArrayList<String>()
        for(kind in listOf("translation","translation_visible","lens")) {
            if(!coordinator.nativeEnabled(if(kind=="translation_visible")"translation" else kind) || working.any{actions[it]?.kind==kind})continue
            if(kind=="translation" && message.text.isBlank() || kind=="lens" && message.pictures.isEmpty())continue
            val added=constructor.newInstance(host,msg);val title=when(kind){"translation"->"翻译";"translation_visible"->"一键翻译";else->"Google 识图"}
            actions[added]=Action(kind,title,View.generateViewId(),message,WeakReference(host))
            coordinator.tagNative(added,kind,title);working.add(added)
            addedTitles.add(title)
        }
        model.set(menu,working)
        coordinator.nativeMenuState(if(addedTitles.isEmpty())"此消息无可翻译文字或普通图片，或项目已存在" else "已注入："+addedTitles.joinToString("、"))
    }
    private fun bind(holder:Any) {
        val root=rowRoot.invoke(holder) as? ViewGroup?:return
        val aio=rowMessage.invoke(holder)
        val message=aio?.let{access.read(it)}
        val old=rows[root]
        if(message==null || message.text.isBlank() || !coordinator.nativeEnabled("translation")){old?.placement?.detach();rows.remove(root);return}
        val content=rowContent.invoke(holder)?:return
        val bubble=bubble.invoke(content) as? View?:return
        if(bubble.parent!==root){old?.placement?.detach();rows.remove(root);if(!rowFailureReported){rowFailureReported=true;coordinator.adapterFailed("translation",IllegalStateException("Unsupported bubble parent"))};return}
        if(old!=null && old.message.key==message.key && old.bubble.get()===bubble){draw(old);lookup(message);return}
        if(old!=null && old.message.account==message.account && old.message.message==message.message){requests.remove(old.message.key)?.cancel();results.remove(old.message.key)}
        old?.placement?.detach()
        val row=Row(message,WeakReference(root),WeakReference(bubble),TranslationPlacement(root,bubble,message.outgoing));rows[root]=row
        draw(row)
        lookup(message)
    }
    private fun lookup(message:ChatMessage) {
        if(results.containsKey(message.key) || !lookups.add(message.key))return
        val epoch=generation
        scope.launch {
            try {
                val cached=connection.call("lookup",args=message.args()).optJSONObject("result")
                if(epoch==generation && !results.containsKey(message.key)){results[message.key]=Result(cached,expanded=cached?.optBoolean("expanded",true)?:true,complete=cached!=null);redraw(message.key)}
            }catch(e:CancellationException){throw e}catch(_:Exception){}finally{if(epoch==generation)lookups.remove(message.key)}
        }
    }
    private fun redraw(key:String){rows.values.toList().filter{it.message.key==key}.forEach{draw(it)}}
    private fun draw(row:Row) {
        try {
        val result=results[row.message.key]
        if(result==null || !result.expanded || !coordinator.nativeEnabled("translation") || (!result.complete && !result.busy && result.error.isEmpty())){row.placement.detach();row.card.clear();return}
        val root=row.root.get()?:return
        val card=row.card.get()?:TranslationCard(root.context){showActions(row.message,it.context)}.also{row.card=WeakReference(it)}
        val value=result.value
        card.show(if(result.error.isNotBlank())result.error else value?.optString("text").orEmpty(),when {
            result.busy->"正在翻译… · 点击操作"
            result.error.isNotBlank()->"翻译失败 · 点击重试或更换服务"
            else->"✓ 由 ${value?.optString("providerName")} 提供翻译支持  ⋯"
        },result.busy)
        row.placement.attach(card)
        }catch(e:Exception){runCatching{row.placement.detach()};if(!rowFailureReported){rowFailureReported=true;coordinator.adapterFailed("translation",e)}}
    }
    private fun activity(context:Context)=generateSequence(context){(it as? ContextWrapper)?.baseContext}.filterIsInstance<Activity>().firstOrNull()?.takeUnless{it.isFinishing || it.isDestroyed}
    private fun toast(context:Context,message:String){Toast.makeText(context,message,Toast.LENGTH_LONG).show()}
    private fun translate(message:ChatMessage,host:Context,provider:String?=null,target:String?=null,force:Boolean=false,fromVisibleBatch:Boolean=false) {
        if(!fromVisibleBatch && rows.values.none{it.message.key==message.key && it.root.get()!=null}){toast(host,"当前消息无法展开译文，请查看QIII运行状态");return}
        val old=results[message.key]
        if(!force && old?.complete==true){old.expanded=true;redraw(message.key);scope.launch{runCatching{connection.call("expand",args=message.args().apply{putBoolean("expanded",true)})}};return}
        requests.remove(message.key)?.cancel()
        val result=Result(busy=true,provider=provider,target=target);results[message.key]=result;redraw(message.key)
        requests[message.key]=scope.launch(start=CoroutineStart.LAZY) {
            try {
                if(!force) {
                    val cached=connection.call("lookup",args=message.args()).optJSONObject("result")
                    if(cached!=null && results[message.key]===result){
                        result.value=cached;result.complete=true;result.busy=false;redraw(message.key)
                        connection.call("expand",args=message.args().apply{putBoolean("expanded",true)});return@launch
                    }
                }
                val value=connection.call("translate",args=message.args(true).apply{putString("provider",provider);putString("target",target)}) { partial->
                    scope.launch{if(result.busy && results[message.key]===result){result.value=partial;redraw(message.key)}}
                }
                if(results[message.key]===result){result.value=value;result.complete=true;result.busy=false;redraw(message.key)}
            }catch(e:CancellationException){result.busy=false;throw e}
            catch(e:Exception){if(results[message.key]===result){result.error=e.message?:"翻译失败";result.failure=e;result.busy=false;redraw(message.key)}}
            finally{if(results[message.key]===result)requests.remove(message.key)}
        }.also{it.start()}
    }
    private fun translateVisible(host:Context) {
        val activity=activity(host)?:return
        if(!coordinator.nativeEnabled("translation"))return
        if(visibleTranslation?.isActive==true){toast(host,"正在翻译当前屏幕的消息，请稍候");return}
        val messages=visibleChatMessages(rows.values.mapNotNull{row->row.bubble.get()?.let{row.message to it}},activity.window.decorView)
        if(messages.isEmpty()){toast(host,"当前屏幕没有可翻译的文字消息");return}
        val epoch=generation
        toast(host,"开始翻译当前屏幕的 ${messages.size} 条消息")
        visibleTranslation=scope.launch {
            for(batch in messages.chunked(3)) {
                if(epoch!=generation || !coordinator.nativeEnabled("translation") || activity.isFinishing || activity.isDestroyed)break
                batch.map{message->launch {
                    if(results[message.key]?.busy!=true){translate(message,activity,fromVisibleBatch=true);requests[message.key]?.join()}
                }}.joinAll()
            }
        }
    }
    private fun showActions(message:ChatMessage,host:Context) {
        val activity=activity(host)?:return;val result=results[message.key]?:return
        val actions=ArrayList<Pair<String,()->Unit>>()
        result.failure?.let{failure->actions.add("查看错误详情" to {ToolDialog.error(activity,"翻译失败",failure){translate(message,activity,result.provider,result.target,true)}})}
        actions.add("复制译文" to {val text=result.value?.optString("text").orEmpty();if(text.isNotEmpty())(host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("译文",text))})
        actions.add("收起译文" to {requests.remove(message.key)?.cancel();result.expanded=false;redraw(message.key);scope.launch{runCatching{connection.call("expand",args=message.args().apply{putBoolean("expanded",false)})}};Unit})
        actions.add("重新翻译" to {translate(message,activity,result.provider?:result.value?.optString("provider"),result.target?:result.value?.optString("target"),true)})
        actions.add("更换翻译服务" to {scope.launch{try {
            val settings=connection.call("settings").getJSONObject("settings")
            val providers=listOf("microsoft" to "微软","google" to "Google")+settings.getJSONArray("providers").objects().map{it.getString("id") to it.getString("name")}
            if(!activity.isDestroyed)ToolDialog.actions(activity,"本条消息使用的服务",providers.map{(id,title)->title to {translate(message,activity,id,result.target?:result.value?.optString("target"),true)}})
        }catch(e:CancellationException){throw e}catch(e:Exception){ToolDialog.error(activity,"读取服务失败",e)}};Unit})
        actions.add("更换目标语言" to {ToolDialog.actions(activity,"本条消息的目标语言",translationLanguages.map{(id,title)->title to {translate(message,activity,result.provider?:result.value?.optString("provider"),id,true)}})})
        actions.add("删除本条译文" to {scope.launch{runCatching{connection.call("clear",args=message.args())}.onFailure{ToolDialog.error(activity,"清理失败",it)}};Unit})
        ToolDialog.actions(activity,"消息翻译",actions)
    }
    private fun search(message:ChatMessage,host:Context) {
        val activity=activity(host)?:return
        fun upload(picture:ChatPicture) {
            val state=mutableStateOf(JSONObject().put("message","正在准备所选图片…"))
            val started=SystemClock.elapsedRealtime();var job:Job?=null
            val dialog=ToolDialog.show(activity,"Google 识图",onDismiss={job?.cancel()}){dismiss->LensProgressContent(state.value,started,dismiss)}?:return
            fun progress(value:JSONObject){scope.launch{if(dialog.isShowing)state.value=state.value.copy().apply{value.keys().forEach{put(it,value.get(it))}}}}
            val trace=DiagnosticLog.get(context).trace("lens")
            job=scope.launch(start=CoroutineStart.LAZY) {
                try {trace.run {
                    trace.event("qq.picture.prepare")
                    val path=pictures.prepare(picture){value->progress(JSONObject().put("message",value))}
                    val result=withContext(Dispatchers.IO){ParcelFileDescriptor.open(File(path),ParcelFileDescriptor.MODE_READ_ONLY).use{descriptor->
                        connection.call("lens",args=Bundle().apply{putParcelable("image",descriptor);putString("requestId",trace.id)},progress=::progress)
                    }}
                    if(activity.isFinishing || activity.isDestroyed)return@run
                    trace.event("browser.open")
                    activity.startActivity(BrowserLaunch.intent(activity,result.getString("url")))
                }}catch(e:CancellationException){trace.event("cancelled");throw e}
                catch(e:Exception){trace.event("failed",level="ERROR",error=e);if(!activity.isDestroyed)ToolDialog.error(activity,"识图失败",if(e is ToolFailure)e else ToolFailure(trace.id,e.message?:"识图失败",e)){upload(picture)}}
                finally{if(dialog.isShowing)dialog.dismiss()}
            }
            job!!.start()
        }
        if(message.pictures.size==1)upload(message.pictures.single())
        else ToolDialog.actions(activity,"选择要识图的图片",message.pictures.mapIndexed{i,picture->"图片 ${i+1}" to {upload(picture)}})
    }
}
