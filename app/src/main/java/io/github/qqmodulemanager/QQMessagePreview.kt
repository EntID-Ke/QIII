package io.github.qqmodulemanager

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Use QQ's installed, unskinned default assets. No QQ code, chat data, or callbacks are loaded. */
@Composable
internal fun QQMessagePreview(kind: String, group: Boolean, modifier: Modifier = Modifier, onLongClick: () -> Unit) {
    val currentClick=rememberUpdatedState(onLongClick)
    key(kind,group) {
        AndroidView(modifier=modifier, factory={ context -> PreviewMessages(context,kind,group){currentClick.value()} })
    }
}

private class PreviewMessages(context: Context,kind: String,group: Boolean,onLongClick: () -> Unit):LinearLayout(context) {
    private val qq = try {
        val config=Configuration(context.resources.configuration).apply { uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO }
        context.createPackageContext("com.tencent.mobileqq",0).createConfigurationContext(config)
    } catch (_:android.content.pm.PackageManager.NameNotFoundException) { null }
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    private fun asset(vararg names:String):Drawable? {
        val host=qq?:return null
        for(name in names) {
            val id=host.resources.getIdentifier(name,"drawable","com.tencent.mobileqq")
            if(id!=0)try{return host.resources.getDrawable(id,host.theme)}catch(_:android.content.res.Resources.NotFoundException){}
        }
        return null
    }
    private fun shape(color:Int,radius:Int=12)=GradientDrawable().apply {setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun text(value:String,size:Float,color:Int)=TextView(context).apply {text=value;textSize=size;setTextColor(color);includeFontPadding=false}
    init {
        orientation=VERTICAL;setPadding(dp(12),dp(18),dp(12),dp(20));setBackgroundColor(0xfff1f2f5.toInt())
        addView(text("10:24",12f,0xff92959b.toInt()).apply{gravity=Gravity.CENTER},LayoutParams(-1,dp(26)))
        for(outgoing in listOf(false,true)) {
            val row=LinearLayout(context).apply {orientation=HORIZONTAL;gravity=if(outgoing)Gravity.RIGHT or Gravity.TOP else Gravity.LEFT or Gravity.TOP;setPadding(0,dp(12),0,dp(4))}
            val avatar=text(if(outgoing)"我" else "Q",18f,Color.WHITE).apply {gravity=Gravity.CENTER;background=shape(if(outgoing)0xff7397bb.toInt() else 0xff93b6b9.toInt(),20)}
            val message=LinearLayout(context).apply {orientation=VERTICAL;gravity=if(outgoing)Gravity.RIGHT else Gravity.LEFT}
            if(group && !outgoing)message.addView(text("示例群成员",12f,0xff93959b.toInt()).apply{setPadding(dp(2),0,0,dp(6))})
            val body=messageBody(kind,outgoing)
            if(body.layoutParams==null)body.layoutParams=LayoutParams(-2,-2)
            body.isLongClickable=true;body.setOnLongClickListener{onLongClick();true};body.contentDescription="${if(outgoing)"发出" else "收到"}的${kind}模拟消息，长按管理菜单"
            message.addView(body)
            if(outgoing) {
                row.addView(message,LayoutParams(0,-2,1f).apply{marginEnd=dp(8)})
                row.addView(avatar,LayoutParams(dp(38),dp(38)))
            }else {
                row.addView(avatar,LayoutParams(dp(38),dp(38)))
                row.addView(message,LayoutParams(0,-2,1f).apply{marginStart=dp(8)})
            }
            addView(row,LayoutParams(-1,-2))
        }
    }
    private fun bubble(outgoing:Boolean)=asset(if(outgoing)"skin_aio_user_bubble_nor" else "skin_aio_friend_bubble_nor")
        ?:shape(if(outgoing)0xff0099ff.toInt() else Color.WHITE)
    private fun messageBody(kind:String,outgoing:Boolean):View {
        val color=if(outgoing)Color.WHITE else 0xff101014.toInt()
        return when(kind) {
            "image","video" -> FrameLayout(context).apply {
                layoutParams=LayoutParams(dp(150),dp(188));background=shape(0xffb4d1df.toInt(),10);clipToOutline=true
                addView(Landscape(context),FrameLayout.LayoutParams(-1,-1))
                if(kind=="video") {
                    addView(text("▶",27f,Color.WHITE).apply{gravity=Gravity.CENTER;background=shape(0x66000000,24)},FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER))
                    addView(text("00:12",12f,Color.WHITE).apply{setPadding(dp(10),dp(8),dp(10),dp(8));background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0x88000000.toInt()))},FrameLayout.LayoutParams(-1,dp(34),Gravity.BOTTOM))
                }
            }
            "voice" -> LinearLayout(context).apply {
                gravity=Gravity.CENTER_VERTICAL;orientation=HORIZONTAL;background=bubble(outgoing);setPadding(dp(16),dp(11),dp(16),dp(11));minimumHeight=dp(44)
                val wave=asset(if(outgoing)"skin_aio_ptt_action_r_3" else "skin_aio_ptt_action_l_3")
                val icon=ImageView(context).apply {setImageDrawable(wave);if(wave==null)setImageResource(android.R.drawable.ic_btn_speak_now)}
                if(!outgoing)addView(icon,LayoutParams(dp(20),dp(24)))
                addView(text("12″",16f,color).apply{gravity=if(outgoing)Gravity.RIGHT else Gravity.LEFT},LayoutParams(dp(70),-2).apply{marginStart=dp(9);marginEnd=dp(9)})
                if(outgoing)addView(icon,LayoutParams(dp(20),dp(24)))
            }
            "file" -> LinearLayout(context).apply {
                orientation=VERTICAL;layoutParams=LayoutParams(dp(225),-2);background=shape(Color.WHITE,12);setPadding(dp(14),dp(15),dp(14),dp(10))
                val line=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
                val labels=LinearLayout(context).apply{orientation=VERTICAL}
                labels.addView(text("示例文档.pdf",16f,0xff17181c.toInt()).apply{maxLines=2})
                labels.addView(text("1.2 MB",12f,0xff9c9ea4.toInt()).apply{setPadding(0,dp(10),0,0)})
                line.addView(labels,LayoutParams(0,-2,1f))
                line.addView(ImageView(context).apply{setImageDrawable(asset("filelook_pdf","guild_file_pdf"))},LayoutParams(dp(46),dp(50)).apply{marginStart=dp(12)})
                addView(line);addView(View(context).apply{setBackgroundColor(0xfff1f1f4.toInt())},LayoutParams(-1,dp(1)).apply{topMargin=dp(14);bottomMargin=dp(9)})
                addView(text(if(outgoing)"已发送" else "已下载",11f,0xffa4a6ac.toInt()))
            }
            else -> text(if(outgoing)"长按消息，整理你的菜单。" else "这是一条用于预览的消息。",16f,color).apply {
                maxWidth=dp(235);background=bubble(outgoing);setPadding(dp(16),dp(12),dp(16),dp(12));minimumHeight=dp(43)
            }
        }
    }
}

private class Landscape(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas) {
        val w=width.toFloat();val h=height.toFloat()
        paint.shader=LinearGradient(0f,0f,0f,h,0xffc0dbe7.toInt(),0xffe7e5db.toInt(),Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,w,h,paint);paint.shader=null
        paint.color=0xfff8e8bc.toInt();canvas.drawCircle(w*.75f,h*.24f,w*.1f,paint)
        paint.color=0xff9aafb4.toInt();canvas.drawPath(Path().apply{moveTo(0f,h*.7f);lineTo(w*.45f,h*.28f);lineTo(w,h*.78f);lineTo(w,h);lineTo(0f,h);close()},paint)
        paint.color=0xff6f9395.toInt();canvas.drawPath(Path().apply{moveTo(0f,h*.84f);lineTo(w*.34f,h*.6f);lineTo(w*.7f,h*.83f);lineTo(w,h*.66f);lineTo(w,h);lineTo(0f,h);close()},paint)
    }
}
