@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.qqmodulemanager

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.database.ContentObserver
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val protocols=linkedMapOf("chat" to "OpenAI Chat Completions","responses" to "OpenAI Responses","gemini" to "Google Gemini","anthropic" to "Anthropic")
private val defaultUrls=mapOf("chat" to "https://api.openai.com/v1","responses" to "https://api.openai.com/v1","gemini" to "https://generativelanguage.googleapis.com/v1beta","anthropic" to "https://api.anthropic.com/v1")

@Composable internal fun NativeFeatureSettings(context:Context,modifier:Modifier,section:String,policy:JSONObject,savePolicy:((JSONObject)->Unit)->Unit) {
    if(section=="lens") {
        LazyColumn(modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=28.dp)) {
            item{SettingSwitch("Google 识图","长按普通图片，交给浏览器上传并打开 Google Lens 结果。",policy.optBoolean("lensEnabled",true)){v->savePolicy{it.put("lensEnabled",v)}}}
            item{Text("使用 QQ 原生识图图标，结果在系统默认浏览器中打开。多图消息可以选择其中一张。")}
            item{Text("自动定位 QQ 缓存，缺失时补取清晰预览图，无需先点开图片。上传前显示实际图片预览；常见格式在体积合适时保留原文件，避免多余重编码。")}
            item{Text("QQ 负责取图和准备，浏览器直接上传 Google 并打开结果，使用同一会话；不依赖QIII APP 常驻。",style=MaterialTheme.typography.bodySmall)}
            item{Text("浏览器显示所选图片后自动提交，不再改写 Google 返回的结果链接。上传期间由浏览器显示页面加载状态；若出现 Google 验证，可直接在该页面完成。",style=MaterialTheme.typography.bodySmall)}
        };return
    }
    val client=remember{FeatureConnection(context)}
    val scope=rememberCoroutineScope()
    var settings by remember{mutableStateOf<JSONObject?>(null)}
    var accounts by remember{mutableStateOf(JSONArray())}
    var draft by remember{mutableStateOf<JSONObject?>(null)}
    var modelPicker by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var notice by remember{mutableStateOf("")}
    var lastError by remember{mutableStateOf<Throwable?>(null)}
    var prompt by remember{mutableStateOf<String?>(null)}
    var confirmClear by remember{mutableStateOf<String?>(null)}
    var refreshToken by remember{mutableIntStateOf(0)}
    var refreshPending by remember{mutableStateOf(false)}
    val lifecycleOwner=LocalLifecycleOwner.current
    fun work(clearNotice:Boolean=true,block:suspend()->Unit){if(!busy){busy=true;if(clearNotice){notice="";lastError=null};scope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){lastError=e;notice=e.message?:"操作失败";DiagnosticLog.get(context).event("ERROR","settings","operation",(e as? ToolFailure)?.requestId.orEmpty(),error=e)}finally{busy=false;if(refreshPending){refreshPending=false;refreshToken++}}}}}
    fun refresh(){work(false){val result=client.call("settings");settings=result.getJSONObject("settings");accounts=result.getJSONArray("accounts")}}
    fun change(block:(JSONObject)->Unit){val next=settings?.copy()?:return;block(next);work{settings=client.call("saveSettings",next).getJSONObject("settings");notice="已保存"}}
    val requestRefresh by rememberUpdatedState<()->Unit>({if(busy)refreshPending=true else refreshToken++})
    LaunchedEffect(refreshToken,draft!=null){if(draft==null)refresh()}
    DisposableEffect(client,lifecycleOwner){
        val caller=context.applicationContext?:context
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean){requestRefresh()}}
        val registered=runCatching{caller.contentResolver.registerContentObserver(TRANSLATION_SETTINGS_URI,false,observer)}.isSuccess
        val listener=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)requestRefresh()}
        lifecycleOwner.lifecycle.addObserver(listener)
        onDispose{if(registered)caller.contentResolver.unregisterContentObserver(observer);lifecycleOwner.lifecycle.removeObserver(listener);client.close()}
    }
    BackHandler(draft!=null){if(!busy){draft=null;notice=""}}
    AnimatedContent(draft!=null,label="翻译设置") { editing->
        LazyColumn(modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=28.dp)) {
            if(notice.isNotBlank())item{Column{Text(notice,color=if(lastError!=null)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary);lastError?.let{failure->TextButton(onClick={ToolDialog.error(context,"操作失败",failure)}){Text("查看／复制错误详情")}}}}
            if(busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(!editing) {
                item{SettingSwitch("消息翻译","在消息下方展开译文卡片。",policy.optBoolean("translationEnabled",true)){v->savePolicy{it.put("translationEnabled",v)}}}
                item{AndroidView(factory={ctx->TranslationCard(ctx){}.apply{show("你好，最近怎么样？","✓ 由 微软 提供翻译支持  ⋯",false)}},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp))}
                if(settings==null)item{OutlinedButton(onClick=::refresh,enabled=!busy){Text("重新读取设置")}}
                settings?.let { config->
                    val providers=config.getJSONArray("providers").objects()
                    val choices=listOf("microsoft" to "微软（免 Key）","google" to "Google（免 Key）")+providers.map{it.getString("id") to it.getString("name")}
                    item{SettingChoice("默认翻译服务",config.getString("provider"),choices,!busy){v->change{it.put("provider",v)}}}
                    item{SettingChoice("普通消息目标语言",config.getString("target"),translationLanguages.toList(),!busy){v->change{it.put("target",v)}}}
                    item{SettingSwitch("中文默认翻译为另一种语言","在本机识别中文，其余语言使用上面的目标语言。",config.getBoolean("chineseToEnglish"),!busy){v->change{it.put("chineseToEnglish",v)}}}
                    if(config.getBoolean("chineseToEnglish"))item{SettingChoice("中文消息目标语言",config.getString("chineseTarget"),translationLanguages.toList(),!busy){v->change{it.put("chineseTarget",v)}}}
                    item{Text("免费翻译",style=MaterialTheme.typography.titleMedium)}
                    item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {for((id,title) in listOf("microsoft" to "测试微软","google" to "测试 Google"))OutlinedButton(enabled=!busy,onClick={work{
                        val result=client.call("test",JSONObject().put("id",id).put("name",id).put("protocol",id));notice="$title：${result.getString("text")}"
                    }}){Text(title)}}}
                    item{Text("AI 翻译服务",style=MaterialTheme.typography.titleMedium)}
                    items(providers,key={it.getString("id")}){profile->Card(onClick={if(!busy){draft=profile.copy();notice=""}},modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(18.dp)){
                        Text(profile.getString("name"),style=MaterialTheme.typography.titleMedium)
                        Text("${protocols[profile.optString("protocol")]} · ${profile.optString("model").ifBlank{"尚未选择模型"}}",style=MaterialTheme.typography.bodySmall)
                    }}}
                    item{OutlinedButton(enabled=!busy,onClick={draft=JSONObject().put("id",UUID.randomUUID().toString()).put("name","").put("protocol","chat").put("url",defaultUrls.getValue("chat")).put("reasoning","auto").put("reasoningMode","auto");notice=""}){Text("添加 AI 服务")}}
                    item{OutlinedButton(enabled=!busy,onClick={prompt=config.getString("prompt")}){Text("翻译提示词")}}
                    item{Text("本地译文",style=MaterialTheme.typography.titleMedium)}
                    item{Text("重启 QQ 后仍可恢复。收起只隐藏卡片，清理会删除已保存译文。",style=MaterialTheme.typography.bodySmall)}
                    items(accounts.objects(),key={it.getString("account")}){account->TextButton(enabled=!busy,onClick={confirmClear=account.getString("account")}){Text("清理账号 ${account.getString("account")} 的 ${account.getLong("count")} 条译文")}}
                    item{TextButton(enabled=!busy,onClick={confirmClear="*"}){Text("清理全部译文")}}
                    item{Text("聊天翻译在 QQ 进程执行，Key 在本机加密保存并同步到 QQ。无需QIII APP 常驻；失败后可在译文卡片中手动更换服务。",style=MaterialTheme.typography.bodySmall)}
                }
            }else draft?.let { profile->
                fun edit(key:String,value:Any){draft=draft!!.copy().put(key,value)}
                item{Text("AI 服务配置",style=MaterialTheme.typography.titleLarge)}
                item{OutlinedTextField(profile.optString("name"),{edit("name",it)},enabled=!busy,label={Text("服务名称")},modifier=Modifier.fillMaxWidth(),singleLine=true)}
                item{SettingChoice("接口协议",profile.optString("protocol"),protocols.toList(),!busy){v->
                    val next=profile.copy().put("protocol",v).put("reasoningMode","auto").put("reasoning","auto")
                    if(profile.optString("url") in defaultUrls.values)next.put("url",defaultUrls.getValue(v));draft=next
                }}
                item{OutlinedTextField(profile.optString("url"),{edit("url",it)},enabled=!busy,label={Text("API 地址")},supportingText={Text("填写服务基地址，例如 https://api.openai.com/v1")},modifier=Modifier.fillMaxWidth(),singleLine=true)}
                item{OutlinedTextField(profile.optString("key"),{draft=profile.copy().put("key",it).put("keyChanged",true)},enabled=!busy,label={Text(if(profile.optBoolean("keyConfigured") && !profile.optBoolean("keyChanged"))"API Key（已设置，留空不更改）" else "API Key")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)}
                if(profile.optBoolean("keyConfigured"))item{TextButton(enabled=!busy,onClick={draft=profile.copy().put("key","").put("keyChanged",true)}){Text("清除已保存的 Key")}}
                item{OutlinedTextField(profile.optString("model"),{edit("model",it)},enabled=!busy,label={Text("模型 ID，可手动填写")},modifier=Modifier.fillMaxWidth(),singleLine=true)}
                item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled=!busy,onClick={work{val result=client.call("models",profile);draft=draft!!.copy().put("models",result.getJSONArray("models"));modelPicker=true;notice="已拉取 ${result.getJSONArray("models").length()} 个模型"}}){Text("拉取模型")}
                    OutlinedButton(enabled=!busy && profile.optJSONArray("models")?.length()?.let{it>0}==true,onClick={modelPicker=true}){Text("选择模型")}
                }}
                val modes=when(profile.getString("protocol")) {
                    "gemini"->listOf("gemini-level" to "Gemini 档位","gemini-budget" to "Gemini token 预算")
                    "anthropic"->listOf("claude-adaptive" to "自适应思考","claude-budget" to "传统 token 预算")
                    "responses"->listOf("effort" to "OpenAI effort")
                    else->listOf("effort" to "OpenAI effort","qwen" to "Qwen 思考开关","deepseek" to "DeepSeek 思考开关")
                }
                item{SettingChoice("思考参数模式",profile.optString("reasoningMode","auto"),listOf("auto" to "按模型识别")+modes,!busy){v->edit("reasoningMode",v)}}
                val actualMode=reasoningMode(profile)
                val level=profile.optString("reasoning","auto").let{if(actualMode in listOf("qwen","deepseek") && it=="high")"on" else it}
                item{SettingChoice("思考档位",level,reasoningChoices(profile),!busy){v->edit("reasoning",v)}}
                if(actualMode in listOf("qwen","deepseek"))item{Text("当前模式的接口只提供开／关参数。若你的中转支持多档 effort，请将参数模式切换为 OpenAI effort。",style=MaterialTheme.typography.bodySmall)}
                if(actualMode=="effort")item{Text("off 发送 none；其余档位原样发送，包括 xhigh、max。具体档位需模型及服务支持，不会将 max 降为 high。",style=MaterialTheme.typography.bodySmall)}
                if(actualMode in listOf("gemini-budget","claude-budget"))item{OutlinedTextField(profile.opt("budget")?.toString().orEmpty(),{v->if(v.isEmpty())draft=profile.copy().apply{remove("budget")} else v.toIntOrNull()?.let{edit("budget",it)}},enabled=!busy,label={Text("思考 token 预算，留空按档位")},modifier=Modifier.fillMaxWidth(),singleLine=true)}
                item{Text("未知模型请先使用“模型默认”。指定思考参数后，可用连接测试确认服务是否接受；不会自动丢弃不支持的参数。",style=MaterialTheme.typography.bodySmall)}
                item{OutlinedButton(enabled=!busy,onClick={work{notice="测试译文：${client.call("test",profile).getString("text")}"}}){Text("测试连接与翻译")}}
                item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Button(enabled=!busy,onClick={work{settings=client.call("saveProvider",profile).getJSONObject("settings");draft=null;notice="服务已保存"}}){Text("保存服务")}
                    TextButton(enabled=!busy,onClick={draft=null;notice=""}){Text("取消")}
                }}
                if(settings?.getJSONArray("providers")?.objects()?.any{it.getString("id")==profile.getString("id")}==true)item{TextButton(enabled=!busy,onClick={
                    val next=settings!!.copy();next.put("providers",JSONArray(next.getJSONArray("providers").objects().filter{it.getString("id")!=profile.getString("id")}))
                    if(next.optString("provider")==profile.getString("id"))next.put("provider","microsoft")
                    work{settings=client.call("saveSettings",next).getJSONObject("settings");draft=null;notice="已删除服务，已有译文继续保留原来源"}
                }){Text("删除服务")}}
            }
        }
    }
    if(modelPicker && draft!=null)ModelPicker(draft!!.optJSONArray("models")?:JSONArray(),{model->draft=draft!!.copy().put("model",model);modelPicker=false},{modelPicker=false})
    prompt?.let { value->AlertDialog(onDismissRequest={if(!busy)prompt=null},title={Text("默认翻译提示词")},text={Column{
        OutlinedTextField(value,{prompt=it},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=180.dp,max=350.dp))
        Text("{target_lang} 会替换为目标语言。消息正文单独作为用户内容发送。",style=MaterialTheme.typography.bodySmall)
        TextButton(enabled=!busy,onClick={prompt=TRANSLATION_PROMPT}){Text("恢复默认")}
    }},confirmButton={TextButton(enabled=!busy,onClick={change{it.put("prompt",value.ifBlank{TRANSLATION_PROMPT})};prompt=null}){Text("保存")}},dismissButton={TextButton(enabled=!busy,onClick={prompt=null}){Text("取消")}})}
    confirmClear?.let { account->AlertDialog(onDismissRequest={confirmClear=null},title={Text("清理译文")},text={Text(if(account=="*")"删除全部账号的本地译文，并取消正在进行的翻译。" else "删除账号 $account 的本地译文，并取消相关翻译。")},confirmButton={TextButton(enabled=!busy,onClick={work{accounts=client.call("clear",args=Bundle().apply{if(account!="*")putString("account",account)}).getJSONArray("accounts");notice="译文已清理"};confirmClear=null}){Text("清理")}},dismissButton={TextButton(onClick={confirmClear=null}){Text("取消")}})}
}

@Composable private fun SettingSwitch(title:String,subtitle:String,value:Boolean,enabled:Boolean=true,change:(Boolean)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Text(subtitle,style=MaterialTheme.typography.bodySmall)};Switch(value,onCheckedChange=change,enabled=enabled)}
}
@Composable private fun SettingChoice(title:String,value:String,options:List<Pair<String,String>>,enabled:Boolean,select:(String)->Unit) {
    var open by remember{mutableStateOf(false)}
    Column{Text(title,style=MaterialTheme.typography.labelLarge);Box{OutlinedButton(enabled=enabled,onClick={open=true}){Text(options.firstOrNull{it.first==value}?.second?:value)}
        DropdownMenu(open,onDismissRequest={open=false}){options.forEach{(id,label)->DropdownMenuItem(text={Text(label)},onClick={open=false;select(id)})}}}}
}
@Composable private fun ModelPicker(models:JSONArray,select:(String)->Unit,dismiss:()->Unit) {
    var query by remember{mutableStateOf("")}
    val values=(0 until models.length()).map{models.getString(it)}
    AlertDialog(onDismissRequest=dismiss,title={Text("选择翻译模型")},text={Column {
        OutlinedTextField(query,{query=it},label={Text("搜索模型")},singleLine=true)
        LazyColumn(Modifier.heightIn(max=320.dp)){items(values.filter{it.contains(query,true)},key={it}){model->TextButton(onClick={select(model)}){Text(model)}}}
    }},confirmButton={TextButton(onClick=dismiss){Text("关闭")}})
}
