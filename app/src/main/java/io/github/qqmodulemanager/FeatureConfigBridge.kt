package io.github.qqmodulemanager

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject

/** Configuration transfer is UID-authorized by PolicyProvider; requests run in QQ. */
internal object FeatureConfigBridge {
    @JvmStatic fun call(context:Context,operation:String,args:Bundle?):Bundle {
        val store=TranslationStore(context)
        try {
            val state=context.getSharedPreferences("translation-sync",0)
            if(operation=="toolCacheExport") {
                val pipe=ParcelFileDescriptor.createPipe()
                Thread({try{ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use{store.exportCache(it)}}catch(e:Exception){DiagnosticLog.get(context).event(if(e is java.io.IOException)"INFO" else "ERROR","cache","legacy-export",error=e)}finally{store.close()}},"QIII-cache-export").start()
                return Bundle().apply{putParcelable("cache",pipe[0])}
            }
            when(operation) {
                "toolSaveSettings"->store.saveSettings(JSONObject(args!!.getString("document","{}")))
                "toolSaveProvider"->store.saveProvider(JSONObject(args!!.getString("document","{}")))
                "toolClear"->{
                    store.clear(args?.getString("account"),args?.getString("message"))
                    val commands=JSONArray(state.getString("clears","[]"));val revision=state.getLong("revision",0)+1
                    commands.put(JSONObject().put("revision",revision).put("id",args?.getString("commandId")?:java.util.UUID.randomUUID().toString()).put("account",args?.getString("account")?:JSONObject.NULL).put("message",args?.getString("message")?:JSONObject.NULL))
                    val bounded=JSONArray((maxOf(0,commands.length()-256) until commands.length()).map{commands.getJSONObject(it)})
                    check(state.edit().putLong("revision",revision).putString("clears",bounded.toString()).commit()){"清理记录保存失败"}
                    context.contentResolver.notifyChange(TRANSLATION_SETTINGS_URI,null)
                }
                "toolConfig"->Unit
                else->error("未知配置操作")
            }
            return Bundle().apply{putString("snapshot",store.configuration().put("clears",JSONArray(state.getString("clears","[]"))).toString())}
        }finally{if(operation!="toolCacheExport")store.close()}
    }
}
