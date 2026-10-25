package io.github.qqmodulemanager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONObject

internal data class ManagerSnapshot(val policy:JSONObject=JSONObject(),val runtime:JSONObject=JSONObject(),val catalog:JSONObject=JSONObject())

/** Both the launcher and QQ's embedded page use the same provider-owned configuration. */
internal class ManagerRepository(private val context:Context) {
    private val writes=Mutex()
    private val caller=context.applicationContext?:context
    private var nextBootstrapAt=0L
    private suspend fun call(method:String,args:Bundle?=null):Bundle {
        fun request()=caller.contentResolver.call(PolicyStore.URI,method,null,args)?:error("Manager provider unavailable")
        try{return request()}catch(first:IllegalArgumentException) {
            if(caller.packageName!="com.tencent.mobileqq")throw first
            if(SystemClock.uptimeMillis()>=nextBootstrapAt) {
                nextBootstrapAt=SystemClock.uptimeMillis()+3000
                caller.startActivity(Intent().setClassName("io.github.qqmodulemanager","io.github.qqmodulemanager.BridgeBootstrapActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
            repeat(30){delay(100);try{return request()}catch(_:IllegalArgumentException){}}
            throw first
        }
    }
    suspend fun read():ManagerSnapshot {
        val result=call("snapshot")
        return ManagerSnapshot(JSONObject(result.getString("policy","{}")),JSONObject(result.getString("runtime","{}")),JSONObject(result.getString("catalog","{}")))
    }
    suspend fun update(change:(JSONObject)->Unit)=writes.withLock {
        withContext(Dispatchers.IO) {
            val next=read().policy
            change(next)
            val args=Bundle().apply{putString("policy",next.toString())}
            call("save",args)
        }
    }
    suspend fun restart()=writes.withLock {
        withContext(Dispatchers.IO) {
            val launch=caller.packageManager.getLaunchIntentForPackage("com.tencent.mobileqq")?:error("未安装 QQ")
            if(caller.packageName!="com.tencent.mobileqq")caller.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val result=call("restart",Bundle().apply{putBoolean("refresh",true)})
            if(result.getBoolean("running"))return@withContext result
            if(caller.packageName=="com.tencent.mobileqq")caller.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            repeat(150){delay(100);val next=call("restart");if(next.getBoolean("running"))return@withContext next}
            error("QQ 尚未连接重启接口，请确认已启用模块，并在本次更新后手动完整重启 QQ 一次")
        }
    }
}
