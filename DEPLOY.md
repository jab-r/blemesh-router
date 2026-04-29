# Deploying blemesh-router

Two distribution paths are supported. Side-load is the default for router-fleet
deployments; Play Store / Play Internal Testing is supported by the same build
config and only differs in the upload step.

The build produces three variants:

| Variant         | Signed with    | Minified | Debuggable | Use                                    |
| --------------- | -------------- | -------- | ---------- | -------------------------------------- |
| `debug`         | debug keystore | no       | yes        | Local development / Android Studio run |
| `release`       | upload key     | yes      | no         | Production side-load or Play Production |
| `internal`      | upload key     | no       | yes        | QA / Play Internal Testing              |

`release` and `internal` are the only variants signed with your upload key.
The build will refuse to run any task whose name contains "release" or
"internal" unless `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` /
`RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` are configured.

---

## One-time: generate an upload keystore

Pick any path you'll keep safe. **Losing this file means you can never update
installed APKs again** — generate it once, back it up.

```bash
keytool -genkey -v \
  -keystore ~/.keystores/blemesh-router-upload.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias upload
```

Then add to `~/.gradle/gradle.properties` (NOT to the project tree):

```properties
RELEASE_STORE_FILE=/Users/you/.keystores/blemesh-router-upload.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=upload
RELEASE_KEY_PASSWORD=...
```

(Or set the same names as environment variables — the build reads either.)

---

## Side-load distribution (recommended for router fleets)

Side-loading is the right choice when:

- The audience is a fleet you control (router phones at a venue, internal QA).
- You want fast iteration with no review cycle.
- You don't need or want a Play account.

### Build

```bash
./gradlew :app:assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

For a debuggable QA variant signed with the same key:

```bash
./gradlew :app:assembleInternal
# Output: app/build/outputs/apk/internal/app-internal.apk
```

### Install on a device

USB cable + ADB (most reliable):

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` replaces an existing install. Updates work as long as the new APK is
signed by the same upload key — different key = "package signatures don't
match" and you must uninstall first.

Wireless ADB (Android 11+):

```bash
adb pair <phone-ip>:<pair-port>     # one-time, pair-screen on phone
adb connect <phone-ip>:<connect-port>
adb install -r app-release.apk
```

Manual install (no laptop):

1. Host the APK somewhere reachable from the phone (laptop's `python3 -m http.server`, GitHub Releases, Drive link, etc.).
2. On the phone: download the APK from a browser.
3. First time only: settings prompt to allow installs from that browser/source.
4. Tap the downloaded APK to install. To update, replace the APK at the same URL and re-tap.

### Bulk-install across a fleet

For 20-30 devices, a shell loop with multiple ADB connections works:

```bash
for dev in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  adb -s "$dev" install -r app-release.apk
done
```

Use a powered USB hub or wireless ADB. For larger / unattended fleets,
look at Headwind MDM, Tinfoil, or Fastlane Supply.

### Pre-install checklist per device

- Toggle **Install unknown apps** for the source you'll use (file manager,
  browser, ADB).
- Optionally disable **Battery optimization** for the app (the in-app
  Settings has a button for this; or do it once via Settings → Battery).
- Enable **Bluetooth** and **Wi-Fi** at the OS level.
- Optionally toggle **Stay awake while charging** (Developer Options) for
  routers that will run plugged in.

### Save the mapping file

R8 obfuscates the release build. Stack traces from the field will be
unreadable without the mapping file.

```bash
./gradlew :app:assembleRelease
cp app/build/outputs/mapping/release/mapping.txt mappings/v$(grep versionCode app/build.gradle.kts | head -1).txt
```

Stash these alongside the APK so you can de-obfuscate later.

---

## Play Store path (optional)

Useful when:

- You want auto-updates over the air without re-flashing devices.
- A managed fleet (MDM) subscribes to Play Internal Testing.
- You're broadening the audience beyond a controlled fleet.

The app is configured for Play App Signing, where Play holds the production
signing key and you only handle the upload key.

### Initial setup (one-time)

1. Create a Play Console account ($25 one-time) at <https://play.google.com/console>.
2. Create the app. Set the package name (must match `applicationId` in
   `app/build.gradle.kts`, currently `com.blemesh.router`).
3. Enroll in **Play App Signing** when prompted. Upload the keystore you
   generated above as the upload key.
4. Fill out the privacy policy, data safety form, and content rating
   questionnaires. blemesh-router collects no user data and stores only a
   local PeerID + Wi-Fi credentials on the device, so the data-safety form
   is short.

### Build and upload an AAB (not APK) for Play

Play prefers Android App Bundles:

```bash
./gradlew :app:bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

Upload via:

- Play Console UI: drag the AAB into a track (Internal Testing / Production).
- Or `fastlane supply` if you set up Fastlane (see loxation-android for an example pattern).

### Play Internal Testing

For staged rollout to a known set of testers (e.g. festival ops crew):

```bash
./gradlew :app:bundleInternal
# This is non-minified, debuggable, signed with the upload key.
# Upload to the Internal Testing track in Play Console.
# Add testers by email; they install via the Play Store with a tester link.
```

### Versioning

Bump `versionCode` (an integer) and `versionName` (a string) in
`app/build.gradle.kts` for each upload. Play rejects bundles with a
`versionCode` <= the highest-uploaded value.

---

## Common pitfalls

- **"Package signatures don't match"** on update: APK was signed by a
  different key. Either restore the original keystore or uninstall first.
- **"INSTALL_PARSE_FAILED_NO_CERTIFICATES"**: APK isn't signed. Make sure
  you ran `assembleRelease`/`assembleInternal`, not `assembleDebug`.
- **"INSTALL_FAILED_UPDATE_INCOMPATIBLE"**: same as the signature mismatch.
- **App installs but won't start**: check logcat for missing class errors.
  R8 may have stripped a class accessed reflectively. Add a `-keep` rule
  in `app/proguard-rules.pro` and rebuild.
- **Crashes only in release**: that's the minify difference. Try
  `assembleInternal` (release-signed but non-minified) to isolate whether
  it's R8 or something else.
