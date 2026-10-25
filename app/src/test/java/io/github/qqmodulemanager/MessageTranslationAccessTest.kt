package io.github.qqmodulemanager

import android.content.Context
import android.content.ContextWrapper
import com.tencent.mobileqq.aio.msg.AIOMsgItem
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import mqq.app.AppRuntime
import mqq.app.MobileQQ
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MessageTranslationAccessTest {
    class Text(private val content:String){fun getContent()=content}
    class Element(private val text:String):MsgElement(1){fun getTextElement()=Text(text)}
    class Face {fun getFaceIndex()=1;fun getFaceText()="fallback"}
    class FaceElement:MsgElement(6){fun getFaceElement()=Face()}
    class GroupRecord:MsgRecord(2,listOf(Element("group message"))) {
        override fun getPeerUid()=""
        override fun getPeerUin()=654321L
    }
    private fun access(account:String?):QQMessageAccess {
        val app=MobileQQ(account?.let{AppRuntime(it)})
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()){override fun getApplicationContext():Context=app}
        return QQMessageAccess(context,javaClass.classLoader!!)
    }
    @Test fun translationReadsOnlyTheSelectedBodyAndChangesTheFingerprintAfterLocalEditing() {
        val access=access("10001")
        val original=access.read(AIOMsgItem(MsgRecord(1,listOf(Element("hello"),MsgElement(7)))))!!
        val edited=access.read(AIOMsgItem(MsgRecord(1,listOf(Element("changed")))))!!
        assertEquals("hello",original.text);assertEquals("10001",original.account)
        assertFalse(original.args().containsKey("text"));assertEquals("hello",original.args(true).getString("text"))
        assertEquals(original.message,edited.message);assertNotEquals(original.source,edited.source)
        val other=access("10002").read(AIOMsgItem(MsgRecord(1,listOf(Element("hello")))))!!
        assertNotEquals(original.key,other.key)
    }
    @Test fun loggedOutAndUnsupportedChatTypesAreNotAssignedASharedCacheIdentity() {
        assertNull(access(null).read(AIOMsgItem(MsgRecord(1,listOf(Element("hello"))))))
        assertNull(access("10001").read(AIOMsgItem(MsgRecord(4,listOf(Element("hello"))))))
    }
    @Test fun nativeQqFacesUseTheSameTextConversionAsQqsCopyMenu() {
        val message=access("10001").read(AIOMsgItem(MsgRecord(1,listOf(Element("hello "),FaceElement(),Element(" 😀")))))!!
        assertEquals("hello [微笑] 😀",message.text)
    }
    @Test fun aProxyApplicationDoesNotHideTheRealQqRuntimeOrRemoveMenuEligibility() {
        MobileQQ(AppRuntime("10001"))
        val proxy=RuntimeEnvironment.getApplication()
        assertFalse(proxy is MobileQQ)
        val access=QQMessageAccess(proxy,javaClass.classLoader!!)
        val message=access.read(AIOMsgItem(MsgRecord(1,listOf(Element("hello")))))
        assertNotNull("QQ uses a proxy Application; resolve MobileQQ's own singleton",message)
        assertEquals("10001",message!!.account)
        assertEquals("hello",message.text)
    }
    @Test fun aGroupRecordWithOnlyPeerUinStillHasAStableIdentity() {
        val access=access("10001");val message=access.read(AIOMsgItem(GroupRecord()))
        assertNotNull(message);assertEquals("group message",message!!.text)
        assertEquals(message.message,access.read(AIOMsgItem(GroupRecord()))!!.message)
    }
}
