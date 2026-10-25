package com.tencent.qqnt.aio.menu.ui;
import com.tencent.mobileqq.aio.msg.AIOMsgItem;
public class e {
    private final int id;private final String title;private final AIOMsgItem message;
    public int clicks;
    public e(int id,String title,AIOMsgItem message){this.id=id;this.title=title;this.message=message;}
    public int c(){return id;}
    public String h(){return title;}
    public AIOMsgItem d(){return message;}
    public void k(){clicks++;}
}
