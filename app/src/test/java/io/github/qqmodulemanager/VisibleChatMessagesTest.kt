package io.github.qqmodulemanager

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h800dp-mdpi")
class VisibleChatMessagesTest {
    private fun message(id:String,text:String="Hello")=ChatMessage("account",id,text,false,emptyList())
    @Test fun onlyVisibleTextInTheCurrentWindowIsSelectedInScreenOrderWithoutDuplicates() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity=controller.get();val root=FrameLayout(activity);activity.setContentView(root)
            fun bubble(top:Int)=View(activity).also{root.addView(it,FrameLayout.LayoutParams(160,60).apply{topMargin=top})}
            val first=bubble(20);val second=bubble(150);val partial=bubble(350);val offscreen=bubble(450)
            val hidden=bubble(60).apply{visibility=View.GONE};val picture=bubble(250);val detached=View(activity)
            val clipped=FrameLayout(activity).apply{clipChildren=true};root.addView(clipped,FrameLayout.LayoutParams(100,50).apply{topMargin=200})
            val clippedBubble=View(activity);clipped.addView(clippedBubble,FrameLayout.LayoutParams(60,40).apply{topMargin=100})
            val foreign=View(activity).apply{layout(0,0,80,40)}
            root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY));root.layout(0,0,400,400)
            val selected=visibleChatMessages(listOf(message("second") to second,message("first") to first,message("duplicate") to hidden,
                message("offscreen") to offscreen,message("image","") to picture,message("partial") to partial,
                message("detached") to detached,message("clipped") to clippedBubble,message("foreign") to foreign,message("first") to first),activity.window.decorView)
            assertEquals(listOf("first","second","partial"),selected.map{it.message})
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun recycledBubblesAreNotSelectedAndWhitespaceMessagesAreExcluded() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity=controller.get();val root=FrameLayout(activity);activity.setContentView(root)
            val removed=View(activity);root.addView(removed,FrameLayout.LayoutParams(100,40));root.removeView(removed)
            val blank=View(activity);root.addView(blank,FrameLayout.LayoutParams(100,40))
            root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY));root.layout(0,0,400,400)
            assertTrue(visibleChatMessages(listOf(message("removed") to removed,message("blank"," \n ") to blank),activity.window.decorView).isEmpty())
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun bubblesBelowTheVisibleWindowFrameAreExcluded() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity=controller.get();val root=FrameLayout(activity);activity.setContentView(root)
            val first=View(activity);root.addView(first,FrameLayout.LayoutParams(100,40).apply{topMargin=40})
            val covered=View(activity);root.addView(covered,FrameLayout.LayoutParams(100,40).apply{topMargin=250})
            val window=object:View(activity){override fun getWindowVisibleDisplayFrame(rect:android.graphics.Rect){rect.set(0,0,400,200)}}
            root.addView(window,FrameLayout.LayoutParams(1,1))
            root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY));root.layout(0,0,400,400)
            assertEquals(listOf("first"),visibleChatMessages(listOf(message("first") to first,message("covered") to covered),window).map{it.message})
        }finally{controller.pause().stop().destroy()}
    }
}
