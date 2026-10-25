package com.tencent.qqnt.kernel.nativeinterface;
import java.util.List;
public class MsgRecord {
    private final int chatType;private final List<MsgElement> elements;
    public MsgRecord(int chatType,List<MsgElement> elements){this.chatType=chatType;this.elements=elements;}
    public int getChatType(){return chatType;}
    public List<MsgElement> getElements(){return elements;}
    public long getMsgId(){return 123L;}
    public String getPeerUid(){return "peer";}
    public long getPeerUin(){return 0L;}
    public String getText(){throw new AssertionError("Message contents must not be inspected");}
}
