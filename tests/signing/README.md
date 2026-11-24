# Signing regressions

Requirements: Linux with `/proc` and inotify support, JDK 21 (`JAVA` may override the detected JDK), Python 3, Bash/Make, GNU `findmnt` and `stat`, a writable `/dev/shm` (or `PHASE2_PERMISSION_ROOT` on tmpfs/ext4), `keytool`, and an Android SDK for the Gradle configuration tests. The release smoke additionally requires ext4, `tar`, `base64`, and `apksigner`.

Run the focused suite:

```sh
bash tests/signing/run_tests.sh
```

The suite covers:

- CI and local property writes through real `java.util.Properties.load(InputStream)`;
- ASCII, whitespace, controls, Latin-1, BMP CJK, and non-BMP credentials under `LC_ALL=C` and `LC_ALL=C.UTF-8`;
- missing, empty, malformed-base64, invalid-JKS, alias, password, and private-key inputs;
- canonical ancestor-chain ownership/mode checks, including sticky root-owned `/tmp`-style directories and rejection of unsafe intermediates;
- descriptor-backed named `CREATE_NEW` staging (required for hard-link publication; same-user cleanup races are out of scope);
- atomic no-replace publication, concurrent-file preservation, and second-publish rollback;
- existing-properties owner/mode/symlink/exact-value validation and stale-property guidance;
- verified owner/`0600` output on every available tmpfs/ext4 filesystem and fail-closed behavior on fuseblk;
- local JKS creation with non-BMP credentials and `java.home` keytool resolution;
- behavioral `make signing.properties` runs under `LC_ALL=C` and `LC_ALL=C.UTF-8`, including no needless rewrite, invalid/stale properties rejection, and stale-APK dry-run scheduling;
- missing, partial, empty, complete, whitespace-only credential, whitespace-only `KEYSTORE_FILE`, and non-BMP Gradle signing configuration;
- shared-writer use and JDK 21 ordering in CI.

Run the disposable signed/unsigned release smoke on ext4:

```sh
bash tests/signing/smoke_signed_release.sh /home/samson1357924
```

The smoke copies the current tracked and untracked non-ignored files to a temporary ext4 directory, creates a placeholder KO and disposable non-BMP credentials, builds signed and unsigned releases, verifies the signed APK with `apksigner`, and requires `apksigner` to reject the unsigned APK. Its trap removes the copy, keystore, properties, KO, and APKs. `JAVA`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, and `APKSIGNER` may be overridden. Never use real secrets in the smoke.
