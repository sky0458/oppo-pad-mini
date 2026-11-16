package io.github.externalvnc.app;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Persistent, app-private, size-capped diagnostics. Never log credentials. */
public final class AppLog {
    private static final long MAX_FILE_BYTES = 512 * 1024;
    private final File directory;
    private final SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);

    public AppLog(Context context) {
        directory = new File(context.getFilesDir(), "diagnostics");
        timestamp.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public synchronized void add(String category, String message) {
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w("ExternalVNC", "Could not create diagnostics directory");
            return;
        }
        File current = file(0);
        if (current.length() > MAX_FILE_BYTES) rotate();
        String line = timestamp.format(new Date()) + " [" + category + "] "
                + redact(message) + "\n";
        try (FileOutputStream stream = new FileOutputStream(current, true)) {
            stream.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            Log.w("ExternalVNC", "Unable to write diagnostics", exception);
        }
    }

    /** Copy or preview the last maxChars characters, without loading an unlimited log into UI. */
    public synchronized String recent(int maxChars) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportTo(out);
        String content = out.toString("UTF-8");
        if (content.length() <= maxChars) return content;
        return "…（仅显示最近日志；导出可保存全部）\n" + content.substring(content.length() - maxChars);
    }

    public synchronized void exportTo(OutputStream destination) throws IOException {
        for (int generation = 2; generation >= 0; generation--) {
            File source = file(generation);
            if (!source.isFile()) continue;
            try (FileInputStream input = new FileInputStream(source)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) destination.write(buffer, 0, read);
            }
        }
        destination.flush();
    }

    private File file(int generation) {
        return new File(directory, generation == 0 ? "external-vnc.log" : "external-vnc." + generation + ".log");
    }

    private void rotate() {
        File oldest = file(2);
        if (oldest.exists() && !oldest.delete()) Log.w("ExternalVNC", "Could not remove old log");
        if (file(1).exists() && !file(1).renameTo(oldest)) Log.w("ExternalVNC", "Could not rotate log 1");
        if (file(0).exists() && !file(0).renameTo(file(1))) Log.w("ExternalVNC", "Could not rotate log 0");
    }

    /** Avoid leaking IP addresses into exported logs; passwords are never passed to add(). */
    static String redact(String input) {
        if (input == null) return "";
        return input.replaceAll("(?<![\\d.])((?:\\d{1,3}\\.){3})\\d{1,3}(?![\\d.])", "$1xxx")
                .replace('\n', ' ').replace('\r', ' ');
    }
}
