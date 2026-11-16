package io.github.externalvnc.rfb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal pure-Java open-source RFB/VNC client (RFC 6143).
 * Security: VncAuth/None/Apple DH (type 30). Encodings: Raw, CopyRect, DesktopSize.
 * No TLS or session-wide encryption; use a trusted encrypted tunnel.
 */
public final class RfbClient implements AutoCloseable {
    public enum AuthMode { AUTO, MAC_ACCOUNT, MAC_CBC_COMPAT, VNC_PASSWORD }

    public interface Listener {
        void onConnected(String desktopName, int width, int height);
        /** Frame pixels are reused by the reader thread; copy before asynchronous processing. */
        void onFrame(int width, int height, int[] pixels);
        void onError(String error);
        void onDisconnected();
        void onDiagnostic(String message);
    }

    private static final int MAX_PIXELS = 16_777_216;
    private final String host, username, password;
    private final AuthMode authMode;
    private final int port;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private volatile Socket socket;
    private volatile DataOutputStream output;
    private volatile int width, height, cursorX, cursorY;
    private volatile int buttonMask;
    private int[] pixels;

    public RfbClient(String host, int port, String username, String password,
                     AuthMode authMode, Listener listener) {
        this.host = host;
        this.port = port;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.authMode = authMode;
        this.listener = listener;
    }

    public void runBlocking() {
        if (!active.compareAndSet(false, true)) return;
        try {
            Socket connection = new Socket();
            socket = connection;
            connection.connect(new InetSocketAddress(host, port), 8000);
            connection.setTcpNoDelay(true);
            connection.setSoTimeout(15000); // Avoid hanging forever during authentication.
            DataInputStream in = new DataInputStream(new BufferedInputStream(connection.getInputStream(), 65536));
            output = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream(), 65536));
            negotiate(in);
            connection.setSoTimeout(0);
            while (active.get()) {
                int kind = in.readUnsignedByte();
                switch (kind) {
                    case 0:
                        readFramebufferUpdate(in);
                        requestUpdate(true);
                        break;
                    case 1: // SetColorMapEntries
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        skip(in, in.readUnsignedShort() * 6);
                        break;
                    case 2: // Bell
                        break;
                    case 3: // ServerCutText
                        skip(in, 3);
                        int len = in.readInt();
                        if (len < 0 || len > 1048576) throw new IOException("Invalid clipboard length");
                        skip(in, len);
                        break;
                    default: throw new IOException("Unsupported RFB message " + kind);
                }
            }
        } catch (Exception ex) {
            if (active.get()) {
                listener.onDiagnostic("ERROR " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                listener.onError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        } finally {
            close();
            listener.onDisconnected();
        }
    }

    private void negotiate(DataInputStream in) throws Exception {
        byte[] greeting = new byte[12];
        in.readFully(greeting);
        String protocol = new String(greeting, StandardCharsets.US_ASCII);
        if (!protocol.startsWith("RFB 003.") || greeting[11] != '\n')
            throw new IOException("Unexpected RFB greeting: " + protocol);
        int remoteMinor = Integer.parseInt(protocol.substring(8, 11));
        int minor = remoteMinor >= 8 ? 8 : remoteMinor >= 7 ? 7 : 3;
        listener.onDiagnostic("RFB greeting=" + protocol.trim() + " negotiated=3." + minor);
        write(String.format(java.util.Locale.ROOT, "RFB 003.%03d\n", minor)
                .getBytes(StandardCharsets.US_ASCII));

        int security;
        if (minor == 3) {
            security = in.readInt();
            listener.onDiagnostic("RFB 3.3 server security=" + security);
            if (security == 0) throw new IOException(readReason(in));
        } else {
            int count = in.readUnsignedByte();
            if (count == 0) throw new IOException(readReason(in));
            if (count > 64) throw new IOException("Invalid security type count=" + count);
            int[] offeredTypes = new int[count];
            boolean allowNone = false, allowVnc = false, allowMac = false;
            for (int i = 0; i < count; i++) {
                int offered = in.readUnsignedByte();
                offeredTypes[i] = offered;
                if (offered == 1) allowNone = true;
                if (offered == 2) allowVnc = true;
                if (offered == 30) allowMac = true;
            }
            listener.onDiagnostic("Server security types=" + Arrays.toString(offeredTypes));
            if (authMode == AuthMode.VNC_PASSWORD) {
                if (!allowVnc) throw new IOException("Mac 未提供 VNC 专用密码认证 (2)，请改选 Mac 账号密码 (30)");
                security = 2;
            } else if (authMode == AuthMode.MAC_ACCOUNT || authMode == AuthMode.MAC_CBC_COMPAT) {
                if (!allowMac) throw new IOException("服务器未提供 Mac 账号认证 (30)；可尝试 VNC 专用密码认证");
                security = 30;
            } else if (!username.isEmpty() && allowMac) security = 30;
            else if (!password.isEmpty() && allowVnc) security = 2;
            else if (username.isEmpty() && password.isEmpty() && allowNone) security = 1;
            else if (allowMac && username.isEmpty())
                throw new IOException("Mac 提供账号认证 (30)：请填写 Mac 本地用户名及登录密码");
            else throw new IOException("无兼容认证方式；服务端提供 " + Arrays.toString(offeredTypes));
            listener.onDiagnostic("Selected security type=" + security
                    + (security == 30 ? (authMode == AuthMode.MAC_CBC_COMPAT ? " (AES-CBC)" : " (AES-ECB)") : ""));
            synchronized (writeLock) { output.writeByte(security); output.flush(); }
        }
        if (security == 2) {
            if (password.isEmpty()) throw new IOException("VNC password is required");
            byte[] challenge = new byte[16];
            in.readFully(challenge);
            write(encryptChallenge(challenge, password));
        } else if (security == 30) {
            AppleDhAuth.authenticate(in, output, username, password,
                    authMode == AuthMode.MAC_CBC_COMPAT);
        } else if (security != 1) {
            throw new IOException("Unsupported RFB SecurityType " + security);
        }
        // SecurityResult is mandatory for authenticated types and RFB 3.8.
        if (security == 2 || security == 30 || minor == 8) {
            int result = in.readInt();
            listener.onDiagnostic("SecurityResult=" + result);
            if (result != 0) {
                throw new IOException(minor >= 8 ? readReason(in) : "VNC authentication rejected (" + result + ")");
            }
        }

        synchronized (writeLock) { output.writeByte(1); output.flush(); } // share desktop
        int fbW = in.readUnsignedShort(), fbH = in.readUnsignedShort();
        byte[] serverPixelFormat = new byte[16];
        in.readFully(serverPixelFormat);
        int nameLen = in.readInt();
        if (nameLen < 0 || nameLen > 1048576) throw new IOException("Invalid desktop name length");
        byte[] name = new byte[nameLen];
        in.readFully(name);
        resize(fbW, fbH);
        setPixelFormat();
        setEncodings();
        ready.set(true);
        listener.onDiagnostic("ServerInit desktop=" + width + "x" + height + " (connected)");
        listener.onConnected(new String(name, StandardCharsets.UTF_8), width, height);
        requestUpdate(false);
    }

    private void setPixelFormat() throws IOException {
        synchronized (writeLock) {
            output.writeByte(0); output.write(new byte[3]);
            output.writeByte(32); output.writeByte(24); // bpp, depth
            output.writeByte(0); output.writeByte(1);   // LE, true colour
            output.writeShort(255); output.writeShort(255); output.writeShort(255);
            output.writeByte(16); output.writeByte(8); output.writeByte(0); // R G B shift
            output.write(new byte[3]);
            output.flush();
        }
    }

    private void setEncodings() throws IOException {
        synchronized (writeLock) {
            output.writeByte(2); output.writeByte(0); output.writeShort(3);
            output.writeInt(1); output.writeInt(0); output.writeInt(-223);
            output.flush();
        }
    }

    private void requestUpdate(boolean incremental) throws IOException {
        synchronized (writeLock) {
            output.writeByte(3); output.writeByte(incremental ? 1 : 0);
            output.writeShort(0); output.writeShort(0);
            output.writeShort(width); output.writeShort(height);
            output.flush();
        }
    }

    private void resize(int w, int h) throws IOException {
        if (w < 1 || h < 1 || (long) w * h > MAX_PIXELS)
            throw new IOException("Invalid framebuffer " + w + "x" + h);
        width = w; height = h;
        pixels = new int[w * h];
        cursorX = w / 2; cursorY = h / 2;
    }

    private void readFramebufferUpdate(DataInputStream in) throws IOException {
        in.readUnsignedByte(); // pad
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int x = in.readUnsignedShort(), y = in.readUnsignedShort();
            int w = in.readUnsignedShort(), h = in.readUnsignedShort();
            int encoding = in.readInt();
            if (encoding == -223) { // DesktopSize: x/y must be zero
                if (x != 0 || y != 0) throw new IOException("Invalid DesktopSize");
                resize(w, h); continue;
            }
            if (w == 0 || h == 0 || x + w > width || y + h > height)
                throw new IOException("Rectangle outside framebuffer");
            if (encoding == 0) {
                byte[] line = new byte[w * 4];
                for (int row = 0; row < h; row++) {
                    in.readFully(line);
                    int at = (y + row) * width + x;
                    for (int col = 0, p = 0; col < w; col++, p += 4) {
                        int blue = line[p] & 255, green = line[p + 1] & 255, red = line[p + 2] & 255;
                        pixels[at + col] = 0xff000000 | (red << 16) | (green << 8) | blue;
                    }
                }
            } else if (encoding == 1) {
                int sourceX = in.readUnsignedShort(), sourceY = in.readUnsignedShort();
                if (sourceX + w > width || sourceY + h > height)
                    throw new IOException("CopyRect source outside framebuffer");
                int[] tmp = new int[w * h];
                for (int row = 0; row < h; row++)
                    System.arraycopy(pixels, (sourceY + row) * width + sourceX, tmp, row * w, w);
                for (int row = 0; row < h; row++)
                    System.arraycopy(tmp, row * w, pixels, (y + row) * width + x, w);
            } else throw new IOException("Unsupported framebuffer encoding " + encoding);
        }
        if (count > 0) listener.onFrame(width, height, pixels);
    }

    private static String readReason(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 65536) throw new IOException("Invalid RFB failure reason length");
        byte[] reason = new byte[n];
        in.readFully(reason);
        return new String(reason, StandardCharsets.UTF_8);
    }

    private static void skip(DataInputStream in, int count) throws IOException {
        byte[] buffer = new byte[8192];
        while (count > 0) {
            int chunk = Math.min(count, buffer.length);
            in.readFully(buffer, 0, chunk);
            count -= chunk;
        }
    }

    /** Legacy VNC DES challenge-response, RFB SecurityType 2; password uses first 8 bytes. */
    public static byte[] encryptChallenge(byte[] challenge, String password) throws Exception {
        if (challenge.length != 16) throw new IllegalArgumentException("Challenge must be 16 bytes");
        byte[] bytes = password.getBytes(StandardCharsets.ISO_8859_1);
        byte[] key = new byte[8];
        for (int i = 0; i < Math.min(8, bytes.length); i++)
            key[i] = (byte) (Integer.reverse(bytes[i] & 0xff) >>> 24);
        Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
        return cipher.doFinal(challenge);
    }

    private void write(byte[] data) throws IOException {
        synchronized (writeLock) { output.write(data); output.flush(); }
    }

    public void sendPointer(int x, int y, int buttons) {
        DataOutputStream stream = output;
        if (!active.get() || !ready.get() || stream == null || width == 0 || height == 0) return;
        cursorX = Math.max(0, Math.min(width - 1, x));
        cursorY = Math.max(0, Math.min(height - 1, y));
        try {
            synchronized (writeLock) {
                stream.writeByte(5);
                stream.writeByte(buttons);
                stream.writeShort(cursorX);
                stream.writeShort(cursorY);
                stream.flush();
            }
        } catch (IOException e) { close(); }
    }

    public void setButtons(int buttons) {
        buttonMask = buttons;
        sendPointer(cursorX, cursorY, buttons);
    }

    public void sendRelativeMove(float dx, float dy) {
        sendPointer(cursorX + Math.round(dx), cursorY + Math.round(dy), buttonMask);
    }

    public void click(int button) {
        sendPointer(cursorX, cursorY, buttonMask | button);
        sendPointer(cursorX, cursorY, buttonMask);
    }

    public void scroll(boolean up) { click(up ? 8 : 16); }

    public void sendKey(int keysym, boolean pressed) {
        DataOutputStream stream = output;
        if (!active.get() || !ready.get() || stream == null || keysym == 0) return;
        try {
            synchronized (writeLock) {
                stream.writeByte(4);
                stream.writeByte(pressed ? 1 : 0);
                stream.writeShort(0);
                stream.writeInt(keysym);
                stream.flush();
            }
        } catch (IOException e) { close(); }
    }

    @Override public void close() {
        active.set(false);
        ready.set(false);
        Socket s = socket; socket = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
}
