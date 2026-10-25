package io.github.qqmodulemanager

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import android.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

internal class LensSearch(private val context:Context) {
    internal fun prepare(original:File,image:File,progress:(JSONObject)->Unit) {
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(original.path,options)
        require(options.outWidth>0 && options.outHeight>0){"图片尚未下载完整，或不是支持的图片格式"}
        var sample=1;while(maxOf(options.outWidth,options.outHeight)/sample>3200)sample*=2
        val decoded=BitmapFactory.decodeFile(original.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("图片解码失败")
        var rotated=decoded
        try {
            val orientation=runCatching{ExifInterface(original.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix=Matrix().apply{when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(270f);postScale(-1f,1f)};8->setRotate(270f)}}
            if(!matrix.isIdentity)rotated=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true)
            val ratio=minOf(1f,1600f/maxOf(rotated.width,rotated.height));val width=maxOf(1,(rotated.width*ratio).toInt());val height=maxOf(1,(rotated.height*ratio).toInt())
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply{drawColor(Color.WHITE);drawBitmap(rotated,null,Rect(0,0,width,height),Paint(Paint.FILTER_BITMAP_FLAG))}
                image.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)){"图片编码失败"}}
                val scale=minOf(1f,240f/maxOf(width,height));val preview=Bitmap.createScaledBitmap(bitmap,maxOf(1,(width*scale).toInt()),maxOf(1,(height*scale).toInt()),true)
                val previewBytes=try{ByteArrayOutputStream().use{preview.compress(Bitmap.CompressFormat.JPEG,80,it);it.toByteArray()}}finally{if(preview!==bitmap)preview.recycle()}
                progress(JSONObject().put("stage","prepared").put("message","已准备图片 ${width}×$height · ${image.length()/1024} KB")
                    .put("preview",Base64.encodeToString(previewBytes,Base64.NO_WRAP)).put("width",width).put("height",height).put("bytes",image.length()))
                ToolTrace.current.get()?.event("lens.prepared",JSONObject().put("originalWidth",options.outWidth).put("originalHeight",options.outHeight).put("width",width).put("height",height)
                    .put("sourceBytes",original.length()).put("preparedJpegBytes",image.length()).put("format","JPEG").put("orientation",orientation))
            }finally{bitmap.recycle()}
        }finally{if(rotated!==decoded)rotated.recycle();decoded.recycle()}
    }
    suspend fun search(descriptor:ParcelFileDescriptor,progress:(JSONObject)->Unit):String {
        val directory=File(context.cacheDir,"qqmm-lens").apply{mkdirs()}
        val original=File.createTempFile("source-",".img",directory)
        try {
            progress(JSONObject().put("stage","read").put("message","正在读取所选图片…"))
            val fingerprint=java.security.MessageDigest.getInstance("SHA-256");val md5=java.security.MessageDigest.getInstance("MD5");var total=0
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input->original.outputStream().use { output->
                val buffer=ByteArray(32768)
                while(true){currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break;total+=count;require(total<=20*1024*1024){"所选图片超过 20 MB，无法上传"};fingerprint.update(buffer,0,count);md5.update(buffer,0,count);output.write(buffer,0,count)}
            }}
            ToolTrace.current.get()?.event("lens.source",JSONObject().put("bytes",total).put("sha256",fingerprint.digest().joinToString(""){"%02x".format(it)}).put("md5",md5.digest().joinToString(""){"%02x".format(it)}))
            currentCoroutineContext().ensureActive()
            return BrowserLensUpload.prepare(context,original.path,progress)
        }finally{original.delete()}
    }
}
