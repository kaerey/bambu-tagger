package dev.bambutagger;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Native Android 13.56 MHz MIFARE Classic 1K writer. Only works on phones with MifareClassic support. */
public final class TagCloner {
    public interface Progress { void update(String message); }
    public static final byte[] FF = Hex.decode("FFFFFFFFFFFF");
    public static final byte[] ZERO = Hex.decode("000000000000");
    private final Context context;
    private final android.content.SharedPreferences preferences;

    public static final class CardInfo {
        public final String uid;
        public final int sectors;
        public final int readableSectors;
        public final String material;
        CardInfo(String uid, int sectors, int readableSectors, String material) {
            this.uid=uid;this.sectors=sectors;this.readableSectors=readableSectors;this.material=material;
        }
    }

    private static final class Auth {
        final byte[] key;
        final boolean keyB;
        Auth(byte[] key, boolean keyB) {this.key=key;this.keyB=keyB;}
    }

    public TagCloner(Context context) {
        this.context=context.getApplicationContext();
        this.preferences=this.context.getSharedPreferences("bambu_tagger_recovery",Context.MODE_PRIVATE);
    }

    private static MifareClassic open(Tag tag) throws Exception {
        MifareClassic mifare = MifareClassic.get(tag);
        if (mifare == null)
            throw new IllegalArgumentException("This phone/tag combination does not expose MIFARE Classic. NTAG215 and 125 kHz tags cannot be used.");
        mifare.connect();
        if (mifare.getSectorCount() != 16 || mifare.getSize() != 1024) {
            mifare.close();
            throw new IllegalArgumentException("This requires a 1K MIFARE Classic-compatible tag with 16 sectors.");
        }
        return mifare;
    }

    private static void add(List<Auth> list, byte[] key, boolean isB) {
        for (Auth a : list) if (Arrays.equals(a.key,key) && a.keyB==isB) return;
        list.add(new Auth(key, isB));
    }

    private Auth auth(MifareClassic mifare, int sector, byte[] uid,
                             BambuDump next) throws Exception {
        List<Auth> keys = new ArrayList<>();
        // Blank cards and previously cloned Bambu tags are both supported.
        add(keys, FF, false);
        add(keys, FF, true);
        add(keys, ZERO, false);
        add(keys, ZERO, true);
        if (uid.length == 4) {
            byte[][] oldA = BambuKeys.derive(uid, 'A');
            byte[][] oldB = BambuKeys.derive(uid, 'B');
            add(keys, oldA[sector], false);
            add(keys, oldB[sector], true);
        }
        String recoveryUid=preferences.getString("last_original_uid","");
        if(recoveryUid.length()==8) {
            try {
                byte[] saved=Hex.decode(recoveryUid);
                byte[][] recoveryA=BambuKeys.derive(saved,'A');
                byte[][] recoveryB=BambuKeys.derive(saved,'B');
                add(keys,recoveryA[sector],false);
                add(keys,recoveryB[sector],true);
            }catch(Exception ignored) {}
        }
        if (next != null) {
            add(keys, next.keysA[sector], false);
            add(keys, next.keysB[sector], true);
        }
        for (Auth key : keys) {
            try {
                boolean ok = key.keyB
                    ? mifare.authenticateSectorWithKeyB(sector, key.key)
                    : mifare.authenticateSectorWithKeyA(sector, key.key);
                if (ok) return key;
            } catch (Exception ignored) {
                // Try next known candidate. A failed auth does not imply a damaged tag.
            }
        }
        throw new IllegalStateException("Cannot authenticate sector " + sector + ". Tag may use unknown keys. Aborting without a write.");
    }

    public CardInfo inspect(Tag tag) throws Exception {
        MifareClassic mifare=open(tag);
        try {
            byte[] uid=tag.getId();
            int readable=0;
            String material="Unknown";
            for (int sector=0;sector<16;sector++) {
                try {
                    auth(mifare,sector,uid,null);
                    readable++;
                    if (sector==1) material=Hex.ascii(mifare.readBlock(4));
                } catch(Exception ignored) { /* Report unavailable sectors; read-only inspection. */ }
            }
            return new CardInfo(Hex.encode(uid),16,readable,material);
        } finally {mifare.close();}
    }

    private String backup(MifareClassic mifare, byte[] uid, BambuDump target, Progress progress) throws Exception {
        JSONObject backup = new JSONObject();
        backup.put("uid", Hex.encode(uid));
        backup.put("timestamp", System.currentTimeMillis());
        backup.put("note", "Read-back backup. Sector trailer keys are masked on MIFARE reads; this is not guaranteed to be a restorable full image.");
        JSONArray sectors = new JSONArray();
        for (int sector=0;sector<16;sector++) {
            Auth key = auth(mifare,sector,uid,target);
            JSONObject record = new JSONObject();
            record.put("sector",sector);
            record.put("authKeyType",key.keyB?"B":"A");
            record.put("authKey",Hex.encode(key.key));
            JSONArray blocks=new JSONArray();
            for(int block=0;block<4;block++) {
                blocks.put(Hex.encode(mifare.readBlock(sector*4+block)));
            }
            record.put("readBlocks",blocks);
            sectors.put(record);
            if (sector%4==3) progress.update("Backing up existing tag: " +(sector+1)+"/16 sectors");
        }
        backup.put("sectors",sectors);
        File directory = new File(context.getFilesDir(),"tag-backups");
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Failed to create local backup directory");
        String timestamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
        File file = new File(directory, Hex.encode(uid)+"_"+timestamp+".json");
        try(FileOutputStream output=new FileOutputStream(file)) {
            output.write(backup.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        return file.getName();
    }

    /**
     * Write sectors 15..1 first, leaving the manufacturer sector for last.
     * Block 0 must be written BEFORE the new sector-0 trailer: the Bambu trailer's
     * access bits restrict data writes and may lock out block-0 writing on some tags.
     * Some CUID tags also drop the RF session after UID replacement. If that happens,
     * keep the recovery UID available for reauthentication on a subsequent tap.
     * Success is never assumed until a separate NFC verification tap succeeds.
     */
    public String write(Tag tag, BambuDump target, Progress progress) throws Exception {
        MifareClassic mifare=open(tag);
        try {
            byte[] originalUid=tag.getId();
            String backupName=backup(mifare,originalUid,target,progress);
            preferences.edit().putString("last_original_uid",Hex.encode(originalUid)).commit();
            progress.update("Backup saved. Writing 15 data sectors — keep tag against phone.");
            for(int sector=15;sector>=1;sector--) {
                auth(mifare,sector,originalUid,target);
                int first=mifare.sectorToBlock(sector);
                // Write normal data before the trailer changes write permissions.
                for(int offset=0;offset<3;offset++)
                    mifare.writeBlock(first+offset,target.blocks[first+offset]);
                mifare.writeBlock(first+3,target.blocks[first+3]);
                if (!mifare.authenticateSectorWithKeyA(sector,target.keysA[sector]))
                    throw new IllegalStateException("Sector "+sector+" did not accept the new Key A. Tag may be partially written.");
                for(int offset=0;offset<3;offset++) {
                    if(!Arrays.equals(mifare.readBlock(first+offset),target.blocks[first+offset]))
                        throw new IllegalStateException("Write/read mismatch at sector "+sector+", block "+offset);
                }
                progress.update("Written "+(16-sector)+"/16 sectors. Keep fob steady.");
            }
            auth(mifare,0,originalUid,target);
            mifare.writeBlock(1,target.blocks[1]);
            mifare.writeBlock(2,target.blocks[2]);
            progress.update("Changing UID and sector-0 permissions. Do not move the fob.");
            // Gen 2/CUID manufacturer block is written with a normal Mifare write.
            // A fixed-UID or Gen 1 tag may reject this write.
            mifare.writeBlock(0,target.blocks[0]);
            mifare.writeBlock(3,target.blocks[3]);
            progress.update("Write command finished. Lift the fob and tap again to verify.");
            return backupName;
        } finally {mifare.close();}
    }

    public String verify(Tag tag, BambuDump target, Progress progress) throws Exception {
        MifareClassic mifare=open(tag);
        try {
            String uid=Hex.encode(tag.getId());
            if (!uid.equalsIgnoreCase(target.uidHex))
                throw new IllegalStateException("UID mismatch. Expected " +target.uidHex+", found "+uid);
            for(int sector=0;sector<16;sector++) {
                if (!mifare.authenticateSectorWithKeyA(sector,target.keysA[sector]))
                    throw new IllegalStateException("Key A verification failed in sector " +sector);
                for(int offset=0;offset<3;offset++) {
                    int absolute=sector*4+offset;
                    byte[] actual=mifare.readBlock(absolute);
                    if(!Arrays.equals(actual,target.blocks[absolute]))
                        throw new IllegalStateException("Verification mismatch: sector "+sector+", block "+offset);
                }
                byte[] trailer=mifare.readBlock(sector*4+3);
                for(int i=6;i<=9;i++) {
                    if(trailer[i]!=target.blocks[sector*4+3][i])
                        throw new IllegalStateException("Sector trailer permissions mismatch in sector "+sector);
                }
                if (!mifare.authenticateSectorWithKeyB(sector,target.keysB[sector]))
                    throw new IllegalStateException("Key B verification failed in sector "+sector);
                if(sector%4==3)progress.update("Verified "+(sector+1)+"/16 sectors");
            }
            return "UID, 48 data blocks, 16 access trailers, and Key A/B authentication verified. AMS recognition still depends on the printer.";
        } finally {mifare.close();}
    }
}
