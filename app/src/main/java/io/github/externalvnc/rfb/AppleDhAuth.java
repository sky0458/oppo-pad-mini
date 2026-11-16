package io.github.externalvnc.rfb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Apple Remote Desktop / macOS Screen Sharing RFB security type 30 (DH + AES). */
public final class AppleDhAuth {
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final SecureRandom RANDOM = new SecureRandom();

    private AppleDhAuth() { }

    public static void authenticate(DataInputStream in, DataOutputStream out,
                                    String username, String password, boolean useCbc) throws Exception {
        if (username == null || username.isEmpty() || password == null || password.isEmpty())
            throw new IOException("Mac 账号认证需要填写 Mac 本地用户名和登录密码");

        // SecurityType 30 challenge: generator(u16), key length(u16), P, B.
        int generator = in.readUnsignedShort();
        int keyLength = in.readUnsignedShort();
        if (keyLength < 64 || keyLength > 512 || generator < 2)
            throw new IOException("Invalid Apple DH parameters (keyLength=" + keyLength + ")");
        byte[] primeBytes = new byte[keyLength];
        byte[] serverBytes = new byte[keyLength];
        in.readFully(primeBytes);
        in.readFully(serverBytes);

        BigInteger prime = new BigInteger(1, primeBytes);
        BigInteger peer = new BigInteger(1, serverBytes);
        if (prime.bitLength() < 500 || !prime.isProbablePrime(40)
                || peer.compareTo(TWO) < 0 || peer.compareTo(prime.subtract(TWO)) > 0)
            throw new IOException("Invalid Apple DH modulus or server public key");

        // Generate a fresh secret for every connection; transmitted public value is fixed-width.
        BigInteger privateValue = new BigInteger(Math.min(256, prime.bitLength() - 2), RANDOM)
                .mod(prime.subtract(BigInteger.valueOf(3))).add(TWO);
        BigInteger publicValue = BigInteger.valueOf(generator).modPow(privateValue, prime);
        byte[] shared = fixedWidth(peer.modPow(privateValue, prime), keyLength);
        byte[] aesKey = MessageDigest.getInstance("MD5").digest(shared);

        // Two NUL-terminated UTF-8 fields of exactly 64 bytes; random padding.
        byte[] credentials = new byte[128];
        RANDOM.nextBytes(credentials);
        putField(credentials, 0, username);
        putField(credentials, 64, password);

        Cipher cipher = Cipher.getInstance(useCbc ? "AES/CBC/NoPadding" : "AES/ECB/NoPadding");
        SecretKeySpec key = new SecretKeySpec(aesKey, "AES");
        if (useCbc) cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(new byte[16]));
        else cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(credentials);
        synchronized (out) {
            out.write(ciphertext);
            out.write(fixedWidth(publicValue, keyLength));
            out.flush();
        }
        Arrays.fill(shared, (byte) 0);
        Arrays.fill(aesKey, (byte) 0);
        Arrays.fill(credentials, (byte) 0);
    }

    private static void putField(byte[] output, int offset, String text) throws IOException {
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        if (value.length > 63) throw new IOException("Mac 用户名或密码超过 63 字节，Type 30 无法编码");
        System.arraycopy(value, 0, output, offset, value.length);
        output[offset + value.length] = 0;
    }

    static byte[] fixedWidth(BigInteger value, int size) {
        byte[] raw = value.toByteArray();
        if (raw.length > size + 1) throw new IllegalArgumentException("DH value too long");
        byte[] result = new byte[size];
        int sourceStart = Math.max(0, raw.length - size);
        int length = raw.length - sourceStart;
        System.arraycopy(raw, sourceStart, result, size - length, length);
        return result;
    }
}
