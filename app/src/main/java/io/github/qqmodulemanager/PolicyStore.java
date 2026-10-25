package io.github.qqmodulemanager;

import android.content.Context;
import android.net.Uri;
import org.json.*;

public final class PolicyStore {
    public static final Uri URI = Uri.parse("content://io.github.qqmodulemanager.bridge");
    private final Context context;
    public PolicyStore(Context context) { this.context = context; }
    public synchronized JSONObject read() {
        try { return new JSONObject(context.getSharedPreferences("policy", 0).getString("document", "{}")); }
        catch (JSONException e) { throw new IllegalStateException("Invalid saved policy", e); }
    }
    public synchronized void save(JSONObject document) {
        try {
            document.put("revision", read().optLong("revision") + 1);
            if (!context.getSharedPreferences("policy", 0).edit().putString("document", document.toString()).commit()) throw new IllegalStateException("Cannot save policy");
            context.getContentResolver().notifyChange(URI, null);
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    public synchronized JSONObject runtime() {
        try { return new JSONObject(context.getSharedPreferences("runtime", 0).getString("document", "{}")); }
        catch (JSONException e) { return new JSONObject(); }
    }
    public synchronized void runtime(JSONObject document) {
        context.getSharedPreferences("runtime", 0).edit().putString("document", document.toString()).apply();
        context.getContentResolver().notifyChange(Uri.withAppendedPath(URI, "runtime"), null);
    }
}
