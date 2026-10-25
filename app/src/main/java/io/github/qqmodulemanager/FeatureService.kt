package io.github.qqmodulemanager

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A separate, UID-authorized protocol. The three module adapters continue using bridge v2. */
internal object FeatureWire {
    const val PACKAGE="io.github.qqmodulemanager"
    const val DESCRIPTOR="io.github.qqmodulemanager.features.v1"
    const val CALLBACK="io.github.qqmodulemanager.features.callback.v1"
    const val CODE=IBinder.FIRST_CALL_TRANSACTION
    fun send(remote:IBinder,op:String,args:Bundle,callback:IBinder?=null) {
        val data=Parcel.obtain();val reply=Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR);data.writeString(op);data.writeBundle(args);data.writeStrongBinder(callback)
            check(remote.transact(CODE,data,reply,0)){"QIII接口版本不匹配，请重启 QQ"};reply.readException()
        }finally{data.recycle();reply.recycle()}
    }
    fun callback(remote:IBinder,state:String,value:JSONObject) {
        val data=Parcel.obtain()
        try{data.writeInterfaceToken(CALLBACK);data.writeString(state);data.writeString(value.toString());remote.transact(CODE,data,null,IBinder.FLAG_ONEWAY)}finally{data.recycle()}
    }
}

/** Used by standalone settings when QQ is absent; chat actions always use HostFeatures. */
class FeatureService:Service() {
    private lateinit var runtime:FeatureRuntime
    private lateinit var endpoint:FeatureEndpoint
    override fun onCreate(){super.onCreate();runtime=FeatureRuntime(this,TranslationStore(this));endpoint=FeatureEndpoint(this,runtime)}
    override fun onBind(intent:Intent):IBinder=endpoint
    override fun onDestroy(){endpoint.close();runtime.close();super.onDestroy()}
}

internal class FeatureEndpoint(private val context:Context,private val runtime:FeatureRuntime,
    private val prepare:suspend(String)->Unit={}):Binder(),java.io.Closeable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val concurrency=Semaphore(4)
    private val jobs=ConcurrentHashMap<String,Work>()
    private val queued=ConcurrentHashMap<String,Work>()
    private data class Work(val job:Job,val operation:String,val account:String?,val message:String?)
    private fun authorize():Int {
        val uid=Binder.getCallingUid()
        if(uid==context.applicationInfo.uid)return uid
        val allowed=if(context.packageName=="com.tencent.mobileqq")FeatureWire.PACKAGE else "com.tencent.mobileqq"
        val peerUid=try{context.packageManager.getApplicationInfo(allowed,0).uid}catch(_:android.content.pm.PackageManager.NameNotFoundException){-1}
        if(uid==peerUid)return uid
        throw SecurityException("QIII: feature caller rejected")
    }
    suspend fun call(op:String,args:Bundle,progress:(JSONObject)->Unit={},callerUid:Int=android.os.Process.myUid()):JSONObject {
        val id="$callerUid:${args.getString("requestId")?:error("Missing request ID")}"
        val work=Work(currentCoroutineContext().job,op,args.getString("account"),args.getString("message"))
        check(jobs.putIfAbsent(id,work)==null){"Duplicate request ID"}
        try {
            if(op=="clear")(jobs.values+queued.values).filter{it.operation=="translate" && (work.account==null || it.account==work.account) && (work.message==null || it.message==work.message)}.forEach{it.job.cancel()}
            prepare(op)
            return if(op in listOf("translate","test","models","lens","analyzeDiagnostics"))concurrency.withPermit{runtime.perform(op,args,progress)} else runtime.perform(op,args,progress)
        }finally{jobs.remove(id,work)}
    }
    override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
        if(code!=FeatureWire.CODE)return super.onTransact(code,data,reply,flags)
        data.enforceInterface(FeatureWire.DESCRIPTOR)
        val uid=authorize();val operation=data.readString()?:error("Missing operation")
        val args=data.readBundle(FeatureService::class.java.classLoader)?:Bundle()
        val callback=data.readStrongBinder()
        if(operation=="cancel"){val id="$uid:${args.getString("cancelId")}";jobs[id]?.job?.cancel();queued[id]?.job?.cancel();reply?.writeNoException();return true}
        require(callback!=null){"Callback required"}
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try{val result=call(operation,args,{FeatureWire.callback(callback,"progress",it)},uid);FeatureWire.callback(callback,"done",result)}
            catch(e:CancellationException){runCatching{FeatureWire.callback(callback,"error",JSONObject().put("message","请求已取消"))};throw e}
            catch(e:Exception){runCatching{FeatureWire.callback(callback,"error",JSONObject().put("message",e.message?:e.javaClass.simpleName).put("requestId",(e as? ToolFailure)?.requestId.orEmpty()))}}
        }
        val id="$uid:${args.getString("requestId")?:error("Missing request ID")}";val work=Work(job,operation,args.getString("account"),args.getString("message"))
        if(queued.putIfAbsent(id,work)!=null){job.cancel();error("Duplicate request ID")}
        val death=IBinder.DeathRecipient{job.cancel()}
        try{callback.linkToDeath(death,0)}catch(_:RemoteException){job.cancel()}
        job.invokeOnCompletion {
            queued.remove(id,work)
            runCatching{callback.unlinkToDeath(death,0)}
            @Suppress("DEPRECATION") val file=args.getParcelable<ParcelFileDescriptor>("image");runCatching{file?.close()}
        }
        job.start();reply?.writeNoException();return true
    }
    override fun close(){scope.cancel()}
}
