package io.github.qqmodulemanager

import com.tencent.mobileqq.aio.msg.AIOMsgItem
import com.tencent.qqnt.kernel.api.af
import com.tencent.qqnt.kernel.nativeinterface.*
import com.tencent.qqnt.msg.f
import kotlinx.coroutines.*
import mqq.app.AppRuntime
import mqq.app.MobileQQ
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class QQPictureAccessTest {
    @get:Rule val folder=TemporaryFolder()
    class PictureElement(val picture:PicElement):MsgElement(2){fun getPicElement()=picture}
    private val pic=PicElement()
    private val element=PictureElement(pic)
    private val picture=ChatPicture(MsgRecord(2,listOf(element)),element)
    private val access=QQPictureAccess(javaClass.classLoader!!)
    private fun image()=folder.newFile().apply{writeBytes(byteArrayOf(1,2,3))}.path
    private class Service(val download:(RichMediaElementGetReq,IKernelMsgListener)->Unit={_,_->}):af {
        val listeners=CopyOnWriteArrayList<IKernelMsgListener>()
        val requested=CompletableDeferred<RichMediaElementGetReq>()
        override fun addMsgListener(listener:IKernelMsgListener){listeners.add(listener)}
        override fun removeMsgListener(listener:IKernelMsgListener){assertTrue(listeners.remove(listener))}
        override fun getRichMediaElement(request:RichMediaElementGetReq){requested.complete(request);download(request,listeners.single())}
    }
    private fun finished(request:RichMediaElementGetReq,path:String)=FileTransNotifyInfo().apply{msgId=request.msgId;msgElementId=request.elementId;filePath=path}
    @After fun clean(){f.service=null}

    @Test fun anOrdinaryImageWithEmptyMessagePathsUsesQqsRealCache()=runBlocking {
        val cached=image();pic.resolvedThumbs[720]=cached
        assertEquals("",pic.sourcePath);assertTrue(pic.thumbPath.isEmpty())
        assertEquals(cached,access.prepare(picture))
        assertNull(f.service) // Resolving a cached image does not request a download.
    }
    @Test fun theCacheIsResolvedWhenClickedInsteadOfFreezingAnEmptyMenuTimePath()=runBlocking {
        MobileQQ(AppRuntime("10001"))
        val message=QQMessageAccess(RuntimeEnvironment.getApplication(),javaClass.classLoader!!).read(AIOMsgItem(MsgRecord(2,listOf(element))))!!
        assertEquals(1,message.pictures.size)
        val cached=image();pic.sourcePath=cached
        assertEquals(cached,access.prepare(message.pictures.single()))
    }
    @Test fun aMissingImageIsFetchedThroughQqWithoutOpeningTheImageViewer()=runBlocking {
        val downloaded=image();val progress=ArrayList<String>()
        val service=Service{req,listener->
            assertEquals(2,req.chatType);assertEquals("peer",req.peerUid)
            assertEquals(2,req.downloadType);assertEquals(720,req.thumbSize)
            listener.onRecvMsg();listener.onRichMediaDownloadComplete(finished(req,downloaded))
        };f.service=service
        assertEquals(downloaded,access.prepare(picture,progress={progress.add(it)}))
        assertEquals(listOf("正在从 QQ 获取图片…"),progress);assertTrue(service.listeners.isEmpty())
    }
    @Test fun anotherMessageOrAnotherPictureCannotSupplyTheSelectedImage()=runBlocking {
        val correct=image();val wrong=image()
        val service=Service{req,listener->
            listener.onRichMediaDownloadComplete(finished(req,wrong).apply{msgId++})
            listener.onRichMediaDownloadComplete(finished(req,wrong).apply{msgElementId++})
            listener.onRichMediaDownloadComplete(finished(req,correct))
        };f.service=service
        assertEquals(correct,access.prepare(picture));assertTrue(service.listeners.isEmpty())
    }
    @Test fun aQqDownloadFailureReportsAnErrorAndRemovesItsListener()=runBlocking {
        val service=Service{req,listener->listener.onRichMediaDownloadComplete(finished(req,"").apply{fileErrCode=1001;trasferStatus=5})};f.service=service
        val error=runCatching{access.prepare(picture)}.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("1001"));assertTrue(service.listeners.isEmpty())
    }
    @Test fun cancellationRemovesTheListenerAndDoesNotCancelQqsOwnTransfer()=runBlocking {
        val service=Service();f.service=service
        val job=launch{access.prepare(picture)};withTimeout(5000){service.requested.await()}
        job.cancelAndJoin();assertTrue(job.isCancelled);assertTrue(service.listeners.isEmpty())
    }
    @Test fun aDownloadTimeoutDoesNotLeaveAListenerBehind()=runBlocking {
        val service=Service();f.service=service
        val error=runCatching{access.prepare(picture,timeoutMillis=10)}.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("超时"));assertTrue(service.listeners.isEmpty())
    }
    @Test fun emptyFilesAreSkippedAndAReadableThumbnailCanReplaceAnOversizedOriginal()=runBlocking {
        pic.resolvedOriginal=folder.newFile().path
        val thumb=image();pic.thumbPath[198]=thumb
        assertEquals(thumb,access.prepare(picture))
        java.io.RandomAccessFile(pic.resolvedOriginal,"rw").use{it.setLength(21L*1024*1024)}
        assertEquals(thumb,access.prepare(picture))
    }
    @Test fun flashImagesAreExcludedWhileMultipleOrdinaryImagesKeepTheirOwnIdentity()=runBlocking {
        MobileQQ(AppRuntime("10001"));val flash=PicElement().apply{this.flash=true}
        val message=QQMessageAccess(RuntimeEnvironment.getApplication(),javaClass.classLoader!!).read(AIOMsgItem(MsgRecord(2,listOf(element,PictureElement(flash),PictureElement(PicElement())))))!!
        assertEquals(2,message.pictures.size);assertSame(element,message.pictures[0].element)
        assertTrue(runCatching{access.prepare(ChatPicture(picture.record,PictureElement(flash)))}.exceptionOrNull()?.message.orEmpty().contains("闪照"))
    }
}
