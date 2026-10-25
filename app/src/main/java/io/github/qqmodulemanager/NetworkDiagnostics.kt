package io.github.qqmodulemanager

import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.*
import javax.net.ssl.SSLException

internal class NetworkTrace(val trace:ToolTrace?,val host:String) {
    @Volatile var stage="connect"
    fun event(name:String,fields:JSONObject=JSONObject()){stage=name;trace?.event("http.$name",fields.put("host",host))}
}
internal class NetworkDiagnostics:EventListener() {
    private fun trace(call:Call)=call.request().tag(NetworkTrace::class.java)
    override fun dnsStart(call:Call,domainName:String){trace(call)?.event("dns")}
    override fun connectStart(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy){trace(call)?.event("connect")}
    override fun secureConnectStart(call:Call){trace(call)?.event("tls")}
    override fun requestBodyStart(call:Call){trace(call)?.event("upload")}
    override fun requestBodyEnd(call:Call,byteCount:Long){trace(call)?.event("uploaded",JSONObject().put("bytes",byteCount))}
    override fun responseHeadersStart(call:Call){trace(call)?.event("response")}
    override fun responseHeadersEnd(call:Call,response:Response){trace(call)?.event("headers",JSONObject().put("status",response.code))}
    override fun responseBodyStart(call:Call){trace(call)?.event("download")}
}
internal fun networkFailure(error:IOException,host:String,stage:String,cancelled:Boolean):String {
    if(cancelled)return "请求已取消"
    val chain=generateSequence<Throwable>(error){it.cause}.take(8).toList();val cause=chain.last()
    val reason=when {
        chain.any{it is UnknownHostException}->"DNS 解析失败"
        chain.any{it is SSLException}->"TLS 安全连接失败"
        chain.any{it is SocketTimeoutException || it is java.io.InterruptedIOException}->when(stage){"upload"->"上传超时";"uploaded","response","headers","download"->"等待服务响应超时";else->"连接超时"}
        chain.any{it is ConnectException}->"无法连接服务器"
        error.message.orEmpty().contains("CLEARTEXT",true)->"当前进程禁止此明文 HTTP 地址"
        else->"连接中断（${cause.javaClass.simpleName}）"
    }
    return "$reason · $host。可查看诊断日志中的具体原因"
}
