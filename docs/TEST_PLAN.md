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
- [ ] Read blank known-working blue Gen 2/CUID keyfob, 16 sectors readable.
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
