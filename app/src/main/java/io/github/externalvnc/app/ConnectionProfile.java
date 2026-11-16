package io.github.externalvnc.app;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;
import io.github.externalvnc.rfb.RfbClient;

/** Metadata only. Passwords are deliberately stored outside this model. */
final class ConnectionProfile {
    String id;
    String name;
    String host;
    int port;
    String username;
    RfbClient.AuthMode authMode;
    long createdAt;
    long lastConnectedAt;

    ConnectionProfile() {
        id = UUID.randomUUID().toString();
        name = "新连接";
        host = "";
        port = 5900;
        username = "";
        authMode = RfbClient.AuthMode.AUTO;
        createdAt = System.currentTimeMillis();
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id).put("name", name).put("host", host)
                .put("port", port).put("username", username)
                .put("authMode", authMode.name()).put("createdAt", createdAt)
                .put("lastConnectedAt", lastConnectedAt);
    }

    static ConnectionProfile fromJson(JSONObject value) throws JSONException {
        ConnectionProfile profile = new ConnectionProfile();
        profile.id = value.getString("id");
        if (profile.id.length() < 4 || profile.id.length() > 128)
            throw new JSONException("Invalid profile id");
        profile.name = value.optString("name", "新连接");
        profile.host = value.optString("host", "");
        profile.port = value.optInt("port", 5900);
        profile.username = value.optString("username", "");
        if (profile.port < 1 || profile.port > 65535 || profile.name.length() > 100
                || profile.host.length() > 253 || profile.username.length() > 256)
            throw new JSONException("Invalid profile metadata");
        try {
            profile.authMode = RfbClient.AuthMode.valueOf(value.optString("authMode", "AUTO"));
        } catch (IllegalArgumentException ignored) { profile.authMode = RfbClient.AuthMode.AUTO; }
        profile.createdAt = value.optLong("createdAt", profile.createdAt);
        profile.lastConnectedAt = value.optLong("lastConnectedAt", 0);
        return profile;
    }

    String displayLabel() {
        return name + (host.isEmpty() ? "" : " · " + host);
    }
}
