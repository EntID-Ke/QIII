@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.qqmodulemanager

import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReorderGestureTest {
    @get:Rule(order=0) val activityManifest=object:ExternalResource() {
        override fun before(){val app=RuntimeEnvironment.getApplication();Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app,ComponentActivity::class.java))}
    }
    @get:Rule(order=1) val compose=createComposeRule()
    private var entries by mutableStateOf((0 until 30).map{"item-$it"})
    private lateinit var drag:ReorderState
    private var commits=0
    private fun screen() {
        compose.setContent {
            val list=rememberLazyListState()
            drag=rememberReorderState(list,{from,to->
                val old=entries.indexOf(from);val target=entries.indexOf(to)
                entries=entries.toMutableList().apply{add(target,removeAt(old))}
            },{commits++})
            LazyColumn(Modifier.size(320.dp,360.dp).reorderViewport(drag).testTag("list"),state=list,userScrollEnabled=drag.activeKey==null,contentPadding=PaddingValues(vertical=12.dp)) {
                items(entries,key={it}){key->
                    Row(Modifier.animateItem(placementSpec=if(drag.isMoving(key))null else spring(stiffness=420f)).graphicsLayer{translationY=drag.translation(key)}.fillMaxWidth().height(72.dp)) {
                        Text(key,Modifier.weight(1f))
                        Text("☰",Modifier.reorderHandle(drag,key).size(60.dp,72.dp))
                    }
                }
            }
        }
        compose.mainClock.autoAdvance=false
    }
    @Test fun adjacentRowsSwapAndDoNotBounceBackWhileTheFingerIsStill() {
        screen()
        val initialTop=compose.onNodeWithTag("reorder:item-1").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("reorder:item-1").performTouchInput {
            down(center);moveBy(Offset(0f,12f));moveBy(Offset(0f,88f))
        }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle {
            assertEquals(2,entries.indexOf("item-1"));assertEquals("item-2",entries[1]);assertEquals(0,commits)
        }
        assertEquals(initialTop+100f,compose.onNodeWithTag("reorder:item-1").fetchSemanticsNode().boundsInRoot.top,1f)
        compose.onRoot().performTouchInput{up()}
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle{assertEquals(1,commits);assertNull(drag.activeKey)}
    }
    @Test fun holdingPastTheBottomScrollsContinuouslyWithoutReleasingTheRow() {
        screen()
        compose.onNodeWithTag("reorder:item-1").performTouchInput {
            down(center);moveBy(Offset(0f,12f));moveBy(Offset(0f,300f))
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle {
            assertTrue("Lower edge: active=${drag.activeKey}, step=${drag.scrollStep(.016f)}, first=${drag.list.firstVisibleItemIndex}, index=${entries.indexOf("item-1")}, visible=${drag.list.layoutInfo.visibleItemsInfo.map{it.key to it.offset}}",drag.list.firstVisibleItemIndex>0)
            assertEquals("item-1",drag.activeKey);assertTrue(entries.indexOf("item-1")>4);assertEquals(0,commits)
        }
        compose.onRoot().performTouchInput{up()}
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle{assertEquals(1,commits);assertEquals(30,entries.distinct().size)}
    }
    @Test fun holdingPastTheTopScrollsUpAndAllowsDraggingIntoEarlierRows() {
        screen();compose.onNodeWithTag("list").performScrollToIndex(12)
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("reorder:item-13").performTouchInput {
            down(center);moveBy(Offset(0f,-12f));moveBy(Offset(0f,-220f))
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle {
            assertTrue("Upper edge: active=${drag.activeKey}, step=${drag.scrollStep(.016f)}, first=${drag.list.firstVisibleItemIndex}, index=${entries.indexOf("item-13")}, visible=${drag.list.layoutInfo.visibleItemsInfo.map{it.key to it.offset}}",drag.list.firstVisibleItemIndex<12)
            assertEquals("item-13",drag.activeKey);assertTrue(entries.indexOf("item-13")<11)
        }
        compose.onRoot().performTouchInput{up()}
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle{assertEquals(1,commits)}
    }
}
