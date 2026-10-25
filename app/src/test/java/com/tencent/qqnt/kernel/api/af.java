package com.tencent.qqnt.kernel.api;
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener;
import com.tencent.qqnt.kernel.nativeinterface.RichMediaElementGetReq;
public interface af {
    void addMsgListener(IKernelMsgListener listener);
    void removeMsgListener(IKernelMsgListener listener);
    void getRichMediaElement(RichMediaElementGetReq request);
}
