package io.github.qqmodulemanager

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Track the finger in viewport coordinates, independently of the row's changing list position. */
internal class ReorderState(val list:LazyListState,private val edge:Float,private val speed:Float) {
    var activeKey by mutableStateOf<String?>(null);private set
    var settlingKey by mutableStateOf<String?>(null);private set
    var settling by mutableStateOf<Animatable<Float,AnimationVector1D>?>(null);private set
    private var releaseTop=0f
    var coordinates:LayoutCoordinates?=null
    var onMove:(String,String)->Unit={_,_->}
    var onFinish:()->Unit={}
    private var finger by mutableFloatStateOf(0f)
    private var grab=0f
    private var direction=0
    private var pendingIndex:Int?=null
    private var moved=false
    private fun item(key:String?)=list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==key}
    private fun top(offset:Int)=offset+list.layoutInfo.beforeContentPadding
    private fun visualTop():Float {
        val height=item(activeKey)?.size?:0
        return (finger-grab).coerceIn(0f,(list.layoutInfo.viewportSize.height-height).coerceAtLeast(0).toFloat())
    }
    fun isMoving(key:String)=activeKey==key || settlingKey==key
    fun translation(key:String):Float {
        val row=item(key)?:return 0f
        return when(key){activeKey->visualTop()-top(row.offset);settlingKey->(releaseTop-top(row.offset))*(1f-(settling?.value?:1f));else->0f}
    }
    fun start(key:String,rootY:Float) {
        val row=item(key)?:return
        val origin=coordinates?.localToRoot(androidx.compose.ui.geometry.Offset.Zero)?.y?:return
        finger=rootY-origin;grab=finger-top(row.offset)
        settlingKey=null;settling=null;activeKey=key;direction=0;pendingIndex=null;moved=false
    }
    fun drag(rootY:Float) {
        if(activeKey==null)return
        val origin=coordinates?.localToRoot(androidx.compose.ui.geometry.Offset.Zero)?.y?:return
        val next=rootY-origin
        if(abs(next-finger)>.1f)direction=if(next>finger)1 else -1
        finger=next;reorder()
    }
    private fun reorder() {
        val key=activeKey?:return
        val row=item(key)?:return
        if(pendingIndex!=null && row.index!=pendingIndex)return
        pendingIndex=null
        val center=visualTop()+row.size/2f
        val candidates=list.layoutInfo.visibleItemsInfo.filter{
            it.key is String && it.key!=key && if(direction>0)it.index>row.index && center>top(it.offset)+it.size/2f
            else direction<0 && it.index<row.index && center<top(it.offset)+it.size/2f
        }
        val target=if(direction>0)candidates.maxByOrNull{it.index}else candidates.minByOrNull{it.index}
        if(target!=null){
            // Otherwise LazyColumn follows the old first item's key when it changes position.
            if(row.index==list.firstVisibleItemIndex || target.index==list.firstVisibleItemIndex)
                list.requestScrollToItem(list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset)
            pendingIndex=target.index;onMove(key,target.key as String);moved=true
        }
    }
    fun scrollStep(seconds:Float):Float {
        if(activeKey==null)return 0f
        val height=list.layoutInfo.viewportSize.height.toFloat()
        val penetration=when{finger<edge->(finger-edge)/edge;finger>height-edge->(finger-height+edge)/edge;else->0f}
        return penetration.coerceIn(-1f,1f)*speed*seconds
    }
    suspend fun scroll(seconds:Float) {
        val step=scrollStep(seconds)
        if(step!=0f)list.scroll(MutatePriority.UserInput){if(scrollBy(step)!=0f)direction=if(step>0)1 else -1}
        reorder()
    }
    fun finish() {
        if(activeKey==null)return
        releaseTop=visualTop();settlingKey=activeKey;settling=Animatable(0f)
        activeKey=null;pendingIndex=null
        if(moved)onFinish()
    }
    fun settled(){settlingKey=null;settling=null}
}

@Composable
internal fun rememberReorderState(list:LazyListState,onMove:(String,String)->Unit,onFinish:()->Unit):ReorderState {
    val density=LocalDensity.current
    val state=remember(list,density){ReorderState(list,with(density){56.dp.toPx()},with(density){720.dp.toPx()})}
    state.onMove=onMove;state.onFinish=onFinish
    LaunchedEffect(state.settling) {
        state.settling?.animateTo(1f,spring(dampingRatio=.8f,stiffness=420f))
        if(state.activeKey==null)state.settled()
    }
    LaunchedEffect(state.activeKey) {
        if(state.activeKey!=null) {
            var previous=withFrameNanos{it}
            while(state.activeKey!=null) {
                val seconds=withFrameNanos{time->((time-previous)/1_000_000_000f).coerceIn(0f,.05f).also{previous=time}}
                state.scroll(seconds)
            }
        }
    }
    return state
}

internal fun Modifier.reorderViewport(state:ReorderState)=onGloballyPositioned{state.coordinates=it}

internal fun Modifier.reorderHandle(state:ReorderState,key:String):Modifier = composed {
    var coordinates by remember{mutableStateOf<LayoutCoordinates?>(null)}
    this.onGloballyPositioned{coordinates=it}.testTag("reorder:$key").semantics{contentDescription="拖拽调整顺序"}.pointerInput(state,key){
        detectDragGestures(orientationLock=null,onDragStart={down,_,_->coordinates?.let{state.start(key,it.localToRoot(down.position).y)}},onDragEnd={state.finish()},onDragCancel=state::finish){change,_->
            change.consume();coordinates?.let{state.drag(it.localToRoot(change.position).y)}
        }
    }
}
