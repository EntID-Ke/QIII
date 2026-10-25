"""Verify the manager's host ABI using DexSQL 0.1.1, without executing QQ code."""
import argparse, json, subprocess
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--dexsql',required=True)
p.add_argument('--apk',required=True)
p.add_argument('--output',required=True)
args=p.parse_args()
expected={
    'Lcom/tencent/qqnt/kernel/nativeinterface/PicElement;->getMd5HexStr()Ljava/lang/String;',
    'Lcom/tencent/mobileqq/aio/utils/aq;->a(Lcom/tencent/qqnt/kernel/nativeinterface/PicElement;)Ljava/lang/String;',
    'Lcom/tencent/mobileqq/aio/utils/aq;->c(Lcom/tencent/qqnt/kernel/nativeinterface/PicElement;I)Ljava/lang/String;',
    'Lcom/tencent/qqnt/msg/f;->f()Lcom/tencent/qqnt/kernel/api/af;',
    'Lcom/tencent/qqnt/kernel/api/af;->addMsgListener(Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgListener;)V',
    'Lcom/tencent/qqnt/kernel/api/af;->removeMsgListener(Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgListener;)V',
    'Lcom/tencent/qqnt/kernel/api/af;->getRichMediaElement(Lcom/tencent/qqnt/kernel/nativeinterface/RichMediaElementGetReq;)V',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgElement;->getElementId()J',
    'Lcom/tencent/qqnt/kernel/nativeinterface/RichMediaElementGetReq;-><init>()V',
    'Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgListener;->onRichMediaDownloadComplete(Lcom/tencent/qqnt/kernel/nativeinterface/FileTransNotifyInfo;)V',
    'Lcom/tencent/qqnt/kernel/nativeinterface/FileTransNotifyInfo;->getMsgId()J',
    'Lcom/tencent/qqnt/kernel/nativeinterface/FileTransNotifyInfo;->getMsgElementId()J',
    'Lcom/tencent/qqnt/kernel/nativeinterface/FileTransNotifyInfo;->getFileErrCode()J',
    'Lcom/tencent/qqnt/kernel/nativeinterface/FileTransNotifyInfo;->getFilePath()Ljava/lang/String;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/FileTransNotifyInfo;->getTrasferStatus()I',
    'Lmqq/app/MobileQQ;->getMobileQQ()Lmqq/app/MobileQQ;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;->getPeerUin()J',
    'Lcom/tencent/mobileqq/emoticon/QQSysFaceUtil;->convertToLocal(I)I',
    'Lcom/tencent/mobileqq/emoticon/QQSysFaceUtil;->getFaceString(I)Ljava/lang/String;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgElement;->getFaceElement()Lcom/tencent/qqnt/kernel/nativeinterface/FaceElement;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/FaceElement;->getFaceIndex()I',
    'Lcom/tencent/qqnt/kernel/nativeinterface/FaceElement;->getFaceText()Ljava/lang/String;',
    'Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;->isSelf()Z',
    'Lcom/tencent/mobileqq/aio/msglist/holder/AIOBubbleMsgItemVB;->handleUIState(Lcom/tencent/mvi/base/mvi/MviUIState;)V',
    'Lcom/tencent/mobileqq/aio/msglist/holder/AIOBubbleMsgItemVB;->Z1()Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;',
    'Lcom/tencent/mobileqq/aio/msglist/holder/AIOBubbleMsgItemVB;->a2()Landroid/view/View;',
    'Lcom/tencent/mobileqq/aio/msglist/holder/AIOBubbleMsgItemVB;->Y1()Lcom/tencent/mobileqq/aio/msglist/holder/component/BaseContentComponent;',
    'Lcom/tencent/mobileqq/aio/msglist/holder/component/c;->T1()Landroid/view/View;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;->getMsgId()J',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;->getPeerUid()Ljava/lang/String;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgElement;->getTextElement()Lcom/tencent/qqnt/kernel/nativeinterface/TextElement;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgElement;->getPicElement()Lcom/tencent/qqnt/kernel/nativeinterface/PicElement;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/TextElement;->getContent()Ljava/lang/String;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/PicElement;->getIsFlashPic()Ljava/lang/Boolean;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/PicElement;->getSourcePath()Ljava/lang/String;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/PicElement;->getThumbPath()Ljava/util/HashMap;',
    'Lmqq/app/MobileQQ;->peekAppRuntime()Lmqq/app/AppRuntime;',
    'Lmqq/app/AppRuntime;->getCurrentAccountUin()Ljava/lang/String;',
    'Lcom/tencent/qqnt/aio/menu/h;->b()I',
    'Lcom/tencent/qqnt/aio/menu/h;->g()Ljava/lang/String;',
    'Lcom/tencent/qqnt/aio/menu/h;->f()Ljava/lang/String;',
    'Lcom/tencent/qqnt/aio/menu/h;->h()Ljava/lang/String;',
    'Lcom/tencent/qqnt/aio/menu/h;->c()I',
    'Lcom/tencent/qqnt/aio/menu/h;->k()V',
    'Lcom/tencent/qqnt/aio/menu/ui/e;->k()V',
    'Lcom/tencent/mobileqq/login/restart/MainProcessRestartLoadingActivity;-><init>()V',
    'Lcom/tencent/mobileqq/login/restart/MainProcessRestartLoadingActivity;->doOnResume()V',
    'Lcom/tencent/mobileqq/aio/input/draft/InputDraftVMDelegate;->l(Lcom/tencent/mobileqq/aio/input/draft/InputDraftVMDelegate;Ljava/util/List;)V',
    'Lcom/tencent/mobileqq/aio/input/draft/InputDraftVMDelegate;-><init>(Lcom/tencent/mobileqq/aio/input/adorn/c;)V',
    'Lcom/tencent/qqnt/aio/menu/h;-><init>(Landroid/content/Context;Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;)V',
    'Lcom/tencent/qqnt/aio/menu/ui/QQCustomMenuExpandableLayout;->r(ILcom/tencent/qqnt/aio/menu/ui/e;Z[F)Landroid/view/View;',
    'Lcom/tencent/qqnt/aio/menu/ui/QQCustomMenuExpandableLayout;->dismiss()V',
    'Lcom/tencent/qqnt/aio/menu/ui/c;->c()Lcom/tencent/qqnt/aio/menu/ui/c;',
    'Lcom/tencent/qqnt/aio/menu/ui/QQCustomMenuExpandableLayout;->setMenu(Lcom/tencent/qqnt/aio/menu/ui/c;Landroid/view/View;)V',
    'Lcom/tencent/qqnt/aio/menu/ui/QQCustomMenuNoIconLayout;->setMenu(Lcom/tencent/qqnt/aio/menu/ui/c;Landroid/view/View;)V',
    'Lcom/tencent/qqnt/aio/menu/ui/e;->c()I',
    'Lcom/tencent/qqnt/aio/menu/ui/e;->d()Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;',
    'Lcom/tencent/qqnt/aio/menu/ui/e;->h()Ljava/lang/String;',
    'Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;->getMsgRecord()Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;->getChatType()I',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;->getElements()Ljava/util/ArrayList;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgElement;->getElementType()I',
    'Lcom/tencent/mobileqq/quibadge/QUIBadge;->updateNum(I)V',
    'Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession$CppProxy;->onMsfPush(Ljava/lang/String;[BLcom/tencent/qqnt/kernel/nativeinterface/PushExtraInfo;)V',
    'Lcom/tencent/mobileqq/setting/main/b;->f(Landroid/content/Context;)Ljava/util/List;',
    'Lcom/tencent/mobileqq/setting/processor/i;-><init>(Landroid/content/Context;ILjava/lang/CharSequence;ILjava/lang/String;)V',
    'Lcom/tencent/mobileqq/setting/processor/i;->B(Lkotlin/jvm/functions/Function0;)V',
    'Lcom/tencent/mobileqq/setting/processor/b;-><init>(Ljava/util/List;Ljava/lang/CharSequence;Ljava/lang/CharSequence;ILkotlin/jvm/internal/DefaultConstructorMarker;)V',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;->getSenderUin()J',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;->getMsgAttrs()Ljava/util/HashMap;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgAttributeInfo;->getVasMsgInfo()Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgElement;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgElement;->getBubbleInfo()Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgBubble;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgElement;->getVasFont()Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgFont;',
}
expected_fields={
    'Lcom/tencent/qqnt/kernel/nativeinterface/RichMediaElementGetReq;->msgId:J',
    'Lcom/tencent/qqnt/kernel/nativeinterface/RichMediaElementGetReq;->elementId:J',
    'Lcom/tencent/qqnt/kernel/nativeinterface/RichMediaElementGetReq;->chatType:I',
    'Lcom/tencent/qqnt/kernel/nativeinterface/RichMediaElementGetReq;->peerUid:Ljava/lang/String;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/RichMediaElementGetReq;->downloadType:I',
    'Lcom/tencent/qqnt/kernel/nativeinterface/RichMediaElementGetReq;->thumbSize:I',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;->elements:Ljava/util/ArrayList;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;->msgType:I',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgElement;->textElement:Lcom/tencent/qqnt/kernel/nativeinterface/TextElement;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgElement;->picElement:Lcom/tencent/qqnt/kernel/nativeinterface/PicElement;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/TextElement;->atType:I',
    'Lcom/tencent/qqnt/aio/menu/ui/e;->a:Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;',
    'Lcom/tencent/qqnt/aio/menu/h;->e:Ljava/lang/String;',
    'Lcom/tencent/qqnt/aio/menu/ui/c;->a:Ljava/util/List;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;->senderUin:J',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;->msgAttrs:Ljava/util/HashMap;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/MsgAttributeInfo;->vasMsgInfo:Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgElement;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgElement;->bubbleInfo:Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgBubble;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgElement;->vasFont:Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgFont;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgBubble;->bubbleId:Ljava/lang/Integer;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgBubble;->subBubbleId:Ljava/lang/Integer;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgFont;->fontId:Ljava/lang/Integer;',
    'Lcom/tencent/qqnt/kernel/nativeinterface/VASMsgFont;->magicFontType:Ljava/lang/Integer;',
}
def quote(value):return "'"+value.replace("'","''")+"'"
sql='SELECT descriptor FROM methods WHERE method_kind IS NOT NULL AND descriptor IN ('+','.join(map(quote,sorted(expected)))+')'
command=[args.dexsql,'query','--threads','2','--input',args.apk,'--sql',sql,'--timing']
r=subprocess.run(command,capture_output=True,text=True,check=True)
found=[line.split('|')[1].strip() for line in r.stdout.splitlines() if line.startswith('| L')]
records=[dict(descriptor=s,passed=found.count(s)==1) for s in sorted(expected)]
field_sql='SELECT descriptor FROM fields WHERE descriptor IN ('+','.join(map(quote,sorted(expected_fields)))+')'
f=subprocess.run([args.dexsql,'query','--threads','2','--input',args.apk,'--sql',field_sql],capture_output=True,text=True,check=True)
found_fields={line.split('|')[1].strip() for line in f.stdout.splitlines() if line.startswith('| L')}
records.extend(dict(descriptor=s,passed=s in found_fields) for s in sorted(expected_fields))
Path(args.output).write_text(json.dumps(dict(tool='DexSQL 0.1.1',checks=records,timing=r.stderr),ensure_ascii=False,indent=2))
passed=sum(c['passed'] for c in records)
print(f'{passed}/{len(records)} host ABI checks passed')
if passed!=len(records):raise SystemExit(1)
