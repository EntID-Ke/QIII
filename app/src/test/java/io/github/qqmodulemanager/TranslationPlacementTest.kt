package io.github.qqmodulemanager

import android.view.View
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h800dp-mdpi")
class TranslationPlacementTest {
    @Test fun translatedCardOccupiesRealRowHeightAndKeepsFooterBelowIt() {
        val context=RuntimeEnvironment.getApplication();val root=ConstraintLayout(context)
        val bubble=View(context).apply{id=View.generateViewId()}
        root.addView(bubble,ConstraintLayout.LayoutParams(160,50).apply{topToTop=0;endToEnd=0})
        val footer=View(context).apply{id=View.generateViewId()}
        root.addView(footer,ConstraintLayout.LayoutParams(100,20).apply{topToBottom=bubble.id;endToEnd=bubble.id})
        val overlay=View(context);root.addView(overlay,ConstraintLayout.LayoutParams(10,10).apply{topToTop=bubble.id;bottomToBottom=bubble.id;endToEnd=bubble.id})
        val card=TranslationCard(context){}.apply{show("一条较长的译文，应该在原气泡下方占据自己的空间。","✓ 由 微软 提供翻译支持",false)}
        val placement=TranslationPlacement(root,bubble,true);placement.attach(card)
        root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));root.layout(0,0,400,root.measuredHeight)
        assertTrue(card.top>=bubble.bottom);assertEquals(bubble.right,card.right);assertTrue(footer.top>=card.bottom);assertTrue(root.height>=footer.bottom)
        assertEquals(bubble.id,(overlay.layoutParams as ConstraintLayout.LayoutParams).topToTop)
        placement.detach();assertNull(card.parent);assertEquals(bubble.id,(footer.layoutParams as ConstraintLayout.LayoutParams).topToBottom)
    }
    @Test fun nativeFooterRebindingAndStubReplacementAreRestoredWhenTheRowIsRecycled() {
        val context=RuntimeEnvironment.getApplication();val root=ConstraintLayout(context);val bubble=View(context).apply{id=View.generateViewId()}
        root.addView(bubble,ConstraintLayout.LayoutParams(100,50).apply{topToTop=0;startToStart=0})
        val footer=FrameLayout(context);root.addView(footer,ConstraintLayout.LayoutParams(100,20).apply{topToBottom=bubble.id;startToStart=bubble.id})
        val card=TranslationCard(context){};val placement=TranslationPlacement(root,bubble,false);placement.attach(card)
        val replacement=View(context);val params=footer.layoutParams;root.removeView(footer);root.addView(replacement,params)
        placement.detach();assertEquals(bubble.id,(replacement.layoutParams as ConstraintLayout.LayoutParams).topToBottom)
        placement.attach(card);(replacement.layoutParams as ConstraintLayout.LayoutParams).topToBottom=bubble.id;placement.attach(card)
        assertEquals(card.id,(replacement.layoutParams as ConstraintLayout.LayoutParams).topToBottom)
        assertEquals(bubble.id,(card.layoutParams as ConstraintLayout.LayoutParams).startToStart)
    }
}
