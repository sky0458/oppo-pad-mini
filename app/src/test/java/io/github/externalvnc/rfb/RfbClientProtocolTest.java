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
