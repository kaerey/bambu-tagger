package dev.bambutagger;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** A complete signed 1K dump. Does not forge or modify Bambu RFID signatures. */
public final class BambuDump {
    public final byte[][] blocks;
    public final byte[] uid;
    public final String uidHex;
    public final String filament;
    public final String family;
    public final String rgba;
    public final String spoolId;
    public final byte[][] keysA;
    public final byte[][] keysB;

    private BambuDump(byte[][] blocks) throws GeneralSecurityException {
        this.blocks = blocks;
        uid = Arrays.copyOfRange(blocks[0], 0, 4);
        uidHex = Hex.encode(uid);
        int bcc = (uid[0] ^ uid[1] ^ uid[2] ^ uid[3]) & 255;
        if ((blocks[0][4] & 255) != bcc) throw new IllegalArgumentException("Invalid UID checksum (BCC)");
        keysA = BambuKeys.derive(uid, 'A');
        keysB = BambuKeys.derive(uid, 'B');
        for (int sector = 0; sector < 16; sector++) {
            byte[] trailer = blocks[sector * 4 + 3];
            if (!Arrays.equals(keysA[sector], Arrays.copyOfRange(trailer, 0, 6)) ||
                !Arrays.equals(keysB[sector], Arrays.copyOfRange(trailer, 10, 16))) {
                throw new IllegalArgumentException("Incorrect sector keys at sector " + sector + "; unsupported dump");
            }
        }
        family = Hex.ascii(blocks[2]);
        filament = Hex.ascii(blocks[4]);
        rgba = Hex.encode(Arrays.copyOfRange(blocks[5], 0, 4));
        spoolId = Hex.encode(blocks[9]);
    }

    public static BambuDump fromBinary(byte[] data) throws GeneralSecurityException {
        if (data.length != 1024) throw new IllegalArgumentException("Expected exactly 1024 bytes");
        byte[][] blocks = new byte[64][16];
        for (int i = 0; i < 64; i++) System.arraycopy(data, i * 16, blocks[i], 0, 16);
        return new BambuDump(blocks);
    }

    public static BambuDump fromJson(String data) throws JSONException, GeneralSecurityException {
        JSONObject root = new JSONObject(data);
        JSONObject card = root.getJSONObject("Card");
        JSONObject sourceBlocks = root.getJSONObject("blocks");
        byte[][] blocks = new byte[64][16];
        for (int i = 0; i < 64; i++) {
            blocks[i] = Hex.decode(sourceBlocks.getString(Integer.toString(i)));
            if (blocks[i].length != 16) throw new IllegalArgumentException("Invalid block length at " + i);
        }
        BambuDump dump = new BambuDump(blocks);
        if (!dump.uidHex.equalsIgnoreCase(card.getString("UID")))
            throw new IllegalArgumentException("UID in metadata does not match block zero");
        return dump;
    }

    public byte[] toBinary() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        for (byte[] block : blocks) out.write(block, 0, block.length);
        return out.toByteArray();
    }

    public String colorRgb() { return "#" + rgba.substring(0, 6); }
}
