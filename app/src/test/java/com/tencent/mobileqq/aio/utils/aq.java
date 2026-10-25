package com.tencent.mobileqq.aio.utils;
import com.tencent.qqnt.kernel.nativeinterface.PicElement;
public class aq {
    public static String a(PicElement pic){return pic.resolvedOriginal;}
    public static String c(PicElement pic,int size){return pic.resolvedThumbs.get(size);}
}
