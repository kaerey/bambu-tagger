package dev.bambutagger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Published Bambu RFID-A/RFID-B sector key derivation, not RSA signing. */
public final class BambuKeys {
    private static final byte[] MASTER = Hex.decode("9A759CF2C4F7CAFF222CB9769B41BC96");
    private BambuKeys() {}

    private static byte[] hmac(byte[] secret, byte[] value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value);
    }

    public static byte[][] derive(byte[] uid, char kind) throws GeneralSecurityException {
        if (uid.length != 4 || (kind != 'A' && kind != 'B'))
            throw new IllegalArgumentException("Expected a four-byte UID and key A or B");
        byte[] prk = hmac(MASTER, uid);
        byte[] info = ("RFID-" + kind + "\0").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        byte[] last = new byte[0];
        int counter = 1;
        while (stream.size() < 96) {
            byte[] input = new byte[last.length + info.length + 1];
            System.arraycopy(last, 0, input, 0, last.length);
            System.arraycopy(info, 0, input, last.length, info.length);
            input[input.length - 1] = (byte)counter++;
            last = hmac(prk, input);
            stream.write(last, 0, last.length);
        }
        byte[] all = stream.toByteArray();
        byte[][] keys = new byte[16][6];
        for (int i = 0; i < 16; i++) keys[i] = Arrays.copyOfRange(all, i * 6, i * 6 + 6);
        return keys;
    }
}
