package io.github.qqmodulemanager;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

/** Run as QQ's UID; its restart Activity is deliberately not exported to other applications. */
public final class HostRestart {
    public static final int TRANSACTION_RESTART=android.os.IBinder.FIRST_CALL_TRANSACTION;
    public static final String ACTIVITY="com.tencent.mobileqq.login.restart.MainProcessRestartLoadingActivity";
    public static void restart(Context context) {
        if(!"com.tencent.mobileqq".equals(context.getPackageName()))throw new SecurityException("QQ context required");
        // Resolve the native flow before touching any process.
        Intent intent=new Intent().setClassName("com.tencent.mobileqq",ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("tipText","正在应用模块配置并重启 QQ…");
        if(context.getPackageManager().resolveActivity(intent,0)==null)throw new IllegalStateException("QQ restart Activity unavailable");
        ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        if(am!=null && am.getRunningAppProcesses()!=null)for(ActivityManager.RunningAppProcessInfo p:am.getRunningAppProcesses())
            if(p.uid==Process.myUid() && p.pid!=Process.myPid() && p.processName.startsWith("com.tencent.mobileqq:") && !p.processName.equals("com.tencent.mobileqq:account"))Process.killProcess(p.pid);
        context.startActivity(intent);
    }
}
