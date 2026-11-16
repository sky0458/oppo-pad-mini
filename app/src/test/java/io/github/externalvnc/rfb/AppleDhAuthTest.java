package io.github.externalvnc.rfb;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

public class AppleDhAuthTest {
    // RFC 2409 1024-bit MODP group, common to Apple DH auth fixtures.
    private static final BigInteger PRIME = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
            "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
            "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE65381" +
            "FFFFFFFFFFFFFFFF", 16);

    @Test public void macAuthEcb() throws Exception { verifyRoundTrip(false); }

    @Test public void macAuthCbc() throws Exception { verifyRoundTrip(true); }

    private void verifyRoundTrip(boolean cbc) throws Exception {
        BigInteger serverPrivate = BigInteger.valueOf(19);
        BigInteger serverPublic = BigInteger.TWO.modPow(serverPrivate, PRIME);
        ByteArrayOutputStream challenge = new ByteArrayOutputStream();
        DataOutputStream server = new DataOutputStream(challenge);
        server.writeShort(2);
        server.writeShort(128);
        server.write(AppleDhAuth.fixedWidth(PRIME, 128));
        server.write(AppleDhAuth.fixedWidth(serverPublic, 128));

        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        AppleDhAuth.authenticate(new DataInputStream(new ByteArrayInputStream(challenge.toByteArray())),
                new DataOutputStream(answer), "test-mac", "secret-longer-than-eight", cbc);
        byte[] reply = answer.toByteArray();
        assertEquals(256, reply.length);

        BigInteger clientPublic = new BigInteger(1, java.util.Arrays.copyOfRange(reply, 128, 256));
        byte[] secret = AppleDhAuth.fixedWidth(clientPublic.modPow(serverPrivate, PRIME), 128);
        byte[] key = MessageDigest.getInstance("MD5").digest(secret);
        Cipher cipher = Cipher.getInstance(cbc ? "AES/CBC/NoPadding" : "AES/ECB/NoPadding");
        if (cbc) cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        else cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] plain = cipher.doFinal(reply, 0, 128);
        assertEquals("test-mac", readField(plain, 0));
        assertEquals("secret-longer-than-eight", readField(plain, 64));
    }

    @Test public void rejectsInvalidDhKeyLength() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream server = new DataOutputStream(bytes);
        server.writeShort(2);
        server.writeShort(8);
        try {
            AppleDhAuth.authenticate(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                    new DataOutputStream(new ByteArrayOutputStream()), "u", "p", false);
            fail("Expected IOException");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("keyLength"));
        }
    }

    @Test public void rejectsTooLongUser() throws Exception {
        try {
            AppleDhAuth.authenticate(new DataInputStream(new ByteArrayInputStream(new byte[0])),
                    new DataOutputStream(new ByteArrayOutputStream()), "", "pass", false);
            fail("Expected IOException");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("用户名"));
        }
    }

    private String readField(byte[] data, int start) {
        int end = start;
        while (end < start + 64 && data[end] != 0) end++;
        return new String(data, start, end - start, StandardCharsets.UTF_8);
    }
}
