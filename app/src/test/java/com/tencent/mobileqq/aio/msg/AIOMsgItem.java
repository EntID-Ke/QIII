package com.tencent.mobileqq.aio.msg;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
public class AIOMsgItem {
    private final MsgRecord record;
    public AIOMsgItem(MsgRecord record){this.record=record;}
    public MsgRecord getMsgRecord(){return record;}
    public boolean isSelf(){return false;}
}
