package io.github.qqmodulemanager;

import android.app.*;
import android.content.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class HostRestartTest {
    @Test public void nativeRestartUsesQqIdentityAndOnlyStopsItsOwnSubprocesses() {
        Application app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app.getPackageManager()).addActivityIfNotPresent(new ComponentName("com.tencent.mobileqq",HostRestart.ACTIVITY));
        Context qq=new ContextWrapper(app){@Override public String getPackageName(){return "com.tencent.mobileqq";}};
        int uid=android.os.Process.myUid(),pid=android.os.Process.myPid();
        ActivityManager.RunningAppProcessInfo main=process("com.tencent.mobileqq",pid,uid),msf=process("com.tencent.mobileqq:MSF",pid+1,uid),account=process("com.tencent.mobileqq:account",pid+2,uid),foreign=process("other.app",pid+3,uid+1);
        Shadows.shadowOf((ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE)).setProcesses(List.of(main,msf,account,foreign));
        HostRestart.restart(qq);
        Intent intent=Shadows.shadowOf(app).getNextStartedActivity();
        assertEquals(new ComponentName("com.tencent.mobileqq",HostRestart.ACTIVITY),intent.getComponent());
        assertTrue(ShadowProcess.wasKilled(msf.pid));assertFalse(ShadowProcess.wasKilled(main.pid));assertFalse(ShadowProcess.wasKilled(account.pid));assertFalse(ShadowProcess.wasKilled(foreign.pid));
    }
    @Test public void missingNativeActivityDoesNotKillAnyProcess() {
        Application app=RuntimeEnvironment.getApplication();Context qq=new ContextWrapper(app){@Override public String getPackageName(){return "com.tencent.mobileqq";}};
        ActivityManager.RunningAppProcessInfo msf=process("com.tencent.mobileqq:MSF",android.os.Process.myPid()+1,android.os.Process.myUid());
        Shadows.shadowOf((ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE)).setProcesses(List.of(msf));
        try{HostRestart.restart(qq);fail("No native restart flow");}catch(IllegalStateException expected){}
        assertFalse(ShadowProcess.wasKilled(msf.pid));
    }
    @Test(expected=SecurityException.class) public void cannotRunNativeRestartWithTheManagerIdentity(){HostRestart.restart(RuntimeEnvironment.getApplication());}
    private ActivityManager.RunningAppProcessInfo process(String name,int pid,int uid){ActivityManager.RunningAppProcessInfo p=new ActivityManager.RunningAppProcessInfo(name,pid,new String[]{"com.tencent.mobileqq"});p.uid=uid;return p;}
}
