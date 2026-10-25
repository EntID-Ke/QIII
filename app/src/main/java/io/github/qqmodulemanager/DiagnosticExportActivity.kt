package io.github.qqmodulemanager

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class DiagnosticExportActivity:ComponentActivity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val source=intent.data?:run{finish();return}
        try{DiagnosticExports.file(this,source)}catch(_:IllegalArgumentException){finish();return}
        setContent{ManagerTheme{Surface{Text("请选择日志保存位置")}}}
        if(state==null) {
            val name="QIII-${BuildConfig.VERSION_NAME}-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(java.util.Date())}.txt"
            @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,name),1)
        }
    }
    @Deprecated("Android document picker callback") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=1)return
        val destination=data?.data
        if(resultCode!=Activity.RESULT_OK || destination==null){finish();return}
        setContent{ManagerTheme{Surface{Text("正在导出日志…")}}}
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO){contentResolver.openInputStream(intent.data!!)!!.use{input->contentResolver.openOutputStream(destination)!!.use{input.copyTo(it)}}}
                Toast.makeText(this@DiagnosticExportActivity,"日志已导出",Toast.LENGTH_SHORT).show()
            }catch(e:CancellationException){throw e}catch(e:Exception){DiagnosticLog.get(this@DiagnosticExportActivity).event("ERROR","diagnostics","export-failed",error=e);Toast.makeText(this@DiagnosticExportActivity,"导出失败：${e.javaClass.simpleName}",Toast.LENGTH_LONG).show()}
            finish()
        }
    }
}
