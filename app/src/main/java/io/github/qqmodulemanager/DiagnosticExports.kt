package io.github.qqmodulemanager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID

internal object DiagnosticExports {
    fun file(context:Context,uri:Uri):File {
        require(uri.authority=="io.github.qqmodulemanager.bridge" && uri.pathSegments.size==2 && uri.pathSegments[0]=="diagnostics" && uri.lastPathSegment!!.matches(Regex("[0-9a-f-]{36}\\.txt"))){"无效日志文件"}
        return File(File(context.filesDir,"diagnostic-exports"),uri.lastPathSegment!!)
    }
    @JvmStatic fun create(context:Context,text:String):Bundle {
        require(text.length<=1_000_000){"日志导出内容过大，请缩小范围"}
        val directory=File(context.filesDir,"diagnostic-exports").apply{mkdirs()}
        directory.listFiles()?.sortedByDescending{it.lastModified()}?.drop(9)?.forEach{it.delete()}
        val uri=PolicyStore.URI.buildUpon().appendPath("diagnostics").appendPath("${UUID.randomUUID()}.txt").build()
        file(context,uri).writeText(text)
        return Bundle().apply{putString("uri",uri.toString())}
    }
    @JvmStatic fun fromDescriptor(context:Context,descriptor:ParcelFileDescriptor):Bundle {
        val result=create(context,"");val target=file(context,Uri.parse(result.getString("uri")))
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use{input->target.outputStream().use{output->
                val buffer=ByteArray(32768);var count=0
                while(true){val size=input.read(buffer);if(size<0)break;count+=size;require(count<=4*1024*1024){"日志导出超过 4 MB，请缩小范围"};output.write(buffer,0,size)}
            }}
            return result
        }catch(error:Exception){target.delete();throw error}
    }
    @JvmStatic fun authorizeRead(context:Context,uri:Uri) {
        val uid=Binder.getCallingUid();if(uid==context.applicationInfo.uid)return
        val qqUid=try{context.packageManager.getApplicationInfo("com.tencent.mobileqq",0).uid}catch(_:PackageManager.NameNotFoundException){-1}
        if(uid==qqUid || context.checkUriPermission(uri,Binder.getCallingPid(),uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED)return
        throw SecurityException("日志文件读取未授权")
    }
    @JvmStatic fun open(context:Context,uri:Uri,mode:String):ParcelFileDescriptor {
        require(mode=="r"){"日志只能只读打开"};authorizeRead(context,uri)
        return ParcelFileDescriptor.open(file(context,uri),ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
