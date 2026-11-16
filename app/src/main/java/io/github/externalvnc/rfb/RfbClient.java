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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal pure-Java open-source RFB/VNC client (RFC 6143).
 * Security: VncAuth/None/Apple DH (type 30). Encodings: zlib, Raw, CopyRect, DesktopSize,
 * ExtendedDesktopSize (when the server offers it).
 * No TLS or session-wide encryption; use a trusted encrypted tunnel.
 */
public final class RfbClient implements AutoCloseable {
    public enum AuthMode { AUTO, MAC_ACCOUNT, MAC_CBC_COMPAT, VNC_PASSWORD }

    public interface Listener {
        void onConnected(String desktopName, int width, int height);
        /** Frame pixels are reused by the reader thread; copy before asynchronous processing. */
        void onFrame(int width, int height, int[] pixels);
        /** Opt in to immutable dirty rectangles instead of copying every full desktop frame. */
        default boolean usesFrameRegions() { return false; }
        /** currentPixels is reader-owned, usable synchronously for overflow recovery only. */
        default void onFrameRegions(int width, int height, List<FrameRegion> regions,
                                    int[] currentPixels) { }
        /** Called locally before sending the mouse packet, so the cursor remains responsive. */
        default void onPointerMoved(int x, int y, int width, int height) { }
        default void onResizeCapabilityChanged(boolean supported) { }
        void onError(String error);
        void onDisconnected();
        void onDiagnostic(String message);
    }

    public static final class FrameRegion {
        public final int x, y, width, height;
        public final int[] argb;

        public FrameRegion(int x, int y, int width, int height, int[] argb) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.argb = argb;
        }
    }

    private static final int MAX_PIXELS = 16_777_216;
    private final String host, username, password;
    private final AuthMode authMode;
    private final int port;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean motionQueued = new AtomicBoolean();
    /** Every interactive write runs here, never on Android's main thread. */
    private final ExecutorService outbound = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "rfb-outbound");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Socket socket;
    private volatile DataOutputStream output;
    private volatile int width, height, cursorX, cursorY;
    private volatile int targetFps = 30;
    private volatile boolean extendedDesktopSize;
    private volatile int primaryScreenId;
    private volatile int buttonMask;
    private int[] pixels;
    private final Inflater zlibInflater = new Inflater();
    private int framebufferUpdates;
    private long lastUpdateRequestNanos;

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
        if (closed.get()) return;
        if (!active.compareAndSet(false, true)) return;
        if (closed.get()) { active.set(false); return; }
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
                        // DesktopSize invalidates the local pixel buffer. Ask for a full
                        // repaint rather than waiting for incremental damage that may never arrive.
                        boolean needsFullRefresh = readFramebufferUpdate(in);
                        paceFrameRequests();
                        requestUpdate(!needsFullRefresh);
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
            zlibInflater.end();
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
        if (security == 30) {
            // macOS defaults third-party viewers to observe-only. SetMode requests
            // input control; without this message mouse/keyboard events are ignored.
            synchronized (writeLock) {
                output.write(new byte[]{10, 0, 0, 1});
                output.flush();
            }
            listener.onDiagnostic("Apple SetMode control request sent (0a 00 00 01)");
        }
        ready.set(true);
        listener.onDiagnostic("ServerInit desktop=" + width + "x" + height + " (connected)");
        listener.onConnected(new String(name, StandardCharsets.UTF_8), width, height);
        listener.onPointerMoved(cursorX, cursorY, width, height);
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
        listener.onDiagnostic("Requested pixel format: 32bpp depth24 littleEndian RGB888 shifts=16,8,0");
    }

    private void setEncodings() throws IOException {
        synchronized (writeLock) {
            // macOS supports zlib (6), which drastically reduces 32bpp bandwidth.
            output.writeByte(2); output.writeByte(0); output.writeShort(5);
            output.writeInt(6); output.writeInt(1); output.writeInt(0); output.writeInt(-223);
            output.writeInt(-308); // ExtendedDesktopSize: server must advertise before client resizes.
            output.flush();
        }
    }

    private void requestUpdate(boolean incremental) throws IOException {
        synchronized (writeLock) {
            output.writeByte(3); output.writeByte(incremental ? 1 : 0);
            output.writeShort(0); output.writeShort(0);
            output.writeShort(width); output.writeShort(height);
            output.flush();
            lastUpdateRequestNanos = System.nanoTime();
        }
    }

    private void paceFrameRequests() {
        long interval = 1_000_000_000L / targetFps;
        long remaining = interval - (System.nanoTime() - lastUpdateRequestNanos);
        if (remaining > 0 && remaining <= interval) LockSupport.parkNanos(remaining);
    }

    public void setTargetFps(int fps) {
        if (fps != 30 && fps != 60 && fps != 90)
            throw new IllegalArgumentException("FPS must be 30, 60 or 90");
        targetFps = fps;
        listener.onDiagnostic("Target frame request rate=" + fps + " FPS (server/network may be slower)");
    }

    public int getTargetFps() { return targetFps; }

    public boolean supportsRemoteResize() { return extendedDesktopSize; }

    /** This changes the Mac desktop only if its VNC server advertised RFB ExtendedDesktopSize. */
    public boolean requestDesktopResize(int requestedWidth, int requestedHeight) {
        if (requestedWidth < 1 || requestedHeight < 1
                || (long) requestedWidth * requestedHeight > MAX_PIXELS
                || requestedWidth > 65535 || requestedHeight > 65535
                || !extendedDesktopSize) return false;
        return enqueueOutbound(() -> {
            synchronized (writeLock) {
                output.writeByte(251); // SetDesktopSize
                output.writeByte(0);
                output.writeShort(requestedWidth);
                output.writeShort(requestedHeight);
                output.writeByte(1); // one screen
                output.write(new byte[1 + 2]);
                output.writeInt(primaryScreenId); // server-advertised screen id
                output.writeShort(0); output.writeShort(0);
                output.writeShort(requestedWidth); output.writeShort(requestedHeight);
                output.writeInt(0); // flags
                output.flush();
            }
            listener.onDiagnostic("SetDesktopSize request " + requestedWidth + "x" + requestedHeight);
        });
    }

    /** Manually request a full screen update if the server sent a blank/incomplete frame. */
    public boolean requestFullRefresh() {
        return enqueueOutbound(() -> {
            requestUpdate(false);
            listener.onDiagnostic("Manual non-incremental framebuffer refresh requested");
        });
    }

    private void resize(int w, int h) throws IOException {
        if (w < 1 || h < 1 || (long) w * h > MAX_PIXELS)
            throw new IOException("Invalid framebuffer " + w + "x" + h);
        width = w; height = h;
        pixels = new int[w * h];
        cursorX = w / 2; cursorY = h / 2;
        listener.onPointerMoved(cursorX, cursorY, w, h);
    }

    private FrameRegion snapshotRegion(int x, int y, int w, int h) {
        int[] copy = new int[w * h];
        for (int row = 0; row < h; row++)
            System.arraycopy(pixels, (y + row) * width + x, copy, row * w, w);
        return new FrameRegion(x, y, w, h, copy);
    }

    private void readZlibRegion(DataInputStream in, int x, int y, int w, int h) throws IOException {
        int compressedLength = in.readInt();
        long maximum = (long) w * h * 4 + 65536;
        if (compressedLength < 1 || compressedLength > maximum)
            throw new IOException("Invalid zlib rectangle size=" + compressedLength);
        byte[] compressed = new byte[compressedLength];
        in.readFully(compressed);
        byte[] decoded = new byte[w * h * 4];
        if (!zlibInflater.needsInput())
            throw new IOException("Previous zlib rectangle has trailing compressed input");
        zlibInflater.setInput(compressed);
        int length = 0;
        try {
            while (length < decoded.length) {
                int count = zlibInflater.inflate(decoded, length, decoded.length - length);
                if (count == 0) throw new IOException("Truncated or invalid VNC zlib rectangle");
                length += count;
            }
            // Consume the SYNC_FLUSH marker of this persistent compression stream.
            byte[] flush = new byte[32];
            while (!zlibInflater.needsInput()) {
                int before = zlibInflater.getRemaining();
                int count = zlibInflater.inflate(flush);
                if (count != 0) throw new IOException("Extra decompressed zlib pixels");
                if (!zlibInflater.needsInput() && before == zlibInflater.getRemaining())
                    throw new IOException("Invalid zlib stream boundary");
            }
        } catch (DataFormatException error) {
            throw new IOException("Invalid VNC zlib compressed frame", error);
        }
        for (int row = 0; row < h; row++) {
            int at = (y + row) * width + x;
            for (int col = 0, pos = row * w * 4; col < w; col++, pos += 4) {
                int blue = decoded[pos] & 255;
                int green = decoded[pos + 1] & 255;
                int red = decoded[pos + 2] & 255;
                pixels[at + col] = 0xff000000 | red << 16 | green << 8 | blue;
            }
        }
    }

    /** Returns true when an updated desktop size requires a non-incremental repaint. */
    private boolean readFramebufferUpdate(DataInputStream in) throws IOException {
        in.readUnsignedByte(); // pad
        int count = in.readUnsignedShort();
        int rawRects = 0, zlibRects = 0, copyRects = 0, resizeRects = 0;
        List<FrameRegion> patches = listener.usesFrameRegions() ? new ArrayList<>() : null;
        long rawPixels = 0, nonBlackRawPixels = 0, nonZeroPaddingBytes = 0;
        for (int i = 0; i < count; i++) {
            int x = in.readUnsignedShort(), y = in.readUnsignedShort();
            int w = in.readUnsignedShort(), h = in.readUnsignedShort();
            int encoding = in.readInt();
            if (encoding == -223) { // DesktopSize: x/y must be zero
                if (x != 0 || y != 0) throw new IOException("Invalid DesktopSize");
                resizeRects++;
                if (w != width || h != height) resize(w, h);
                continue;
            }
            if (encoding == -308) { // ExtendedDesktopSize; x=reason, y=status
                int screens = in.readUnsignedByte();
                skip(in, 3);
                if (screens < 1 || screens > 16) throw new IOException("Invalid remote screen count");
                for (int screen = 0; screen < screens; screen++) {
                    int id = in.readInt();
                    skip(in, 12); // x/y/width/height/flags
                    if (screen == 0) primaryScreenId = id;
                }
                if (!extendedDesktopSize) {
                    extendedDesktopSize = true;
                    listener.onResizeCapabilityChanged(true);
                }
                listener.onDiagnostic("ExtendedDesktopSize reply reason=" + x + " status=" + y
                        + " size=" + w + "x" + h + " screens=" + screens);
                if (y == 0 && (w != width || h != height)) {
                    resize(w, h);
                    resizeRects++;
                }
                continue;
            }
            if (w == 0 || h == 0 || x + w > width || y + h > height)
                throw new IOException("Rectangle outside framebuffer");
            if (encoding == 0) {
                rawRects++;
                rawPixels += (long) w * h;
                byte[] line = new byte[w * 4];
                for (int row = 0; row < h; row++) {
                    in.readFully(line);
                    int at = (y + row) * width + x;
                    for (int col = 0, p = 0; col < w; col++, p += 4) {
                        int blue = line[p] & 255, green = line[p + 1] & 255, red = line[p + 2] & 255;
                        if ((blue | green | red) != 0) nonBlackRawPixels++;
                        if (line[p + 3] != 0) nonZeroPaddingBytes++;
                        pixels[at + col] = 0xff000000 | (red << 16) | (green << 8) | blue;
                    }
                }
            } else if (encoding == 6) {
                zlibRects++;
                readZlibRegion(in, x, y, w, h);
            } else if (encoding == 1) {
                copyRects++;
                int sourceX = in.readUnsignedShort(), sourceY = in.readUnsignedShort();
                if (sourceX + w > width || sourceY + h > height)
                    throw new IOException("CopyRect source outside framebuffer");
                int[] tmp = new int[w * h];
                for (int row = 0; row < h; row++)
                    System.arraycopy(pixels, (sourceY + row) * width + sourceX, tmp, row * w, w);
                for (int row = 0; row < h; row++)
                    System.arraycopy(tmp, row * w, pixels, (y + row) * width + x, w);
            } else throw new IOException("Unsupported framebuffer encoding " + encoding);
            if (patches != null) patches.add(snapshotRegion(x, y, w, h));
        }
        framebufferUpdates++;
        if (framebufferUpdates <= 8 || framebufferUpdates % 60 == 0 || resizeRects > 0)
            listener.onDiagnostic("FramebufferUpdate #" + framebufferUpdates + " rects=" + count
                    + " raw=" + rawRects + " zlib=" + zlibRects
                    + " copy=" + copyRects + " desktopSize=" + resizeRects
                    + " rawPixels=" + rawPixels + " nonBlackWire=" + nonBlackRawPixels
                    + " nonZero4thByte=" + nonZeroPaddingBytes + " size=" + width + "x" + height);
        if (rawRects > 0 || zlibRects > 0 || copyRects > 0) {
            if (patches != null) listener.onFrameRegions(width, height, patches, pixels);
            else listener.onFrame(width, height, pixels);
        }
        return resizeRects > 0;
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

    @FunctionalInterface private interface OutboundTask {
        void run() throws IOException;
    }

    /** Queue a network write without blocking Android's UI or pointer-input callbacks. */
    private boolean enqueueOutbound(OutboundTask task) {
        if (!active.get() || !ready.get()) return false;
        try {
            outbound.execute(() -> {
                if (!active.get() || !ready.get()) return;
                try {
                    task.run();
                } catch (IOException error) {
                    listener.onDiagnostic("Outbound IO error: " + error);
                    listener.onError("VNC 发送失败：" + error.getMessage());
                    close();
                }
            });
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    /** Must run on the outbound worker; called only by enqueueOutbound tasks. */
    private void writePointer(int x, int y, int buttons) throws IOException {
        synchronized (writeLock) {
            if (!ready.get() || output == null) return;
            output.writeByte(5);
            output.writeByte(buttons);
            output.writeShort(x);
            output.writeShort(y);
            output.flush();
        }
    }

    public void sendPointer(int x, int y, int buttons) {
        if (!ready.get() || width == 0 || height == 0) return;
        cursorX = Math.max(0, Math.min(width - 1, x));
        cursorY = Math.max(0, Math.min(height - 1, y));
        final int atX = cursorX, atY = cursorY;
        listener.onPointerMoved(atX, atY, width, height);
        enqueueOutbound(() -> writePointer(atX, atY, buttons));
    }

    public void setButtons(int buttons) {
        buttonMask = buttons;
        sendPointer(cursorX, cursorY, buttons);
    }

    /** Mouse motion is coalesced so that fast hardware mice cannot create an unbounded queue. */
    public void sendRelativeMove(float dx, float dy) {
        if (!ready.get() || width == 0 || height == 0) return;
        cursorX = Math.max(0, Math.min(width - 1, cursorX + Math.round(dx)));
        cursorY = Math.max(0, Math.min(height - 1, cursorY + Math.round(dy)));
        listener.onPointerMoved(cursorX, cursorY, width, height);
        scheduleLatestMotion();
    }

    private void scheduleLatestMotion() {
        if (!motionQueued.compareAndSet(false, true)) return;
        if (!enqueueOutbound(() -> {
            final int atX = cursorX, atY = cursorY, mask = buttonMask;
            try {
                writePointer(atX, atY, mask);
            } finally {
                motionQueued.set(false);
                if (atX != cursorX || atY != cursorY || mask != buttonMask)
                    scheduleLatestMotion();
            }
        })) motionQueued.set(false);
    }

    public void click(int button) {
        final int atX = cursorX, atY = cursorY, previousButtons = buttonMask;
        enqueueOutbound(() -> {
            writePointer(atX, atY, previousButtons | button);
            writePointer(atX, atY, previousButtons);
        });
    }

    public void scroll(boolean up) { click(up ? 8 : 16); }

    public void sendKey(int keysym, boolean pressed) {
        if (keysym == 0) return;
        enqueueOutbound(() -> {
            synchronized (writeLock) {
                if (!ready.get() || output == null) return;
                output.writeByte(4);
                output.writeByte(pressed ? 1 : 0);
                output.writeShort(0);
                output.writeInt(keysym);
                output.flush();
            }
        });
    }

    @Override public void close() {
        closed.set(true);
        active.set(false);
        ready.set(false);
        outbound.shutdownNow();
        Socket s = socket; socket = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
}
