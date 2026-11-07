# Real-phone validation checklist

No APK was built in the file-creation environment. Perform these tests with an expendable fob only.

## Offline library / startup
- [ ] App starts with NFC disabled without crashing.
- [ ] Offline SUNLU Yellow and Sunflower Yellow are visible and have distinct UIDs.
- [ ] Select SUNLU Yellow: expected UID `0524A124`, Bambu PLA Basic Yellow, hex `#F4EE2A`.
- [ ] Search "yellow", "polymaker", "petg" filters saved cards correctly.

## Live library
- [ ] Connect to Internet, select **Find Yellow in GitHub**.
- [ ] Browse `PLA/PLA Basic/Yellow` and select one UID directory; load succeeds.
- [ ] Airplane mode: already-cached directory loads with clear offline feedback.
- [ ] Selecting a different repository or invalid name reports a useful error.

## NFC — read only
- [ ] Read blank known-working blue Gen 2/CUID keyfob, 16 sectors readable, factory-writable status Yes.
- [ ] Read an already-cloned Bambu fob: identify `87878769` locked sectors, report not writable, and refuse another write *before modifying any block*.
- [ ] NTAG215 tag rejected as unsupported with no write.
- [ ] Fixed UID PVC puck reads but write should fail safely; never expect it to clone.

## NFC — write and verify (sacrificial CUID fob)
- [ ] Preflight shows actual old UID and sector count, no write on first scan.
- [ ] A *different* tag at write tap is rejected before writing.
- [ ] After explicit confirmation, complete a 16-sector write with strong NFC contact.
- [ ] Lift and scan again: UID must be `0524A124`, 48 data blocks and trailer keys verified.
- [ ] AMS 2 Pro recognizes Bambu PLA Basic Yellow.
- [ ] Re-scan after AMS reading to ensure tag still works.

## Errors / recovery
- [ ] Remove fob while writing. App describes partial-write risk and does not falsely claim success.
- [ ] Test recovery with original UID recorded in app preferences and backups; do not promise full restoration.
- [ ] Check no write starts on screen rotations or background/foreground transitions.


## Experimental Reusable Tag Mode validation
- [ ] Mode defaults OFF after app restart; requires warning confirmation when enabled.
- [ ] Fresh spare CUID tag reads 16/16 sectors and factory permissions `FF078069`.
- [ ] Select SUNLU Yellow; turn ON Reusable Tag Mode; confirm the write dialog clearly says EXPERIMENTAL.
- [ ] First tap only previews, second tap writes, third tap verifies target UID, all 48 data blocks, keys A/B, and all 16 `FF078069` access-byte values.
- [ ] Verify displays 'AMS acceptance UNKNOWN' and does not assert an exact Bambu dump.
- [ ] Test AMS 2 Pro recognition. Afterwards inspect the fob and confirm writable permissions remain on all 16 sectors.
- [ ] Attempt second write with another material only if both AMS acceptance and writability survived.
- [ ] Exact mode still expects `87878769` access bytes; exact clones are rejected by preflight for rewriting.
- [ ] Interrupted writes remain errors, never successes. Keep backups; avoid genuine Bambu tags.
