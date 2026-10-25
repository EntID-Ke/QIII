package com.tencent.qqnt.kernel.nativeinterface;
public class MsgElement {
    private final int type;
    public MsgElement(int type){this.type=type;}
    public int getElementType(){return type;}
    public long getElementId(){return 456L;}
}
