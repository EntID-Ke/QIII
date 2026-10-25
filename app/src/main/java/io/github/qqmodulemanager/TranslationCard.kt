package io.github.qqmodulemanager

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Native Views avoid importing the module's Compose/AndroidX classes into QQ's layout. */
internal class TranslationCard(context:Context,actions:(View)->Unit):LinearLayout(context) {
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    private val dark=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    val translation=TextView(context).apply{setTextColor(if(dark)0xffeeeeee.toInt() else 0xff202124.toInt());textSize=16f;setTextIsSelectable(true);includeFontPadding=false}
    val source=TextView(context).apply{setTextColor(if(dark)0xff999999.toInt() else 0xff7b7e83.toInt());textSize=11f;includeFontPadding=false;setPadding(0,dp(9),0,0);contentDescription="翻译来源与操作"}
    init {
        id=View.generateViewId();orientation=VERTICAL;gravity=Gravity.START
        setPadding(dp(14),dp(12),dp(14),dp(12))
        background=GradientDrawable().apply{cornerRadius=dp(10).toFloat();setColor(if(dark)0xff2c2c2c.toInt() else 0xfff0f1f3.toInt())}
        addView(translation,LayoutParams(-2,-2));addView(source,LayoutParams(-2,-2))
        source.setOnClickListener{actions(it)};setOnLongClickListener{actions(it);true}
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val windowWidth=resources.configuration.screenWidthDp.takeIf{it>0}?.let{it*resources.displayMetrics.density}?:resources.displayMetrics.widthPixels.toFloat()
        val maximum=minOf((windowWidth*.72f).toInt(),View.MeasureSpec.getSize(widthMeasureSpec).takeIf{it>0}?:Int.MAX_VALUE)
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(maximum,View.MeasureSpec.AT_MOST),heightMeasureSpec)
    }
    fun show(text:String,attribution:String,loading:Boolean) {
        translation.text=text.ifEmpty{if(loading)"翻译中…" else "翻译失败"}
        source.text=attribution
        contentDescription="${translation.text}，$attribution"
    }
}

/** Adds a sibling below the native bubble. Native tail/recommendation rows follow the card. */
internal class TranslationPlacement(root:ViewGroup,bubble:View,private val outgoing:Boolean) {
    private val root=WeakReference(root)
    private val bubble=WeakReference(bubble)
    private var card=WeakReference<TranslationCard>(null)
    private fun set(params:ViewGroup.LayoutParams,name:String,value:Int){params.javaClass.getField(name).setInt(params,value)}
    fun attach(view:TranslationCard) {
        val root=root.get()?:return;val bubble=bubble.get()?:return
        if(bubble.parent!==root)throw IllegalStateException("当前消息不是已适配的 QQ 气泡布局")
        val attaching=view.parent!==root
        if(attaching) {
            val params=bubble.layoutParams.javaClass.getConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).newInstance(-2,-2) as ViewGroup.MarginLayoutParams
            set(params,"topToBottom",bubble.id);set(params,if(outgoing)"endToEnd" else "startToStart",bubble.id)
            params.topMargin=(6*root.resources.displayMetrics.density+.5f).toInt()
            root.addView(view,params);card=WeakReference(view)
        }
        // Only move footer siblings; overlays anchored to the bubble stay attached to it.
        for(i in 0 until root.childCount) {
            val child=root.getChildAt(i);val p=child.layoutParams
            if(child!==view && runCatching{p.javaClass.getField("topToBottom").getInt(p)}.getOrDefault(-1)==bubble.id) {
                set(p,"topToBottom",view.id);child.layoutParams=p
            }
        }
        if(attaching){view.alpha=0f;view.animate().alpha(1f).setDuration(160).start()}
    }
    fun detach() {
        val root=root.get()?:return;val bubble=bubble.get()?:return;val old=card.get()
        for(i in 0 until root.childCount){val child=root.getChildAt(i);val p=child.layoutParams;if(child!==old && runCatching{p.javaClass.getField("topToBottom").getInt(p)}.getOrDefault(-1)==old?.id){set(p,"topToBottom",bubble.id);child.layoutParams=p}}
        if(old?.parent===root)root.removeView(old);card.clear()
    }
}
