package io.github.qqmodulemanager

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

/** A one-use loopback handoff lets the browser upload and open Lens in its own session. */
internal object BrowserLensUpload {
    suspend fun prepare(context:Context,path:String,progress:(org.json.JSONObject)->Unit={}):String=withContext(Dispatchers.IO) {
        val original=File(path);require(original.length() in 1..20*1024*1024){"图片为空或超过 20 MB"}
        val image=File.createTempFile("qqmm-browser-lens-",".jpg",context.cacheDir)
        val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
        android.graphics.BitmapFactory.decodeFile(path,options)
        val preserve=options.outMimeType in listOf("image/jpeg","image/png","image/webp") && original.length()<=4*1024*1024 && maxOf(options.outWidth,options.outHeight)<=4096
        var info=org.json.JSONObject()
        val bytes=try{LensSearch(context).prepare(original,image){info=it};if(preserve)original.readBytes() else image.readBytes()}finally{image.delete()}
        val mime=if(preserve)options.outMimeType else "image/jpeg"
        progress(info.put("message","已准备图片 · ${bytes.size/1024} KB，将由浏览器上传并打开结果").put("bytes",bytes.size).put("sourcePreserved",preserve))
        val trace=ToolTrace.current.get()
        trace?.event("lens.browser.prepare",org.json.JSONObject().put("bytes",bytes.size).put("mime",mime).put("sourcePreserved",preserve).put("sha256",java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}))
        val url=serve(bytes,mime){trace?.event("lens.browser.handoff",org.json.JSONObject().put("bytes",bytes.size).put("status","image-delivered-to-browser"))}
        progress(org.json.JSONObject().put("stage","browser").put("message","正在交给浏览器；Google 上传与结果页由同一浏览器完成…"))
        url
    }
    internal fun serve(bytes:ByteArray,mime:String="image/jpeg",delivered:()->Unit={}):String {
        require(mime in listOf("image/jpeg","image/png","image/webp")){"不支持的识图格式"}
        val filename="image."+when(mime){"image/png"->"png";"image/webp"->"webp";else->"jpg"}
        val token=UUID.randomUUID().toString();val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));server.soTimeout=60000
        val encoded=Base64.encodeToString(bytes,Base64.NO_WRAP)
        val html="""<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="referrer" content="no-referrer"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Google 识图</title><style>body{font:16px system-ui;margin:24px;background:#f7f8fc;color:#202124}main{max-width:520px;margin:auto;padding:24px;background:#fff;border-radius:28px}img{max-width:100%;max-height:240px;border-radius:16px}button{border:0;border-radius:24px;padding:14px 24px;background:#2563eb;color:white;font:inherit}button:disabled{opacity:.45}#stop{background:transparent;color:inherit;border:1px solid #888}p{line-height:1.5}.actions{display:flex;flex-wrap:wrap;gap:12px}.detail{font-size:14px;opacity:.8}@media(prefers-color-scheme:dark){body{background:#141318;color:#eee}main{background:#25242a}button{background:#a8c7ff;color:#1c3152}}</style>
<main><h2>Google 识图</h2><img id="preview" src="data:$mime;base64,$encoded" alt="本次所选图片"><p id="status" role="status">正在准备提交这张图片…</p><p id="elapsed" class="detail"></p><p id="hint" class="detail" hidden>暂未收到 Google 页面的响应。可以继续等待，或先停止再重试。</p>
<form method="post" enctype="multipart/form-data" action="https://lens.google.com/v3/upload?hl=zh-CN&amp;re=df&amp;ep=cntpubb&amp;st=${System.currentTimeMillis()}"><input hidden type="file" accept="image/*" name="encoded_image" id="image"><input hidden name="image_content" value=""><div class="actions"><button id="retry" type="submit">重新提交图片</button><button id="stop" type="button" disabled>停止等待</button></div></form><noscript><p>请启用浏览器 JavaScript 后，从 QQ 重新发起识图。</p></noscript></main>
<script>
const form=document.forms[0],image=document.getElementById('image'),status=document.getElementById('status'),elapsed=document.getElementById('elapsed'),hint=document.getElementById('hint'),retry=document.getElementById('retry'),stop=document.getElementById('stop');
let waiting=false,timer=null,started=0;
function tick(){const seconds=Math.floor((performance.now()-started)/1000);elapsed.textContent='已等待 '+seconds+' 秒 · 图片 '+Math.ceil(image.files[0].size/1024)+' KB';hint.hidden=seconds<15;}
function submitImage(){if(waiting)return;if(!image.files.length){status.textContent='请选择图片后重新提交。';return;}waiting=true;retry.disabled=true;stop.disabled=false;hint.hidden=true;status.textContent='已发起 Google 识图请求，正在等待结果页面…';started=performance.now();tick();timer=setInterval(tick,1000);form.submit();}
form.addEventListener('submit',function(event){event.preventDefault();submitImage();});
stop.addEventListener('click',function(){window.stop();clearInterval(timer);waiting=false;retry.disabled=false;stop.disabled=true;hint.hidden=true;status.textContent='已停止等待，可以重新提交这张图片。';});
window.addEventListener('pagehide',function(){clearInterval(timer);});
window.addEventListener('pageshow',function(event){if(event.persisted){clearInterval(timer);waiting=false;retry.disabled=false;stop.disabled=true;hint.hidden=true;status.textContent='已返回识图页面，可以重新提交这张图片。';}});
try{const raw=atob(document.getElementById('preview').src.split(',')[1]),data=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)data[i]=raw.charCodeAt(i);const transfer=new DataTransfer();transfer.items.add(new File([data],'$filename',{type:'$mime'}));image.files=transfer.files;setTimeout(submitImage,0);}catch(e){image.hidden=false;status.textContent='当前浏览器不支持自动填入图片，可选择这张图片后重新提交。';}
</script></html>""".toByteArray(Charsets.UTF_8)
        Thread({server.use {
            val deadline=System.nanoTime()+60_000_000_000L
            while(true) {
                val remaining=((deadline-System.nanoTime())/1_000_000).toInt()
                if(remaining<=0)break
                server.soTimeout=remaining
                val client=try{server.accept()}catch(_:java.io.IOException){break}
                try{client.use {
                    client.soTimeout=minOf(5000,remaining)
                    val reader=client.getInputStream().bufferedReader()
                    val line=reader.readLine().orEmpty();var headerSize=line.length
                    while(true){val header=reader.readLine()?:break;headerSize+=header.length;require(headerSize<=16384){"浏览器请求头过长"};if(header.isEmpty())break}
                    val ok=line.startsWith("GET /$token ")
                    val body=if(ok)html else "Not found".toByteArray()
                    client.getOutputStream().use{output->output.write(("HTTP/1.1 ${if(ok)"200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray());output.write(body)}
                    if(ok){delivered();return@Thread}
                }}catch(_:java.io.IOException){}catch(_:IllegalArgumentException){}
            }
        }},"QIII-browser-image").apply{isDaemon=true;start()}
        return "http://127.0.0.1:${server.localPort}/$token"
    }
}
