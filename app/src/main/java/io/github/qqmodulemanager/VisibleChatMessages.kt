package io.github.qqmodulemanager

import android.graphics.Rect
import android.view.View

/** Snapshot the current chat's visible bubbles before translation cards change row heights. */
internal fun visibleChatMessages(candidates:List<Pair<ChatMessage,View>>,window:View):List<ChatMessage> {
    val frame=Rect();window.getWindowVisibleDisplayFrame(frame)
    val offset=IntArray(2);window.rootView.getLocationOnScreen(offset);frame.offset(-offset[0],-offset[1])
    return candidates.mapNotNull{(message,bubble)->
        val bounds=Rect()
        if(message.text.isBlank() || !bubble.isAttachedToWindow || !bubble.isShown || bubble.rootView!==window.rootView || !bubble.getGlobalVisibleRect(bounds) || !bounds.intersect(frame))null
        else Triple(message,bounds.top,bounds.left)
    }.sortedWith(compareBy({it.second},{it.third})).map{it.first}.distinctBy{it.key}
}
