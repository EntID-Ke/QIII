package io.github.qqmodulemanager;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.os.Process;
import org.json.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;

public final class PolicyProvider extends ContentProvider {
    private final ConcurrentHashMap<Integer, IBinder> hosts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, IBinder> tools = new ConcurrentHashMap<>();
    private PolicyStore store;
    private void registerHost(int pid, IBinder host) {
        if(host==null)return;
        if(hosts.put(pid,host)==host)return;
        try { host.linkToDeath(() -> {
            if(!hosts.remove(pid,host))return;
            tools.remove(pid);
            synchronized(PolicyProvider.this) {
                try { JSONObject r=store.runtime();JSONObject p=r.optJSONObject(String.valueOf(pid));if(p!=null){p.put("connected",false);store.runtime(r);} }catch(JSONException ignored){}
            }
        },0); }catch(RemoteException error){hosts.remove(pid,host);}
    }
    private JSONObject runtime() {
        JSONObject runtime=store.runtime();
        for(java.util.Iterator<String> keys=runtime.keys();keys.hasNext();) {
            String key=keys.next();IBinder host=hosts.get(Integer.parseInt(key));
            try{runtime.getJSONObject(key).put("connected",host!=null && host.isBinderAlive());}catch(JSONException error){throw new IllegalStateException(error);}
        }
        return runtime;
    }
    private String currentPolicy() {
        JSONObject policy=store.read();JSONArray installed=new JSONArray(),compatible=new JSONArray();
        for(String[] module:new String[][]{{"qauxv","io.github.qauxv"},{"qfun","me.yxp.qfun"},{"ono","moe.ono"}}) {
            try {
                android.content.pm.ApplicationInfo info=getContext().getPackageManager().getApplicationInfo(module[1],android.content.pm.PackageManager.GET_META_DATA);
                installed.put(module[0]);
                if(info.metaData!=null && info.metaData.getInt("io.github.qqmodulemanager.bridgeVersion")==io.github.qqmodulemanager.bridge.BridgeClient.VERSION)compatible.put(module[0]);
            }
            catch(android.content.pm.PackageManager.NameNotFoundException ignored) { }
        }
        try { policy.put("installedModules",installed);policy.put("compatibleModules",compatible); }catch(JSONException e){throw new IllegalStateException(e);}
        return policy.toString();
    }
    @Override public boolean onCreate() { store = new PolicyStore(getContext()); return true; }
    private void authorize() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        try { if (uid == getContext().getPackageManager().getApplicationInfo("com.tencent.mobileqq", 0).uid) return; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        throw new SecurityException("QIII: caller rejected");
    }
    @Override public synchronized Bundle call(String method, String argument, Bundle args) {
        authorize(); Bundle out = new Bundle(); int pid = Binder.getCallingPid();
        switch (method) {
            case "diagnosticExport": {
                ParcelFileDescriptor source=args==null?null:args.getParcelable("content");
                return source==null?DiagnosticExports.create(getContext(),args==null?"":args.getString("text","")):DiagnosticExports.fromDescriptor(getContext(),source);
            }
            case "toolConfig":case "toolSaveSettings":case "toolSaveProvider":case "toolClear":case "toolCacheExport":
                return FeatureConfigBridge.call(getContext(),method,args);
            case "tools": {
                JSONObject runtime=runtime();
                for(java.util.Iterator<String> keys=runtime.keys();keys.hasNext();) {
                    String key=keys.next();JSONObject report=runtime.optJSONObject(key);IBinder endpoint=tools.get(Integer.parseInt(key));
                    if(report!=null && report.optBoolean("connected") && report.optString("process").equals("com.tencent.mobileqq") && endpoint!=null && endpoint.isBinderAlive()){out.putBinder("tools",endpoint);break;}
                }
                break;
            }
            case "publish": {
                IBinder host = args == null ? null : args.getBinder("host");
                if (host == null || Binder.getCallingUid() == Process.myUid()) throw new IllegalArgumentException("Host binder required");
                registerHost(pid,host);
                out.putString("policy", currentPolicy()); break;
            }
            case "host": out.putBinder("host", hosts.get(pid)); break;
            case "policy": out.putString("policy", currentPolicy()); break;
            case "restart": {
                JSONObject runtime=runtime();IBinder main=null;
                for(java.util.Iterator<String> keys=runtime.keys();keys.hasNext();) {
                    String key=keys.next();JSONObject report=runtime.optJSONObject(key);
                    if(report!=null && report.optBoolean("connected") && report.optString("process").equals("com.tencent.mobileqq")){main=hosts.get(Integer.parseInt(key));break;}
                }
                if(main==null){
                    if(args!=null && args.getBoolean("refresh"))getContext().getContentResolver().notifyChange(PolicyStore.URI,null);
                    out.putBoolean("running",false);break;
                }
                Parcel request=Parcel.obtain(),reply=Parcel.obtain();
                try {
                    request.writeInterfaceToken(io.github.qqmodulemanager.bridge.BridgeClient.DESCRIPTOR);
                    if(!main.transact(HostRestart.TRANSACTION_RESTART,request,reply,0))throw new IllegalStateException("QQ 中尚未载入新版重启接口，请手动完整重启 QQ 一次");
                    reply.readException();out.putBoolean("running",true);
                }catch(RemoteException error){throw new IllegalStateException("QQ disconnected",error);}
                finally{request.recycle();reply.recycle();}
                break;
            }
            case "snapshot":
                out.putString("policy",store.read().toString());out.putString("runtime",runtime().toString());
                out.putString("catalog",NativeMenuCatalog.withBuiltins(getContext().getSharedPreferences("catalog",0).getString("document","{}")));break;
            case "save": {
                if(args==null)throw new IllegalArgumentException("Policy required");
                try{store.save(new JSONObject(args.getString("policy","{}")));}catch(JSONException error){throw new IllegalArgumentException(error);}
                break;
            }
            case "report": {
                if (Binder.getCallingUid() == Process.myUid() || args == null) throw new SecurityException("Host report required");
                try {
                    JSONObject runtime = store.runtime();
                    JSONObject report = new JSONObject(args.getString("report", "{}"));
                    registerHost(pid,args.getBinder("host"));
                    if(args.getBinder("tools")!=null)tools.put(pid,args.getBinder("tools"));
                    report.put("pid", pid); report.put("seenAt", System.currentTimeMillis());
                    IBinder host=hosts.get(pid);report.put("connected",host!=null && host.isBinderAlive());
                    runtime.put(String.valueOf(pid), report);
                    // A small recent history avoids unbounded stale-process records.
                    while (runtime.length() > 8) {
                        String oldest=null;
                        for(java.util.Iterator<String> keys=runtime.keys();keys.hasNext();) {
                            String key=keys.next();IBinder live=hosts.get(Integer.parseInt(key));
                            if(live==null || !live.isBinderAlive())if(oldest==null || runtime.getJSONObject(key).optLong("seenAt")<runtime.getJSONObject(oldest).optLong("seenAt"))oldest=key;
                        }
                        if(oldest==null)break; // Never evict a running QQ process to make room for dead history.
                        runtime.remove(oldest);
                    }
                    store.runtime(runtime);
                } catch (JSONException e) { throw new IllegalArgumentException(e); }
                break;
            }
            case "catalog": {
                if (args == null) throw new IllegalArgumentException("Catalog required");
                try {
                    JSONObject catalog = new JSONObject(getContext().getSharedPreferences("catalog", 0).getString("document", "{}"));
                    JSONObject snapshot = new JSONObject(args.getString("snapshot", "{}"));
                    String profile = snapshot.getString("profile");
                    if (!profile.matches("(group|private)/(text|image|voice|video|file)")) throw new IllegalArgumentException("Unknown profile");
                    JSONObject previous=catalog.optJSONObject(profile);
                    LinkedHashMap<String,JSONObject> merged=new LinkedHashMap<>();
                    JSONArray old=previous==null?null:previous.optJSONArray("items");
                    if(old!=null)for(int i=0;i<old.length();i++){JSONObject item=old.getJSONObject(i);merged.put(item.getString("key"),item);}
                    JSONArray fresh=snapshot.getJSONArray("items");
                    for(int i=0;i<fresh.length();i++){JSONObject item=fresh.getJSONObject(i);merged.put(item.getString("key"),item);}
                    snapshot.put("items",new JSONArray(merged.values()));
                    catalog.put(profile, snapshot);
                    getContext().getSharedPreferences("catalog", 0).edit().putString("document", catalog.toString()).apply();
                    getContext().getContentResolver().notifyChange(Uri.withAppendedPath(PolicyStore.URI, "catalog"), null);
                } catch (JSONException e) { throw new IllegalArgumentException(e); }
                break;
            }
            default: throw new IllegalArgumentException("Unsupported bridge operation");
        }
        return out;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        if(u.getPath()!=null && u.getPath().startsWith("/diagnostics/")) {
            DiagnosticExports.authorizeRead(getContext(),u);java.io.File file=DiagnosticExports.INSTANCE.file(getContext(),u);
            String[] columns=p==null?new String[]{android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE}:p;
            android.database.MatrixCursor cursor=new android.database.MatrixCursor(columns);Object[] row=new Object[columns.length];
            for(int i=0;i<columns.length;i++)row[i]=columns[i].equals(android.provider.OpenableColumns.DISPLAY_NAME)?"QIII-log.txt":columns[i].equals(android.provider.OpenableColumns.SIZE)?file.length():null;
            cursor.addRow(row);return cursor;
        }
        throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri u) { return u.getPath()!=null && u.getPath().startsWith("/diagnostics/")?"text/plain":null; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException{return DiagnosticExports.open(getContext(),uri,mode);}
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
