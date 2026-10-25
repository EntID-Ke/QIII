package com.tencent.qqnt.kernel.nativeinterface;
public interface IKernelMsgListener {
    void onRichMediaDownloadComplete(FileTransNotifyInfo info);
    void onRecvMsg();
}
