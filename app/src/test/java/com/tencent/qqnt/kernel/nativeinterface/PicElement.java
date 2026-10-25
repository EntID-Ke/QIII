package com.tencent.qqnt.kernel.nativeinterface;
import java.util.HashMap;
public class PicElement {
    public String sourcePath="",resolvedOriginal="";
    public final HashMap<Integer,String> thumbPath=new HashMap<>(),resolvedThumbs=new HashMap<>();
    public Boolean flash=false;
    public String getSourcePath(){return sourcePath;}
    public HashMap<Integer,String> getThumbPath(){return thumbPath;}
    public Boolean getIsFlashPic(){return flash;}
    public String getMd5HexStr(){return "test-image-md5";}
}
