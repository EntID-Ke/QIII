package io.github.qqmodulemanager

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Short-lived bindings keep a cold manager available while requests are in flight. */
internal class FeatureConnection(context:Context):Closeable {
    private val caller=context.applicationContext?:context
    private val main=Handler(Looper.getMainLooper())
    private var ready=CompletableDeferred<IBinder>()
    private var bound=false
    private var closed=false
    private val pending=ConcurrentHashMap<String,CompletableDeferred<JSONObject>>()
    private val idle=Runnable{if(pending.isEmpty())unbind()}
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder){ready.complete(binder)}
        override fun onServiceDisconnected(name:ComponentName){fail("QIII服务已断开，请重试")}
        override fun onBindingDied(name:ComponentName){fail("QIII已更新，请重试")}
        override fun onNullBinding(name:ComponentName){fail("QIII服务不可用")}
    }
    private fun fail(message:String) {
        pending.values.forEach{it.completeExceptionally(IllegalStateException(message))}
        if(!ready.isCompleted)ready.completeExceptionally(IllegalStateException(message))
        unbind()
    }
    private fun unbind(){if(bound){runCatching{caller.unbindService(connection)};bound=false};ready=CompletableDeferred()}
    suspend fun call(op:String,document:JSONObject?=null,args:Bundle=Bundle(),progress:(JSONObject)->Unit={}):JSONObject {
        check(!closed){"QIII连接已关闭"}
        val request=Bundle(args).apply{putString("requestId",args.getString("requestId")?:ToolTrace.current.get()?.id?:UUID.randomUUID().toString());if(document!=null)putString("document",document.toString())}
        if(caller.packageName=="com.tencent.mobileqq")return withContext(Dispatchers.IO){HostFeatures.get(caller).endpoint.call(op,request,progress)}
        val host=withContext(Dispatchers.IO){runCatching{caller.contentResolver.call(PolicyStore.URI,"tools",null,null)?.getBinder("tools")}.getOrNull()}
        val binder=host?:try{withContext(Dispatchers.Main.immediate) {
            check(!closed){"QIII连接已关闭"};main.removeCallbacks(idle)
            if(!bound) {
                bound=caller.bindService(Intent().setClassName(FeatureWire.PACKAGE,"${FeatureWire.PACKAGE}.FeatureService"),connection,Context.BIND_AUTO_CREATE)
                check(bound){"无法启动QIII服务，请检查模块是否安装"}
            }
            withTimeout(10000){ready.await()}
        }}catch(error:Exception){main.post{if(pending.isEmpty())unbind()};throw error}
        val id=request.getString("requestId")!!;val result=CompletableDeferred<JSONObject>()
        val executorUid=caller.packageManager.getApplicationInfo(if(host!=null)"com.tencent.mobileqq" else FeatureWire.PACKAGE,0).uid
        val callback=object:Binder() {
            override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                if(code!=FeatureWire.CODE)return super.onTransact(code,data,reply,flags)
                data.enforceInterface(FeatureWire.CALLBACK)
                if(Binder.getCallingUid()!=executorUid)throw SecurityException("Tool executor callback required")
                val state=data.readString();val value=JSONObject(data.readString()?:"{}")
                if(!result.isCompleted)when(state){"progress"->progress(value);"done"->result.complete(value);"error"->result.completeExceptionally(ToolFailure(value.optString("requestId"),value.optString("message","请求失败"),IllegalStateException("远程执行器错误，完整堆栈见诊断日志")))}
                return true
            }
        }
        pending[id]=result
        try {
            withContext(Dispatchers.IO){FeatureWire.send(binder,op,request,callback)}
            return withTimeout(190000){result.await()}
        }finally {
            result.cancel();pending.remove(id)
            withContext(NonCancellable+Dispatchers.IO){runCatching{FeatureWire.send(binder,"cancel",Bundle().apply{putString("requestId",UUID.randomUUID().toString());putString("cancelId",id)})}}
            main.post{if(pending.isEmpty())main.postDelayed(idle,30000)}
        }
    }
    override fun close(){closed=true;main.removeCallbacks(idle);main.post{fail("页面已关闭")}}
}
