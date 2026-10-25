@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.qqmodulemanager

import io.github.qqmodulemanager.bridge.FeatureCatalog
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.painterResource
import android.database.ContentObserver
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject

private val moduleNames=mapOf("qauxv" to "QAuxiliary", "qfun" to "QFun", "ono" to "ono", "manager" to "QIII", "QQ" to "QQ 原生", "unknown" to "未识别来源")
private val kindNames=linkedMapOf("text" to "文字", "image" to "图片", "voice" to "语音", "video" to "视频", "file" to "文件")
private fun JSONObject.obj(key:String)=optJSONObject(key)?:JSONObject()
private fun JSONArray?.strings():List<String> = if(this==null)emptyList() else (0 until length()).map{optString(it)}

internal class ManagerUi(private val context:Context,private val close:(()->Unit)?=null) {
    @Composable fun Content() {
        ManagerTheme { Manager() }
    }

    @Composable private fun Manager() {
        val repository=remember{ManagerRepository(context)}
        val scope=rememberCoroutineScope()
        val lifecycleOwner=LocalLifecycleOwner.current
        var data by remember{mutableStateOf(ManagerSnapshot())}
        var error by remember{mutableStateOf("")}
        var notice by remember{mutableStateOf("")}
        var restarting by remember{mutableStateOf(false)}
        var tick by remember{mutableIntStateOf(0)}
        var screen by remember{mutableStateOf("home")}
        var query by remember{mutableStateOf("")}
        val moduleListState=rememberLazyListState()
        fun back(){screen=if(FeatureCatalog.get(screen)!=null)"modules" else "home"}
        LaunchedEffect(tick) {
            try{data=withContext(Dispatchers.IO){repository.read()};error=""}catch(e:CancellationException){throw e}catch(e:Exception){DiagnosticLog.get(context).event("ERROR","configuration","read",error=e);error="读取配置失败：${e.javaClass.simpleName} · ${e.message.orEmpty()}"}
        }
        DisposableEffect(lifecycleOwner) {
            val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {override fun onChange(selfChange:Boolean){tick++}}
            context.contentResolver.registerContentObserver(PolicyStore.URI,true,observer)
            val listener=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)tick++}
            lifecycleOwner.lifecycle.addObserver(listener)
            onDispose{context.contentResolver.unregisterContentObserver(observer);lifecycleOwner.lifecycle.removeObserver(listener)}
        }
        val policy=data.policy
        val runtime=data.runtime
        val reports=runtime.keys().asSequence().map{runtime.obj(it)}.toList()
        val main=reports.filter{it.optString("process")=="com.tencent.mobileqq"}.maxWithOrNull(compareBy<JSONObject>{it.optBoolean("connected")}.thenBy{it.optLong("seenAt")})
        val host=main?:JSONObject()
        val connected=host.optBoolean("connected")
        val backgroundConnected=reports.any{it.optBoolean("connected")}
        LaunchedEffect(host.optInt("pid"),host.obj("hostErrors").optString("restart")){restarting=false}
        val catalog=data.catalog
        fun save(change:(JSONObject)->Unit){scope.launch(start=CoroutineStart.UNDISPATCHED){
            // Preserve input order and finish committed edits even if this page closes immediately.
            try{withContext(NonCancellable){repository.update(change)};tick++}catch(e:CancellationException){throw e}catch(e:Exception){DiagnosticLog.get(context).event("ERROR","configuration","save",error=e);error="保存失败：${e.javaClass.simpleName}"}
        }}
        fun restart(){if(!restarting){restarting=true;scope.launch(start=CoroutineStart.UNDISPATCHED){
            try {
                withContext(NonCancellable){repository.restart()}
                notice="已请求 QQ 重启，已保存的配置会保留。"
            }catch(e:CancellationException){throw e}catch(e:Exception){DiagnosticLog.get(context).event("ERROR","restart","request",error=e);error="重启失败：${e.message?:e.javaClass.simpleName}";restarting=false}
        }}}
        BackHandler(screen!="home"){back()}
        Scaffold(topBar={TopAppBar(title={Text(when(screen){"home"->"QIII";"modules"->"模块管理";"translate"->"消息翻译";"lens"->"Google 识图";"menus"->"消息长按菜单";"status"->"运行状态";"logs"->"日志与错误";else->FeatureCatalog.get(screen)?.title?:"QIII"})},navigationIcon={if(screen!="home") TextButton(onClick=::back){Text("返回")}else if(close!=null)TextButton(onClick=close){Text("关闭")}})}) { padding ->
            AnimatedContent(screen,transitionSpec={
                val direction=if(targetState=="home")-1 else 1
                (slideInHorizontally(tween(240)){it/6*direction}+fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(200)){-it/10*direction}+fadeOut(tween(140)))
            },label="页面切换") { route ->
            when(route) {
                "home"->LazyColumn(Modifier.padding(padding).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=28.dp)) {
                    item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                            Image(painterResource(R.drawable.ic_brand),null,Modifier.size(52.dp))
                            Column{Text("QIII",style=MaterialTheme.typography.titleLarge);Text("消息工具 · 模块管理",style=MaterialTheme.typography.bodySmall)}
                        }
                        Text("翻译聊天消息、搜索图片，整理菜单与功能提供方。",style=MaterialTheme.typography.bodyMedium)
                        AssistChip(onClick={screen="status"},label={Text(if(connected)"QQ 已连接" else if(backgroundConnected)"等待 QQ 主进程恢复" else "等待 QQ 连接")})
                        OutlinedButton(onClick=::restart,enabled=!restarting){Text(if(restarting)"正在重启…" else "重启 QQ")}
                    }}}
                    if(error.isNotEmpty())item{Text(error,color=MaterialTheme.colorScheme.error)}
                    if(notice.isNotEmpty())item{Text(notice,style=MaterialTheme.typography.bodyMedium)}
                    if(!connected)item{Text(if(backgroundConnected)"QQ 后台服务仍在运行，聊天与设置进程暂未连接。打开 QQ 主界面后会自动恢复。" else if(reports.isNotEmpty())"已保存的设置会保留。QQ 启动或恢复运行后会重新登记连接。" else "首次使用：在 LSPosed 为QIII勾选 QQ，完全重启 QQ。模块优先级管理还需要对应模块的适配版。",style=MaterialTheme.typography.bodyMedium)}
                    if(host.optJSONArray("incompatibleModules").strings().isNotEmpty())item{Text("需要替换适配版模块："+host.optJSONArray("incompatibleModules").strings().joinToString{moduleNames[it]?:it},color=MaterialTheme.colorScheme.error)}
                    item{NavigationCard("消息翻译","消息下方展开译文 · 微软 / Google / AI"){screen="translate"}}
                    item{NavigationCard("Google 识图","长按图片 · 在浏览器中查看 Lens 结果"){screen="lens"}}
                    item{NavigationCard("消息长按菜单","QQ 原生与模块项目 · 显示隐藏 · 拖拽排序"){screen="menus"}}
                    item{NavigationCard("模块管理","42 项功能 · 提供方优先级与接替设置"){screen="modules"}}
                    item{NavigationCard("运行状态","查看已连接模块、接替原因与配置同步情况"){screen="status"}}
                    item{NavigationCard("日志与错误","查看报错详情 · 复制 · 导出诊断日志"){screen="logs"}}
                    item{Text("设置与译文保存在本机。点击翻译时向所选服务发送当前正文；识图时向 Google 发送所选图片。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
                "modules"->LazyColumn(Modifier.padding(padding).padding(horizontal=20.dp),state=moduleListState,verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=28.dp)) {
                    item{OutlinedTextField(query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("搜索功能")},shape=RoundedCornerShape(18.dp))}
                    items(FeatureCatalog.ALL.filter{query.isBlank()||it.title.contains(query,true)||it.category.contains(query,true)},key={it.id}){feature->
                        NavigationCard(feature.title,feature.category+" · "+featureStatus(host.obj("features").obj(feature.id),policy.obj("features").obj(feature.id).optBoolean("enabled"))){screen=feature.id}
                    }
                }
                "translate","lens"->NativeFeatureSettings(context,Modifier.padding(padding),route,policy,::save)
                "logs"->DiagnosticsUi(context,Modifier.padding(padding))
                "menus"->MenuScreen(Modifier.padding(padding),policy,catalog,host,::save)
                "status"->LazyColumn(Modifier.padding(padding).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item{Text(if(connected)"QQ 连接正常" else if(backgroundConnected)"QQ 后台已连接，等待主进程" else "QQ 当前未连接",style=MaterialTheme.typography.titleLarge)}
                    item{OutlinedButton(onClick=::restart,enabled=!restarting){Text(if(restarting)"正在重启…" else "重启 QQ")}}
                    item{Text("配置版本 ${policy.optLong("revision")} · QQ 已接收 ${host.optLong("revision")}")}
                    item{Text("QQ ${host.optString("qqVersionName","未知")}（${host.optInt("qqVersionCode")}） · 已加载QIII ${host.optString("managerVersion","旧版未上报")}",style=MaterialTheme.typography.bodySmall)}
                    item{Text("消息工具菜单：${host.optString("nativeMenuState","QQ 中尚未上报，请完整重启 QQ 后长按一条消息")}")}
                    item{Text("消息工具执行器：${if(host.optString("toolExecutor")=="com.tencent.mobileqq")"QQ 进程" else "等待新版 QQ 执行器就绪"}")}
                    item{OutlinedButton(onClick={screen="logs"}){Text("查看日志与错误")}}
                    item{host.obj("hostErrors").keys().forEach{key->Text("${mapOf("menu" to "菜单","count" to "消息数量","settings" to "QQ 设置入口","translation" to "译文卡片","translationMenu" to "翻译与识图菜单","translationIcon" to "翻译图标")[key]?:key}接入失败：${host.obj("hostErrors").optString(key)}",color=MaterialTheme.colorScheme.error)}}
                    if(host.optInt("qqVersionCode",16580)!=16580)item{Text("首版适配 QQ 9.3.75；当前 QQ 版本暂不接管。",color=MaterialTheme.colorScheme.error)}
                    items(reports){report->Card{Column(Modifier.padding(16.dp)){
                        Text(report.optString("process","QQ"),style=MaterialTheme.typography.titleMedium)
                        Text(if(report.optBoolean("connected"))"进程已连接" else "进程已结束或等待重新登记",style=MaterialTheme.typography.bodySmall)
                        val modules=report.obj("modules")
                        modules.keys().forEach{Text("${moduleNames[it]?:it} · ${modules.optString(it)}")}
                        for(id in FeatureCatalog.ALL.map{it.id}.filter{policy.obj("features").obj(it).optBoolean("enabled")}){
                            val f=report.obj("features").obj(id);Text("${FeatureCatalog.get(id)?.title?:id}：${featureStatus(f,policy.obj("features").obj(id).optBoolean("enabled"))}")
                            val providers=f.obj("providers");providers.keys().forEach{m->val p=providers.obj(m);if(p.optBoolean("failed"))Text("${moduleNames[m]}：${p.optString("error").let{if(it=="NeedsConfiguration")"需要先在原模块配置参数" else it.ifEmpty{"上次初始化失败"}}}",color=MaterialTheme.colorScheme.error)}
                        }
                    }}}
                }
                else->FeatureScreen(Modifier.padding(padding),route,policy,host,::save)
            }
            }
        }
    }

    @Composable private fun NavigationCard(title:String,subtitle:String,onClick:()->Unit) {
        Card(onClick=onClick,modifier=Modifier.fillMaxWidth()) {Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(6.dp));Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("›",fontSize=28.sp)}}
    }

    private fun featureStatus(state:JSONObject,enabled:Boolean):String {
        if(!enabled && state.optString("phase")!="restart_required")return "未接管"
        return when(val phase=state.optString("phase")) {
            "active"->"当前由 ${moduleNames[state.optString("owner")]?:"备用实现"} 负责"
            "restart_required"->"已安排变更，重启 QQ 后生效"
            "unavailable"->if(state.obj("providers").keys().asSequence().any{state.obj("providers").obj(it).optString("error")=="NeedsConfiguration"})"请先配置参数，再安排重试" else "所有实现均不可用"
            "unsupported_qq"->"当前 QQ 版本尚未适配"
            "host_adapter_unavailable"->"无法接入 QQ，请查看运行状态"
            "managed_in_main"->"由 QQ 主进程统一处理"
            "incompatible_adapter"->"存在未适配的模块，请先替换适配版"
            "waiting","manual",""->"等待模块准备完成"
            else->if(phase.startsWith("initializing:"))"正在初始化 ${moduleNames[phase.substringAfter(':')]}" else phase
        }
    }

    @Composable private fun FeatureScreen(modifier:Modifier,id:String,policy:JSONObject,host:JSONObject,save:((JSONObject)->Unit)->Unit) {
        val definition=FeatureCatalog.get(id)?:return
        val configuration=policy.obj("features").obj(id)
        val defaults=definition.modules()
        val stored=configuration.optJSONArray("order").strings().filter{it in defaults}.distinct()
        var order by remember(id,policy.optLong("revision")){mutableStateOf(stored+defaults.filter{it !in stored})}
        val list=rememberLazyListState()
        val drag=rememberReorderState(list,{from,to->
            val first=order.indexOf(from);val target=order.indexOf(to)
            if(first>=0 && target>=0)order=order.toMutableList().apply{add(target,removeAt(first))}
        },{})
        val state=host.obj("features").obj(id)
        fun update(enabled:Boolean=configuration.optBoolean("enabled"),retry:Boolean=false){save{root->
            val fs=root.obj("features");val f=fs.obj(id);f.put("enabled",enabled);f.put("order",JSONArray(order))
            if(enabled)f.put("participants",JSONArray(order))
            if(retry)f.put("retryEpoch",f.optInt("retryEpoch")+1)
            fs.put(id,f);root.put("features",fs)
        }}
        drag.onFinish={update()}
        LazyColumn(modifier.padding(horizontal=20.dp).reorderViewport(drag),state=list,userScrollEnabled=drag.activeKey==null,verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=28.dp)){
            item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("由QIII统一接管",style=MaterialTheme.typography.titleMedium);Text(featureStatus(state,configuration.optBoolean("enabled")),style=MaterialTheme.typography.bodySmall)};Switch(configuration.optBoolean("enabled"),onCheckedChange={update(it)},enabled=state.obj("providers").length()>0)}}
            item{Text("按住右侧手柄上下拖动，越靠上越优先。",style=MaterialTheme.typography.bodyMedium)}
            items(order,key={it}){module->
                val p=state.obj("providers").obj(module)
                Card(modifier=Modifier.animateItem(placementSpec=if(drag.isMoving(module))null else spring(stiffness=420f)).dragAppearance(drag,module)){
                    Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
                        Text("${order.indexOf(module)+1}",color=MaterialTheme.colorScheme.primary,modifier=Modifier.width(28.dp))
                        Column(Modifier.weight(1f)){Text(moduleNames[module]?:module,style=MaterialTheme.typography.titleMedium);Text(when{!state.obj("providers").has(module)->"未连接适配接口";p.optBoolean("failed")->if(p.optString("error")=="NeedsConfiguration")"需要先在原模块配置" else "适配失败，已跳过";state.optString("owner")==module->"正在负责此功能";!p.optBoolean("ready")->"等待准备";else->"备用实现"},style=MaterialTheme.typography.bodySmall)}
                        DragHandle(drag,module)
                    }
                }
            }
            item{Text(definition.detail,style=MaterialTheme.typography.bodyMedium)}
            item{Text(if(definition.coldSwitch())"开启、关闭和调整优先级后，完全重启 QQ 生效。" else "消息角标的提供方可以即时切换。",style=MaterialTheme.typography.bodyMedium)}
            item{OutlinedButton(onClick={update(retry=true)}){Text("安排重启后重试失败实现")}}
            item{Text("格式、颜色、路径等细项请到所选模块配置。关闭接管后恢复原模块开关。",style=MaterialTheme.typography.bodySmall)}
        }
    }

    @Composable private fun MenuScreen(modifier:Modifier,policy:JSONObject,catalog:JSONObject,host:JSONObject,save:((JSONObject)->Unit)->Unit) {
        var chat by remember{mutableStateOf("group")}
        var kind by remember{mutableStateOf("text")}
        var edit by remember{mutableStateOf<String?>(null)}
        var hint by remember{mutableStateOf(false)}
        fun open(type:String){kind=type;val profile="$chat/$type";if(catalog.obj(profile).optJSONArray("items")==null)hint=true else edit=profile}
        LazyColumn(modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=28.dp)){
            item{Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("统一管理长按菜单",style=MaterialTheme.typography.titleMedium);Text("保存后，下次在 QQ 长按消息时应用。",style=MaterialTheme.typography.bodySmall)};Switch(policy.optBoolean("menusEnabled"),onCheckedChange={v->save{it.put("menusEnabled",v)}})}}
            item{OutlinedButton(onClick={open(kind)},enabled=catalog.obj("$chat/$kind").optJSONArray("items")!=null){Text("编辑当前菜单")}}
            item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){for((key,label)in listOf("group" to "群聊","private" to "私聊"))FilterChip(chat==key,onClick={chat=key},label={Text(label)})}}
            item{LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(kindNames.keys.toList()){key->FilterChip(kind==key,onClick={kind=key},label={Text(kindNames[key]!!)})}}}
            item{Text("长按下面的模拟消息，编辑对应菜单",style=MaterialTheme.typography.titleMedium)}
            items(listOf(kind,"text","image").distinct(),key={it}){type->SampleBubble(type,chat=="group"){open(type)}}
            item{Text("QIII自带的翻译和识图可直接设置。QQ 与其他模块项目：开启上方开关，去 QQ 对所选类型的消息长按一次，再回到这里。翻译仅在消息含文字时出现，识图仅在消息含普通图片时出现。",style=MaterialTheme.typography.bodyMedium)}
            item{Text("已接收配置 ${host.optLong("revision")} / 保存配置 ${policy.optLong("revision")}",style=MaterialTheme.typography.bodySmall)}
            if(host.obj("hostErrors").has("menu"))item{Text("当前无法接入 QQ 菜单，请查看运行状态。原菜单处理继续保留。",color=MaterialTheme.colorScheme.error)}
            if(host.optJSONArray("incompatibleModules").strings().isNotEmpty())item{Text("部分模块尚未安装适配版，菜单暂不接管。请先替换主页面列出的模块。",color=MaterialTheme.colorScheme.error)}
        }
        if(hint)AlertDialog(onDismissRequest={hint=false},confirmButton={TextButton(onClick={hint=false}){Text("知道了")}},title={Text("还没有采集到菜单")},text={Text("请先开启统一管理，再到 QQ 的${if(chat=="group")"群聊" else "私聊"}长按一条${kindNames[kind]}消息。")})
        edit?.let{profile->MenuEditor(profile,policy,catalog.obj(profile),save){edit=null}}
    }

    @Composable private fun SampleBubble(kind:String,group:Boolean,onLongClick:()->Unit) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("${kindNames[kind]}消息",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape=RoundedCornerShape(18.dp)){
                QQMessagePreview(kind,group,Modifier.fillMaxWidth(),onLongClick)
            }
        }
    }

    @Composable private fun MenuEditor(profile:String,policy:JSONObject,snapshot:JSONObject,save:((JSONObject)->Unit)->Unit,dismiss:()->Unit) {
        val list=rememberLazyListState()
        val all=snapshot.optJSONArray("items")?:JSONArray()
        val rule=policy.obj("menus").obj(profile)
        val definitions=remember(profile,snapshot.toString()){(0 until all.length()).map{all.getJSONObject(it)}}
        fun rowKey(entry:JSONObject)=entry.getString("key")+if(entry.optBoolean("editable",true))"" else "#${definitions.indexOf(entry)}"
        val configured=rule.optJSONArray("order").strings()
        var entries by remember(profile,snapshot.toString(),policy.optLong("revision")){mutableStateOf(definitions.sortedBy{configured.indexOf(it.optString("key")).let{i->if(i<0)Int.MAX_VALUE else i}})}
        var hidden by remember(profile,policy.optLong("revision")){mutableStateOf(rule.optJSONArray("hidden").strings().toSet())}
        fun commit(){save{root->val menus=root.obj("menus");menus.put(profile,JSONObject().put("order",JSONArray(entries.map{it.getString("key")})).put("hidden",JSONArray(hidden.toList())));root.put("menus",menus)}}
        val drag=rememberReorderState(list,{from,to->
            val first=entries.indexOfFirst{rowKey(it)==from};val target=entries.indexOfFirst{rowKey(it)==to}
            if(first>=0 && target>=0)entries=entries.toMutableList().apply{add(target,removeAt(first))}
        },::commit)
        ModalBottomSheet(onDismissRequest=dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),sheetGesturesEnabled=drag.activeKey==null){
            Column(Modifier.padding(horizontal=20.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){Text("${if(profile.startsWith("group"))"群聊" else "私聊"} · ${kindNames[profile.substringAfter('/')]}",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick={save{val menus=it.obj("menus");menus.remove(profile);it.put("menus",menus)}}){Text("恢复默认")}}
                Text("开关控制显示，拖拽手柄调整顺序。这里的项目不会执行聊天动作。",style=MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max=520.dp).reorderViewport(drag),state=list,userScrollEnabled=drag.activeKey==null,contentPadding=PaddingValues(vertical=12.dp)){
                    items(entries,key={rowKey(it)}){entry->
                        val key=entry.getString("key");val editable=entry.optBoolean("editable",true)
                        Row(Modifier.animateItem(placementSpec=if(drag.isMoving(rowKey(entry)))null else spring(stiffness=420f)).dragAppearance(drag,rowKey(entry)).fillMaxWidth().height(72.dp).alpha(if(hidden.contains(key))0.5f else 1f),verticalAlignment=Alignment.CenterVertically){
                            Checkbox(!hidden.contains(key),onCheckedChange={visible->hidden=if(visible)hidden-key else hidden+key;commit()},enabled=editable)
                            Column(Modifier.weight(1f)){Text(entry.optString("title"),maxLines=1);Text(if(!editable)"同一标识有多个项目，暂不单独调整" else moduleNames[entry.optString("source")]?:"模块项目",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            if(editable)DragHandle(drag,rowKey(entry))
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Modifier.dragAppearance(drag:ReorderState,key:String):Modifier {
        val active=drag.activeKey==key
        val scale by animateFloatAsState(if(active)1.025f else 1f,spring(stiffness=450f),label="拖拽抬起")
        val elevation by animateDpAsState(if(active)10.dp else 0.dp,label="拖拽阴影")
        return this.zIndex(if(drag.isMoving(key))1f else 0f).graphicsLayer{translationY=drag.translation(key);scaleX=scale;scaleY=scale}.shadow(elevation,RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface,RoundedCornerShape(14.dp))
    }

    @Composable private fun DragHandle(drag:ReorderState,key:String) {
        Text("☰",fontSize=24.sp,modifier=Modifier.reorderHandle(drag,key).padding(14.dp))
    }
}
