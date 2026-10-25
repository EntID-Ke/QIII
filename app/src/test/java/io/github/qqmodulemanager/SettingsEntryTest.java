package io.github.qqmodulemanager;

import android.content.*;
import kotlin.jvm.internal.DefaultConstructorMarker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class SettingsEntryTest {
    public static class Group {
        public final List<?> items;
        public Group(List<?> items,CharSequence title,CharSequence footer,int mask,DefaultConstructorMarker marker){this.items=items;}
    }
    @Test public void entryPreservesRowsAndOpensInsideTheProvidedContext()throws Exception{
        Context context=RuntimeEnvironment.getApplication();java.util.concurrent.atomic.AtomicReference<Context> opened=new java.util.concurrent.atomic.AtomicReference<>();
        SettingsEntry entry=new SettingsEntry(getClass().getClassLoader(),opened::set);
        Group first=new Group(List.of("QQ"),"","",0,null),last=new Group(List.of("QFun"),"","",0,null);
        List<Object> result=entry.insert(context,List.of(first,last));
        assertEquals(3,result.size());assertSame(first,result.get(0));assertSame(last,result.get(2));
        com.tencent.mobileqq.setting.processor.i item=(com.tencent.mobileqq.setting.processor.i)((Group)result.get(1)).items.get(0);
        assertEquals("QIII",item.title);item.listener.invoke();
        assertSame(context,opened.get());
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }
    @Test public void rebuildingAnAlreadyInjectedListDoesNotDuplicateTheEntry()throws Exception{
        SettingsEntry entry=new SettingsEntry(getClass().getClassLoader(),c->{});Context context=RuntimeEnvironment.getApplication();
        List<Object> result=entry.insert(context,List.of(new Group(List.of(),"","",0,null)));
        assertEquals(result,entry.insert(context,Collections.unmodifiableList(result)));
        assertEquals(2,result.size());
    }
}
