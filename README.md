# Bambu Tagger — Android (prototype v0.2-dev)

Native, touch-first Android app for writing compatible MIFARE Classic 1K magic NFC tags using **existing signed Bambu Lab filament RFID identities**. Includes 9 offline shortcuts (7 original aliases plus SUNLU Yellow and a Sunflower Yellow alternative) and a live, cached GitHub library browser.

**Important status:** This is a source-code prototype, **not yet built or tested on a physical phone**. The user's Android phone and blue CUID keyfob previously succeeded when writing with MIFARE Classic Tool, but the custom NFC write path here still requires a real-device test. Treat blank keyfobs as expendable. The app cannot change Bambu's cryptographic signature, create new signed identities, or make fixed-UID / Gen 1 tags work.

## What it currently implements

- Large, thumb-friendly native Android layout; no WebView or server.
- Saved third-party filament shortcuts, with color swatches and clearly labeled approximate matches.
- Bundled signed 1K dumps readable without internet, including **SUNLU Yellow** (`Bambu PLA Basic Yellow`, color `#F4EE2A`, UID `0524A124`) and **SUNLU Yellow — golden alternative** (`Bambu PLA Basic Sunflower Yellow`, `#FEC600`, UID `0625AFE6`).
- Public GitHub repository browsing `Material category → Material → Color → UID`, with signed dump download and on-device cache, using the format of `queengooborg/Bambu-Lab-RFID-Library`.
- Native `android.nfc.tech.MifareClassic` read / write / verify operations. Supports factory-formatted Gen 2/CUID tags which permit writing manufacturer block 0 with regular MIFARE write instructions; *FUID may work*, contingent on tag and hardware. After Bambu's `87878769` permissions are applied, the resulting tag's data blocks become read-only. **Treat a successful clone as one-time-use, not reusable for another filament.**
- Two-tap write workflow (read-only destination preview, explicit UID confirmation, second NFC tap to write), local pre-write backups, and separate read-back verification.
- Automatically derives Bambu MIFARE sector keys A/B from a tag UID using the community's published HKDF-SHA256 algorithm. Does **not** derive or forge RSA signatures.
- Optional alternate compatible GitHub repository (`owner/repo`) using the same folder and `-dump.json` schema.

## Android build (on a machine with Android Studio)

The build machine used for this project did **not** have the Android SDK, Android Gradle Plugin, or Gradle installed, so an APK could not be produced or tested here. The included source files are intended to be opened and compiled on a development machine.

1. Install a current Android Studio with **Android SDK Platform 35**, Android SDK Build Tools, JDK 17+.
2. Open the `BambuTagger_Android` folder in Android Studio.
3. The project uses **Android Gradle Plugin 8.9.2** and **Gradle 8.11.1**. The distribution URL is in `gradle/wrapper/gradle-wrapper.properties`, but the Gradle wrapper *JAR/scripts are not included*. If Android Studio requests a Gradle distribution, configure an installed Gradle 8.11.1 or run `gradle wrapper --gradle-version 8.11.1` from a shell with Gradle installed.
4. Synchronize the Gradle project and run the `app` module on an Android device supporting `MifareClassic`.
5. To build a debug APK from a machine with Gradle installed, run `gradle :app:assembleDebug`. The resulting APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

Minimum Android API 26, target API 34. App permission usage: NFC and INTERNET only; downloaded scans and backups remain stored in the app's private storage. Network access goes only to GitHub public API / raw file hosts.

## Build an APK using GitHub Actions instead of Android Studio

A GitHub Actions workflow is included at `.github/workflows/build-apk.yml`. If you push the **contents** of this project folder to a GitHub repository, the workflow installs the Android SDK, validates bundled tag dumps, compiles the debug APK, and makes `BambuTagger-debug-apk` available as a downloadable workflow artifact. You can also run it manually from the GitHub **Actions** tab (`Build Android debug APK` → `Run workflow`). No local Android SDK is needed for this method.

The workflow was **not executed** when the ZIP was created. It may reveal compile or device-specific bugs that need fixing before installation. After downloading the artifact ZIP, extract `app-debug.apk` and install it on your Android device (accept sideloading only if you trust the source).

## First test: SUNLU Yellow

1. Launch the app, turn on Android NFC, and select **SUNLU PLA Yellow** in **My filaments**.
2. Optionally test the live repository flow with **Find Yellow in GitHub**, which opens `PLA/PLA Basic/Yellow` to show available signed UID directories. Tap a UID to download that identity.
3. With an **expendable known-working blue CUID keyfob**, tap **Write fob**. Read the confirmation explaining the tag risks.
4. Tap the intended fob **once to identify it**. The app does not write during this first scan.
5. Review its existing UID in the dialog, remove it from the phone, choose **Arm write**, then **tap it again** and keep it steady throughout the write.
6. After the write completes, remove and tap it **a third time** for **Verify**. A successful verification means that UID, data, and keys matched the selected dump; **AMS recognition must still be checked separately**.
7. Only after verify succeeds, try the fob in your AMS 2 Pro. Check the AMS display and then re-scan the fob to confirm it remains readable.

**Do not test with genuine Bambu RFID tags or a fob you cannot afford to lose.** Some CUID tags are rejected or intentionally modified by certain AMS firmware. Interrupted writes can leave sectors configured with mismatched keys or irrevocable access bits. The backup format records readable data and known keys, but **restoration is not guaranteed**.

## Known limitations / follow-up work

- Actual APK build and on-phone NFC testing remain outstanding.
- Third-party print profiles are **not** encoded: the AMS sees the *Bambu* material/color represented by the selected signed dump.
- "SUNLU PLA Transparent" currently maps to Translucent Ice Blue (tinted), not true colorless PLA.
- Some Gen 2 CUID tags may fail with Bambu AMS firmware despite successful writes.
- Previously cloned Bambu tags normally have permanently read-only sector permissions (`87878769`). The app checks all 16 sector trailers before writing and refuses non-factory (`FF078069`) access bytes. Use a new blank fob for a different filament.
- Library browsing requires GitHub access; GitHub anonymous API rate limits apply. Cached folders and downloaded data remain available until cleared.
- Advanced paired-tag finder, custom aliases, file import/export, backups export, and full-text search across all remote colors are planned but not implemented in v0.1.

## Upstream research and licenses

- [`queengooborg/Bambu-Lab-RFID-Library`](https://github.com/queengooborg/Bambu-Lab-RFID-Library), signed RFID dump database (GPL-3.0 license in the repository).
- [`Bambu-Research-Group/RFID-Tag-Guide`](https://github.com/Bambu-Research-Group/RFID-Tag-Guide), published tag format and HKDF key derivation.
- [`ikarus23/MifareClassicTool`](https://github.com/ikarus23/MifareClassicTool), open-source Android NFC behavior reference (GPL-3.0).

The project's code is GPL-3.0; see `LICENSE`. It uses Android's standard NFC API rather than embedding or modifying MIFARE Classic Tool app source. The bundled RFID dumps retain their original provenance.


## Experimental Reusable Tag Mode (opt-in)

Select a filament, then tap **Reusable Mode: OFF** in its card. Confirm the warning to enable the experiment; it resets to OFF on app restart. The default remains an exact, normally one-time clone. Experimental mode changes **only** the sector trailer access-condition bytes from `87878769` to the transport/writable format `FF078069`, preserving the community dump's UID, material data, and sector Key A/B. This is **not an exact Bambu clone**, and the app does not perform cryptographic RSA signature validation. Android verification checks data and permissions, not AMS recognition.

Both modes require factory-writable `FF078069` on all 16 sectors before writing. This also permits a *potential* second write of an experimental tag with unchanged writable permissions, authenticated using its current UID-derived keys. Exact Bambu clones with `87878769` remain locked and rejected at preflight. The tag might become unreadable or be rejected/modified by the AMS even when Android verification succeeds. **First test:** fresh disposable blue Gen 2/CUID fob → SUNLU Yellow experimental write → Android verification → AMS 2 Pro recognition test → Android read and permission check. Only if the AMS accepts the tag *and* permissions remain writable should you attempt another filament write. Never test on genuine Bambu spool tags.
