package com.tencent.mobileqq.emoticon;
public class QQSysFaceUtil {
    public static int convertToLocal(int index){return index+10;}
    public static String getFaceString(int localIndex){return localIndex==11?"[微笑]":"[表情]";}
}
