package io.github.qqmodulemanager;

import android.content.Context;
import android.content.pm.*;
import android.graphics.*;
import android.view.*;
import android.widget.LinearLayout;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MessagePreviewTest {
    @Test public void everyMessageTypeAndDirectionOpensOnlyTheEditor()throws Exception{
        Context context=RuntimeEnvironment.getApplication();String hostApk=System.getenv("QIII_PREVIEW_APK");
        if(hostApk!=null) {
            PackageInfo p=new PackageInfo();p.packageName="com.tencent.mobileqq";p.applicationInfo=new ApplicationInfo();
            p.applicationInfo.packageName=p.packageName;p.applicationInfo.sourceDir=hostApk;p.applicationInfo.publicSourceDir=hostApk;
            p.applicationInfo.uid=android.os.Process.myUid()+50;Shadows.shadowOf(context.getPackageManager()).installPackage(p);
        }
        Constructor<?> ctor=Class.forName("io.github.qqmodulemanager.PreviewMessages").getDeclaredConstructor(Context.class,String.class,boolean.class,Function0.class);ctor.setAccessible(true);
        for(boolean group:new boolean[]{false,true})for(String kind:new String[]{"text","image","voice","video","file"}) {
            AtomicInteger edits=new AtomicInteger();Function0<Unit> click=()->{edits.incrementAndGet();return Unit.INSTANCE;};
            LinearLayout root=(LinearLayout)ctor.newInstance(context,kind,group,click);
            for(int direction=0;direction<2;direction++) {
                ViewGroup row=(ViewGroup)root.getChildAt(direction+1);
                ViewGroup message=(ViewGroup)row.getChildAt(direction==0?1:0);
                View bubble=message.getChildAt(message.getChildCount()-1);
                assertTrue(bubble.performLongClick());assertTrue(bubble.getContentDescription().toString().contains(kind));
            }
            assertEquals(2,edits.get());assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
            String output=System.getenv("QIII_PREVIEW_DIR");
            if(output!=null) {
                root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                root.layout(0,0,360,root.getMeasuredHeight());Bitmap bitmap=Bitmap.createBitmap(360,root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
                File dir=new File(output);assertTrue(dir.isDirectory()||dir.mkdirs());
                try(FileOutputStream stream=new FileOutputStream(new File(dir,(group?"group-":"private-")+kind+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}
                bitmap.recycle();
            }
        }
        String output=System.getenv("QIII_PREVIEW_DIR");
        if(output!=null) {
            android.graphics.drawable.Drawable icon=context.getDrawable(R.mipmap.ic_launcher);
            assertTrue(icon instanceof android.graphics.drawable.AdaptiveIconDrawable);
            Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,256,256);icon.draw(new Canvas(bitmap));
            try(FileOutputStream stream=new FileOutputStream(new File(output,"app-icon.png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}
            bitmap.recycle();
        }
    }
}
