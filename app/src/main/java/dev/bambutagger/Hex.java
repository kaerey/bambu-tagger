package dev.bambutagger;

import java.util.Locale;

public final class Hex {
    private Hex() {}

    public static byte[] decode(String raw) {
        String s = raw.replace(" ", "").replace(":", "").trim();
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("Odd hexadecimal length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hexadecimal data");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String encode(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02X", b & 255));
        return sb.toString();
    }

    public static String ascii(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            int n = b & 255;
            if (n == 0) break;
            if (n >= 32 && n <= 126) out.append((char)n);
        }
        return out.toString().trim();
    }
}
