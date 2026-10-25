package com.tencent.qqnt.kernel.nativeinterface;
public class FileTransNotifyInfo {
    public long msgId,msgElementId,fileErrCode;
    public int trasferStatus=4;
    public String filePath="";
    public long getMsgId(){return msgId;}
    public long getMsgElementId(){return msgElementId;}
    public long getFileErrCode(){return fileErrCode;}
    public int getTrasferStatus(){return trasferStatus;}
    public String getFilePath(){return filePath;}
}
