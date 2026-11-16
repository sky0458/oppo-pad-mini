package io.github.externalvnc.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;

/** Small versioned store for non-secret connection metadata and the default selection. */
final class ProfileRepository {
    private static final String PREF_NAME = "vnc_profiles_v1";
    private final SharedPreferences prefs;
    private final List<ConnectionProfile> profiles = new ArrayList<>();
    private String defaultId;

    ProfileRepository(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String serialized = prefs.getString("profiles", null);
        if (serialized != null) {
            try {
                JSONArray all = new JSONArray(serialized);
                for (int i = 0; i < Math.min(256, all.length()); i++) {
                    try { profiles.add(ConnectionProfile.fromJson(all.getJSONObject(i))); }
                    catch (JSONException ignored) { /* Preserve readable entries. */ }
                }
            } catch (JSONException ignored) { /* Invalid data: create a starter profile. */ }
        }
        defaultId = prefs.getString("defaultId", "");
        if (serialized == null && profiles.isEmpty()) {
            // Old versions stored only host/port, never the password. Preserve them.
            SharedPreferences previous = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
            ConnectionProfile initial = new ConnectionProfile();
            initial.name = "MacBook Pro · 测试";
            initial.host = previous.getString("host", "");
            try { initial.port = Integer.parseInt(previous.getString("port", "5900")); }
            catch (NumberFormatException ignored) { initial.port = 5900; }
            if (initial.port < 1 || initial.port > 65535) initial.port = 5900;
            initial.username = "caiwenhai"; // User-requested default username; no password embedded.
            initial.authMode = io.github.externalvnc.rfb.RfbClient.AuthMode.MAC_ACCOUNT;
            profiles.add(initial);
            defaultId = initial.id;
            persist();
        }
        if (!profiles.isEmpty() && find(defaultId) == null) {
            defaultId = profiles.get(0).id;
            persist();
        }
    }

    synchronized List<ConnectionProfile> list() { return new ArrayList<>(profiles); }

    synchronized ConnectionProfile find(String id) {
        for (ConnectionProfile item : profiles) if (item.id.equals(id)) return item;
        return null;
    }

    synchronized String defaultId() { return defaultId; }

    synchronized void upsert(ConnectionProfile value) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(value.id)) {
                profiles.set(i, value);
                persist();
                return;
            }
        }
        if (profiles.size() >= 256) throw new IllegalStateException("Too many profiles");
        profiles.add(value);
        if (defaultId == null || defaultId.isEmpty()) defaultId = value.id;
        persist();
    }

    synchronized void makeDefault(String id) {
        if (find(id) == null) throw new IllegalArgumentException("Unknown profile");
        defaultId = id;
        persist();
    }

    synchronized void remove(String id) {
        profiles.removeIf(item -> item.id.equals(id));
        if (id.equals(defaultId)) defaultId = profiles.isEmpty() ? "" : profiles.get(0).id;
        persist();
    }

    private void persist() {
        JSONArray all = new JSONArray();
        try {
            for (ConnectionProfile profile : profiles) all.put(profile.toJson());
        } catch (JSONException error) {
            throw new IllegalStateException("Invalid profile data", error);
        }
        if (!prefs.edit().putString("profiles", all.toString())
                .putString("defaultId", defaultId).commit())
            throw new IllegalStateException("Failed to save profiles");
    }
}
