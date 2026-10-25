package io.github.qqmodulemanager

import android.content.Context
import android.database.ContentObserver
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray

/** Lives in QQ; the manager is a configuration editor, never the chat request executor. */
internal class HostFeatures private constructor(private val context:Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val store=TranslationStore(context,CredentialVault(context,namespace="qqmm-"),"qqmm-")
    private val sync=Mutex()
    private val preferences=context.getSharedPreferences("qqmm-feature-sync",0)
    private val log=DiagnosticLog.get(context)
    private var observed=false
    private val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange:Boolean){scope.launch{try{synchronize()}catch(e:Exception){log.event("WARN","configuration","refresh-failed",error=e)}}}
    }
    private fun observe(){if(!observed)try{context.contentResolver.registerContentObserver(TRANSLATION_SETTINGS_URI,false,observer);observed=true}catch(e:RuntimeException){log.event("WARN","configuration","observer-unavailable",error=e)}}
    private val runtime=FeatureRuntime(context,store,saveConfiguration={op,document->
        synchronize(if(op=="saveProvider")"toolSaveProvider" else "toolSaveSettings",Bundle().apply{putString("document",document.toString())})
    },clearCaches={args->clearLocal(args)})
    val endpoint=FeatureEndpoint(context,runtime){op->
        if(op=="settings")observe()
        if(op in listOf("translate","settings","models","test","analyzeDiagnostics") && !store.hasImportedConfiguration()) {
            try{synchronize()}catch(e:Exception){log.event("ERROR","configuration","first-sync",error=e);throw IllegalStateException("首次同步翻译配置失败，请重试并查看诊断日志",e)}
        }
    }
    private suspend fun synchronize(operation:String="toolConfig",args:Bundle?=null)=sync.withLock {
        withContext(Dispatchers.IO) {
            var pendingResponse:Bundle?=null
            val pending=synchronized(preferences){JSONArray(preferences.getString("pendingClears","[]")).objects()}
            for(command in pending) {
                pendingResponse=context.contentResolver.call(PolicyStore.URI,"toolClear",null,Bundle().apply{
                    putString("commandId",command.getString("id"));if(!command.isNull("account"))putString("account",command.getString("account"));if(!command.isNull("message"))putString("message",command.getString("message"))
                })?:error("清理记录同步失败")
                synchronized(preferences){val remaining=JSONArray(preferences.getString("pendingClears","[]")).objects().filter{it.getString("id")!=command.getString("id")};check(preferences.edit().putString("pendingClears",JSONArray(remaining).toString()).commit())}
            }
            val response=(if(operation=="toolConfig")pendingResponse else null)?:context.contentResolver.call(PolicyStore.URI,operation,null,args)?:error("QIII配置接口暂不可用")
            val snapshot=JSONObject(response.getString("snapshot")?:error("配置快照为空"))
            store.importConfiguration(snapshot)
            val commands=snapshot.getJSONArray("clears").objects();var applied=preferences.getLong("clearRevision",0)
            val own=JSONArray(preferences.getString("ownClears","[]")).objects().map{it.getString("id")}.toSet()
            if(commands.isNotEmpty() && applied>0 && applied<commands.first().getLong("revision")-1)store.clear(null,null)
            for(command in commands)if(command.getLong("revision")>applied) {
                if(command.optString("id") !in own)store.clear(command.optString("account").takeUnless{command.isNull("account")},command.optString("message").takeUnless{command.isNull("message")})
                applied=command.getLong("revision")
            }
            preferences.edit().putLong("clearRevision",applied).apply()
            log.event("INFO","configuration","synced",fields=JSONObject().put("executor","com.tencent.mobileqq").put("clears",commands.size))
        }
    }
    private suspend fun clearLocal(args:Bundle)=withContext(Dispatchers.IO) {
        val command=JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("account",args.getString("account")?:JSONObject.NULL).put("message",args.getString("message")?:JSONObject.NULL)
        synchronized(preferences){val pending=JSONArray(preferences.getString("pendingClears","[]")).put(command);check(preferences.edit().putString("pendingClears",pending.toString()).commit())}
        store.clear(args.getString("account"),args.getString("message"))
        synchronized(preferences){val own=JSONArray(preferences.getString("ownClears","[]")).objects()+command;check(preferences.edit().putString("ownClears",JSONArray(own.takeLast(256)).toString()).commit())}
        scope.launch{try{synchronize()}catch(e:Exception){log.event("WARN","cache","clear-sync-pending",error=e)}};Unit
    }
    private suspend fun migrateCache() {
        if(preferences.getBoolean("cacheMigrated",false))return
        withContext(Dispatchers.IO) {
            val epoch=store.generation
            val response=context.contentResolver.call(PolicyStore.URI,"toolCacheExport",null,null)?:return@withContext
            @Suppress("DEPRECATION") val descriptor=response.getParcelable<ParcelFileDescriptor>("cache")?:return@withContext
            var count=0;var complete=false
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use{reader->reader.lineSequence().forEach{line->
                currentCoroutineContext().ensureActive();val row=JSONObject(line)
                if(row.optBoolean("complete"))complete=true else {store.importCached(row,epoch);count++}
            }}
            check(complete){"旧版译文导出中断，将在下次启动重试"}
            preferences.edit().putBoolean("cacheMigrated",true).apply()
            log.event("INFO","cache","migrated",fields=JSONObject().put("records",count))
        }
    }
    init {
        observe()
        scope.launch{try{synchronize();migrateCache()}catch(e:Exception){log.event("WARN","configuration","startup-sync-failed",error=e)}}
        log.event("INFO","runtime","QQ-executor-ready")
    }
    companion object {
        @Volatile private var instance:HostFeatures?=null
        fun get(context:Context):HostFeatures=instance?:synchronized(this){instance?:HostFeatures(context.applicationContext?:context).also{instance=it}}
        fun install(context:Context,coordinator:HostCoordinator){coordinator.toolsReady(get(context).endpoint)}
    }
}
