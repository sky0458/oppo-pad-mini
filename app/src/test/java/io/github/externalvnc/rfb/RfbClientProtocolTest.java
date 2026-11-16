package io.github.externalvnc.rfb;

import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;

import static org.junit.Assert.*;

/** End-to-end RFB protocol fixture without needing macOS or an Android device. */
public class RfbClientProtocolTest {
    @Test public void rgb565DecoderPreservesPrimaryColorsAndGray() {
        assertEquals(0xffff0000, RfbClient.decodeRgb565(0xf800));
        assertEquals(0xff00ff00, RfbClient.decodeRgb565(0x07e0));
        assertEquals(0xff0000ff, RfbClient.decodeRgb565(0x001f));
        assertEquals(0xff7b7d7b, RfbClient.decodeRgb565(0x7bef));
    }

    @Test public void appleDisplayInfoAllowsSwitchingPhysicalScreens() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch displaysReceived = new CountDownLatch(1);
            CountDownLatch displaySelected = new CountDownLatch(1);
            CountDownLatch layoutUpdated = new CountDownLatch(1);
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicReference<java.util.List<RfbClient.RemoteDisplay>> reported = new AtomicReference<>();
            Future<?> serverFuture = threads.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    out.write("RFB 003.889\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                    byte[] greeting = new byte[12]; in.readFully(greeting);
                    assertEquals("RFB 003.008\n", new String(greeting, StandardCharsets.US_ASCII));
                    out.writeByte(1); out.writeByte(1); out.flush();
                    assertEquals(1, in.readUnsignedByte());
                    out.writeInt(0); out.flush();
                    assertEquals(1, in.readUnsignedByte());
                    out.writeShort(8); out.writeShort(4); out.write(new byte[16]);
                    out.writeInt(5); out.write("apple".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    byte[] pixelFormat = new byte[20]; in.readFully(pixelFormat);
                    assertEquals(2, in.readUnsignedByte());
                    assertEquals(0, in.readUnsignedByte());
                    assertEquals(7, in.readUnsignedShort());
                    int[] encodings = new int[7];
                    for (int i = 0; i < encodings.length; i++) encodings[i] = in.readInt();
                    assertTrue(Arrays.stream(encodings).anyMatch(id -> id == 1101));
                    assertTrue(Arrays.stream(encodings).anyMatch(id -> id == 1105));
                    assertFalse(readUpdateRequestIncremental(in));

                    // Apple DisplayInfo rectangle: two physical screens, IDs 101/202.
                    out.writeByte(0); out.writeByte(0); out.writeShort(1);
                    writeRectangleHeader(out, 0, 0, 8, 4, 1101);
                    out.writeShort(8); out.writeShort(4); out.writeShort(2); out.writeShort(0);
                    for (int id : new int[]{101, 202}) {
                        out.writeInt(id); out.writeShort(4); out.writeShort(4);
                        out.writeInt(0); out.write(new byte[16]);
                    }
                    out.flush();
                    // The framebuffer reader and outbound input worker are independent:
                    // either message may win the socket write lock first.
                    boolean gotIncremental = false, gotSelect = false;
                    for (int message = 0; message < 2; message++) {
                        int type = in.readUnsignedByte();
                        if (type == 3) {
                            assertEquals(1, in.readUnsignedByte());
                            assertEquals(0, in.readUnsignedShort());
                            assertEquals(0, in.readUnsignedShort());
                            assertEquals(8, in.readUnsignedShort());
                            assertEquals(4, in.readUnsignedShort());
                            gotIncremental = true;
                        } else if (type == 0x0d) {
                            assertEquals(0, in.readUnsignedByte()); // switch one screen
                            assertEquals(0, in.readUnsignedShort());
                            assertEquals(202, in.readInt());
                            gotSelect = true;
                        } else fail("Unexpected outbound message type " + type);
                    }
                    assertTrue(gotIncremental);
                    assertTrue(gotSelect);
                    displaySelected.countDown();

                    // Apple DisplayInfo2: new framebuffer 4x4, two monitor identities.
                    out.writeByte(0); out.writeByte(0); out.writeShort(1);
                    writeRectangleHeader(out, 0, 0, 4, 4, 1105);
                    out.writeShort(20 + 2 * 56);
                    out.writeShort(1); out.writeInt(0); // version and header reserved
                    out.writeShort(4); out.writeShort(4); // framebuffer width/height
                    out.writeInt(202); out.writeInt(0); out.writeShort(2);
                    for (int n = 0; n < 2; n++) {
                        out.writeLong(Double.doubleToLongBits(1.0));
                        out.writeLong(Double.doubleToLongBits(1.0));
                        out.writeInt(n == 0 ? 101 : 202);
                        out.writeShort(0); out.writeShort(n * 4);
                        out.writeShort(4); out.writeShort((n + 1) * 4);
                        out.writeShort(0); out.writeShort(n * 4);
                        out.writeShort(4); out.writeShort((n + 1) * 4);
                        out.writeInt(n == 0 ? 1 : 0);
                        out.write(new byte[16]);
                    }
                    out.flush();
                    assertFalse(readUpdateRequestIncremental(in)); // new screen requires full repaint
                    layoutUpdated.countDown();
                    assertEquals(-1, in.read());
                    return null;
                }
            });
            RfbClient client = new RfbClient("127.0.0.1", server.getLocalPort(), "", "",
                    RfbClient.AuthMode.AUTO, new RfbClient.Listener() {
                @Override public void onConnected(String name, int w, int h) { }
                @Override public void onFrame(int w, int h, int[] pixels) { }
                @Override public void onError(String message) { failure.set(message); }
                @Override public void onDisconnected() { }
                @Override public void onDiagnostic(String message) { }
                @Override public void onRemoteDisplaysChanged(
                        java.util.List<RfbClient.RemoteDisplay> displays, boolean canSwitch) {
                    if (canSwitch && displays.size() == 2) {
                        reported.set(displays);
                        displaysReceived.countDown();
                    }
                }
            });
            Future<?> clientFuture = threads.submit(client::runBlocking);
            try {
                assertTrue(displaysReceived.await(10, TimeUnit.SECONDS));
                assertTrue(client.supportsDisplaySwitch());
                assertEquals(101, client.getRemoteDisplays().get(0).id);
                assertTrue(client.selectAppleDisplay(202L));
                assertTrue(displaySelected.await(10, TimeUnit.SECONDS));
                assertTrue(layoutUpdated.await(10, TimeUnit.SECONDS));
                assertEquals(4, client.getFramebufferWidth());
                assertEquals(4, client.getFramebufferHeight());
                assertEquals(4, reported.get().get(1).width);
            } finally {
                client.close();
                clientFuture.get(10, TimeUnit.SECONDS);
            }
            serverFuture.get(10, TimeUnit.SECONDS);
            assertNull(failure.get());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test public void extendedDesktopSizeMustBeAdvertisedBeforeResizeRequest() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch capability = new CountDownLatch(1);
            CountDownLatch updateSeen = new CountDownLatch(1);
            CountDownLatch resizeSeen = new CountDownLatch(1);
            Future<?> serverFuture = threads.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                    byte[] greeting = new byte[12]; in.readFully(greeting);
                    out.writeByte(1); out.writeByte(1); out.flush(); // None
                    assertEquals(1, in.readUnsignedByte());
                    out.writeInt(0); out.flush();
                    assertEquals(1, in.readUnsignedByte());
                    out.writeShort(4); out.writeShort(4); out.write(new byte[16]);
                    out.writeInt(4); out.write("test".getBytes(StandardCharsets.US_ASCII)); out.flush();
                    byte[] format = new byte[20]; in.readFully(format);
                    assertEquals(16, format[4]); // default fast color RGB565
                    byte[] encodings = new byte[24]; in.readFully(encodings);
                    assertFalse(readUpdateRequestIncremental(in));

                    out.writeByte(0); out.writeByte(0); out.writeShort(1);
                    writeRectangleHeader(out, 0, 0, 4, 4, -308);
                    out.writeByte(1); out.write(new byte[3]); // 1 screen
                    out.writeInt(23); // server screen ID
                    out.writeShort(0); out.writeShort(0); out.writeShort(4); out.writeShort(4);
                    out.writeInt(0); out.flush();
                    // A server's ExtendedDesktopSize announcement may trigger either
                    // an incremental request or a full repaint, depending on state.
                    readUpdateRequestIncremental(in);
                    updateSeen.countDown();

                    assertEquals(251, in.readUnsignedByte());
                    assertEquals(0, in.readUnsignedByte());
                    assertEquals(1920, in.readUnsignedShort());
                    assertEquals(1080, in.readUnsignedShort());
                    assertEquals(1, in.readUnsignedByte());
                    assertEquals(0, in.readUnsignedByte());
                    assertEquals(0, in.readUnsignedShort());
                    assertEquals(23, in.readInt());
                    assertEquals(0, in.readUnsignedShort());
                    assertEquals(0, in.readUnsignedShort());
                    assertEquals(1920, in.readUnsignedShort());
                    assertEquals(1080, in.readUnsignedShort());
                    assertEquals(0, in.readInt());
                    resizeSeen.countDown();
                    assertEquals(-1, in.read());
                    return null;
                }
            });
            AtomicReference<String> error = new AtomicReference<>();
            RfbClient client = new RfbClient("127.0.0.1", server.getLocalPort(), "", "",
                    RfbClient.AuthMode.AUTO, new RfbClient.Listener() {
                @Override public void onConnected(String name, int w, int h) { }
                @Override public void onFrame(int w, int h, int[] pixels) { }
                @Override public void onError(String message) { error.set(message); }
                @Override public void onDisconnected() { }
                @Override public void onDiagnostic(String message) { }
                @Override public void onResizeCapabilityChanged(boolean supported) {
                    if (supported) capability.countDown();
                }
            });
            Future<?> clientFuture = threads.submit(client::runBlocking);
            try {
                assertFalse(client.supportsRemoteResize());
                assertFalse(client.requestDesktopResize(1920, 1080));
                assertTrue(capability.await(10, TimeUnit.SECONDS));
                assertTrue(updateSeen.await(10, TimeUnit.SECONDS));
                assertTrue(client.supportsRemoteResize());
                assertTrue(client.requestDesktopResize(1920, 1080));
                assertTrue(resizeSeen.await(10, TimeUnit.SECONDS));
            } finally {
                client.close();
                clientFuture.get(10, TimeUnit.SECONDS);
            }
            serverFuture.get(10, TimeUnit.SECONDS);
            assertNull(error.get());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test public void desktopSizeCausesFullRefreshAndRawPixelsReachListener() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch nextUpdateRequested = new CountDownLatch(1);
            CountDownLatch inputEventsSeen = new CountDownLatch(1);
            Future<?> serverFuture = threads.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    byte[] version = new byte[12];
                    in.readFully(version);
                    assertEquals("RFB 003.008\n", new String(version, StandardCharsets.US_ASCII));

                    out.writeByte(1); // one security type
                    out.writeByte(1); // None
                    out.flush();
                    assertEquals(1, in.readUnsignedByte());
                    out.writeInt(0); // SecurityResult success
                    out.flush();
                    assertEquals(1, in.readUnsignedByte()); // ClientInit shared

                    out.writeShort(2); out.writeShort(2); // original dimensions
                    out.write(new byte[16]); // pixel format (client requests its own)
                    byte[] name = "mock-vnc".getBytes(StandardCharsets.UTF_8);
                    out.writeInt(name.length);
                    out.write(name);
                    out.flush();

                    byte[] pixelFormatRequest = new byte[20];
                    in.readFully(pixelFormatRequest);
                    assertEquals(0, pixelFormatRequest[0]);
                    byte[] encodingRequest = new byte[24];
                    in.readFully(encodingRequest);
                    assertEquals(2, encodingRequest[0]);
                    assertEquals(5, encodingRequest[3]);
                    assertFalse(readUpdateRequestIncremental(in)); // initial full repaint

                    // Server only advertises new dimensions. This must not be treated
                    // as a complete frame, and the next request must be non-incremental.
                    out.writeByte(0); out.writeByte(0); out.writeShort(1);
                    writeRectangleHeader(out, 0, 0, 3, 2, -223);
                    out.flush();
                    assertFalse(readUpdateRequestIncremental(in));

                    out.writeByte(0); out.writeByte(0); out.writeShort(2);
                    writeRectangleHeader(out, 0, 0, 3, 1, 0); // Raw fallback
                    for (int i = 0; i < 3; i++) {
                        out.writeByte(0x33 + i); // blue
                        out.writeByte(0x22);     // green
                        out.writeByte(0x11);     // red
                        out.writeByte(0);        // padding
                    }
                    writeRectangleHeader(out, 0, 1, 3, 1, 6); // preferred zlib encoding
                    byte[] rawBottomRow = new byte[12];
                    for (int i = 0; i < 3; i++) {
                        rawBottomRow[4 * i] = (byte) (0x36 + i);
                        rawBottomRow[4 * i + 1] = 0x22;
                        rawBottomRow[4 * i + 2] = 0x11;
                    }
                    Deflater deflater = new Deflater(Deflater.BEST_SPEED);
                    deflater.setInput(rawBottomRow);
                    byte[] compressed = new byte[128];
                    int compressedBytes = deflater.deflate(compressed, 0, compressed.length,
                            Deflater.SYNC_FLUSH);
                    out.writeInt(compressedBytes);
                    out.write(compressed, 0, compressedBytes);
                    deflater.end();
                    out.flush();
                    assertTrue(readUpdateRequestIncremental(in)); // next frame can be incremental
                    nextUpdateRequested.countDown();
                    assertPointer(in, 0, 2, 1); // coalesced mouse movement
                    assertPointer(in, 1, 2, 1); // button down
                    assertPointer(in, 0, 2, 1); // button up
                    assertEquals(4, in.readUnsignedByte()); // KeyEvent
                    assertEquals(1, in.readUnsignedByte());
                    assertEquals(0, in.readUnsignedShort());
                    assertEquals(0x0061, in.readInt());
                    assertFalse(readUpdateRequestIncremental(in)); // manual refresh
                    inputEventsSeen.countDown();
                    assertEquals(-1, in.read()); // wait until the client closes deliberately
                    return null;
                }
            });

            CountDownLatch frameReceived = new CountDownLatch(1);
            AtomicReference<int[]> received = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            RfbClient client = new RfbClient("127.0.0.1", server.getLocalPort(), "", "",
                    RfbClient.AuthMode.AUTO, new RfbClient.Listener() {
                @Override public void onConnected(String desktop, int width, int height) {
                    assertEquals("mock-vnc", desktop);
                }
                @Override public void onFrame(int width, int height, int[] pixels) {
                    assertEquals(3, width);
                    assertEquals(2, height);
                    received.set(Arrays.copyOf(pixels, pixels.length));
                    frameReceived.countDown();
                }
                @Override public void onError(String message) { failure.set(message); }
                @Override public void onDisconnected() { }
                @Override public void onDiagnostic(String message) { }
            });
            client.setFastColor(false); // This mock deliberately sends 32bpp Raw and zlib pixels.
            Future<?> clientFuture = threads.submit(client::runBlocking);
            try {
                assertTrue("RFB frame was not received", frameReceived.await(10, TimeUnit.SECONDS));
                int[] image = received.get();
                assertNotNull(image);
                assertEquals(6, image.length);
                assertEquals(0xff112233, image[0]);
                assertEquals(0xff112238, image[5]);
                assertTrue("Next framebuffer request was not observed",
                        nextUpdateRequested.await(10, TimeUnit.SECONDS));
                client.sendRelativeMove(1, 0);
                client.click(1);
                client.sendKey(0x0061, true);
                assertTrue("Manual refresh must be queued", client.requestFullRefresh());
                assertTrue("Async pointer, key and refresh messages were not delivered",
                        inputEventsSeen.await(10, TimeUnit.SECONDS));
            } finally {
                client.close();
                clientFuture.get(10, TimeUnit.SECONDS);
            }
            serverFuture.get(10, TimeUnit.SECONDS);
            assertNull(failure.get());
        } finally {
            threads.shutdownNow();
        }
    }

    private static void writeRectangleHeader(DataOutputStream out,
                                             int x, int y, int width, int height, int encoding) throws Exception {
        out.writeShort(x); out.writeShort(y); out.writeShort(width); out.writeShort(height);
        out.writeInt(encoding);
    }

    private static boolean readUpdateRequestIncremental(DataInputStream in) throws Exception {
        byte[] request = new byte[10];
        in.readFully(request);
        assertEquals(3, request[0]);
        return request[1] != 0;
    }

    private static void assertPointer(DataInputStream in, int buttons, int x, int y) throws Exception {
        assertEquals(5, in.readUnsignedByte());
        assertEquals(buttons, in.readUnsignedByte());
        assertEquals(x, in.readUnsignedShort());
        assertEquals(y, in.readUnsignedShort());
    }
}
