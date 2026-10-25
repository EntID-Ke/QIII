package io.github.qqmodulemanager

import kotlinx.coroutines.*
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONObject

/** Resolve QQ's actual cache at click time; PicElement paths may be empty or stale. */
internal class QQPictureAccess(private val loader:ClassLoader) {
    private fun get(value:Any,name:String):Any?=value.javaClass.getMethod(name).invoke(value)
    private fun readable(path:String?):String?=path?.takeIf{it.isNotBlank() && File(it).let{file->file.isFile && file.canRead() && file.length() in 1..20L*1024*1024}}
    private fun local(pic:Any):String? {
        val paths=loader.loadClass("com.tencent.mobileqq.aio.utils.aq")
        val type=loader.loadClass("com.tencent.qqnt.kernel.nativeinterface.PicElement")
        val original=paths.getMethod("a",type).invoke(null,pic) as? String
        readable(original)?.let{return it}
        readable(get(pic,"getSourcePath") as? String)?.let{return it}
        val thumb=paths.getMethod("c",type,Int::class.javaPrimitiveType)
        for(size in listOf(720,0,198))readable(thumb.invoke(null,pic,size) as? String)?.let{return it}
        return (get(pic,"getThumbPath") as? Map<*,*>)?.values?.filterIsInstance<String>()
            ?.mapNotNull(::readable)?.maxByOrNull{File(it).length()}
    }
    suspend fun prepare(picture:ChatPicture,timeoutMillis:Long=30_000,progress:(String)->Unit={}):String=withContext(Dispatchers.IO) {
        val pic=get(picture.element,"getPicElement")?:error("这条消息没有普通图片")
        check(get(pic,"getIsFlashPic")!=true){"不支持闪照识图"}
        val trace=ToolTrace.current.get()
        trace?.event("qq.picture.selected",JSONObject().put("md5",get(pic,"getMd5HexStr")))
        local(pic)?.let{trace?.event("qq.picture.cached",JSONObject().put("bytes",File(it).length()));return@withContext it}
        progress("正在从 QQ 获取图片…")
        val service=loader.loadClass("com.tencent.qqnt.msg.f").getMethod("f").invoke(null)?:error("QQ 图片服务尚未就绪，请重试")
        val api=loader.loadClass("com.tencent.qqnt.kernel.api.af")
        val requestType=loader.loadClass("com.tencent.qqnt.kernel.nativeinterface.RichMediaElementGetReq")
        val listenerType=loader.loadClass("com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener")
        val request=requestType.getDeclaredConstructor().newInstance()
        fun set(name:String,value:Any){requestType.getField(name).set(request,value)}
        val msgId=(get(picture.record,"getMsgId") as Number).toLong()
        val elementId=(get(picture.element,"getElementId") as Number).toLong()
        val chatType=(get(picture.record,"getChatType") as Number).toInt()
        val peer=get(picture.record,"getPeerUid")?.toString()?.takeIf{it.isNotBlank()}
            ?: (get(picture.record,"getPeerUin") as Number).toLong().takeIf{it>0}?.toString()
        check(msgId!=0L && elementId!=0L && !peer.isNullOrBlank()){ "QQ 图片消息标识尚未就绪，请重试" }
        set("msgId",msgId);set("elementId",elementId);set("chatType",chatType);set("peerUid",peer)
        // Same request as QQ's PIC_DOWNLOAD_BIG_THUMB; sufficient for recognition.
        set("downloadType",2);set("thumbSize",720)
        trace?.event("qq.picture.fetch",JSONObject().put("size",720))
        val completion=CompletableDeferred<String>()
        val listener=Proxy.newProxyInstance(loader,arrayOf(listenerType)){proxy,method,args->when(method.name) {
            "hashCode"->System.identityHashCode(proxy)
            "equals"->proxy===args?.firstOrNull()
            "toString"->"QIIIPictureListener"
            "onRichMediaDownloadComplete"->{
                try {
                    val info=args?.firstOrNull()
                    if(info!=null && (get(info,"getMsgId") as Number).toLong()==msgId && (get(info,"getMsgElementId") as Number).toLong()==elementId) {
                        val error=(get(info,"getFileErrCode") as Number).toLong()
                        val status=(get(info,"getTrasferStatus") as Number).toInt()
                        if(error!=0L || status in 5..6)completion.completeExceptionally(IllegalStateException("QQ 未能取回图片（错误码 $error），请检查网络或图片是否已过期"))
                        else if(status!=3) {
                            val path=readable(get(info,"getFilePath") as? String)?:local(pic)
                            if(path!=null){trace?.event("qq.picture.fetched",JSONObject().put("bytes",File(path).length()));completion.complete(path)}
                            else completion.completeExceptionally(IllegalStateException("QQ 返回的图片文件不可读取，请重试"))
                        }
                    }
                }catch(e:Exception){completion.completeExceptionally(e)}
                null
            }
            else->null // The kernel listener's other callbacks all return void.
        }}
        val remove=api.getMethod("removeMsgListener",listenerType)
        api.getMethod("addMsgListener",listenerType).invoke(service,listener)
        try {
            // A cache fill may finish between the first lookup and listener registration.
            local(pic)?.let{return@withContext it}
            api.getMethod("getRichMediaElement",requestType).invoke(service,request)
            withTimeoutOrNull(timeoutMillis){completion.await()}?:error("从 QQ 获取图片超时，请检查网络后重试")
        }finally{
            // Do not cancel QQ's transfer: the chat view may also be using it.
            remove.invoke(service,listener)
        }
    }
}
