#!/usr/bin/env bash
# Builds signed and unsigned releases in a disposable ext4 copy. No real secrets.
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SMOKE_ROOT=${1:-/home/samson1357924}

if [[ -z ${JAVA:-} ]]; then
    if [[ -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java ]]; then
        JAVA=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
    else
        JAVA=$(command -v java)
    fi
fi
version=$("$JAVA" -version 2>&1)
if [[ $version != *'version "21.'* ]]; then
    printf 'Java 21 is required for the release smoke; selected: %s\n' "$JAVA" >&2
    exit 1
fi
export JAVA
export JAVA_HOME=${JAVA_HOME:-$(dirname -- "$(dirname -- "$JAVA")")}

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
if [[ -n ${APKSIGNER:-} ]]; then
    :
elif command -v apksigner >/dev/null 2>&1; then
    APKSIGNER=$(command -v apksigner)
else
    APKSIGNER=$(find "$SDK/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -print | sort -V | tail -n 1)
fi
if [[ -z $APKSIGNER || ! -x $APKSIGNER ]]; then
    printf 'apksigner was not found; set APKSIGNER to its path.\n' >&2
    exit 1
fi

filesystem=$(findmnt -n -o FSTYPE -T "$SMOKE_ROOT")
if [[ $filesystem != ext4 ]]; then
    printf 'The release smoke requires ext4 (%s); %s is %s.\n' "$SMOKE_ROOT" "$SMOKE_ROOT" "$filesystem" >&2
    exit 1
fi

SMOKE=$(mktemp -d "$SMOKE_ROOT/dfroot-pr1-phase2-smoke-XXXXXX")
cleanup() {
    status=$?
    rm -rf -- "$SMOKE"
    exit "$status"
}
trap cleanup EXIT

# Include tracked modifications and new regression files, but no ignored build output.
git -C "$ROOT" ls-files -c -o --exclude-standard -z | tar -C "$ROOT" --null -T - -cf - | tar -C "$SMOKE" -xf -
cd "$SMOKE"

mkdir -p app/src/main/jni/ko
printf 'disposable placeholder KO for signing smoke\n' > app/src/main/jni/ko/dfroot-android13-5.15.ko
printf 'sdk.dir=%s\n' "$SDK" > local.properties

# Exercise Make's atomic local-keystore creation. Credentials stay in the environment.
export SIGN_PASS='  smoke-password-😀-🔐  '
export SIGN_ALIAS='  smoke-alias-😀-𝄞  '
make keystore.jks
[[ $(stat -c '%a' keystore.jks) == 600 ]]

# Exercise the CI writer against the disposable JKS, then rebuild identical properties
# through local Make mode. Both production paths must accept the non-BMP credentials.
export KEYSTORE
KEYSTORE=$(base64 -w0 keystore.jks)
export KEYSTORE_PASSWORD=$SIGN_PASS
export KEY_ALIAS=$SIGN_ALIAS
export KEY_PASSWORD=$SIGN_PASS
rm -f keystore.jks signing.properties
"$JAVA" scripts/WriteSigningProperties.java ci .
make signing.properties
[[ $(stat -c '%a' signing.properties) == 600 ]]
[[ $(stat -c '%a' keystore.jks) == 600 ]]

./gradlew :app:assembleRelease --no-daemon --console=plain
SIGNED_APK=app/build/outputs/apk/release/DFRoot_4.8-gzif.apk
[[ -f $SIGNED_APK ]]
"$APKSIGNER" verify --verbose --print-certs "$SIGNED_APK"

# A fresh configuration without signing.properties must produce an unsigned release.
rm -f signing.properties keystore.jks
./gradlew :app:clean :app:assembleRelease --no-daemon --console=plain
UNSIGNED_APK=app/build/outputs/apk/release/DFRoot_4.8-gzif.apk
[[ -f $UNSIGNED_APK ]]
if "$APKSIGNER" verify --verbose "$UNSIGNED_APK" >unsigned-apksigner.log 2>&1; then
    printf 'ERROR: apksigner accepted the unsigned release.\n' >&2
    exit 1
fi
if ! grep -Eqi 'No signatures found|Number of signers: 0|ERROR: Missing META-INF/MANIFEST.MF' unsigned-apksigner.log; then
    cat unsigned-apksigner.log >&2
    printf 'ERROR: unsigned APK lacked an unsigned-specific diagnostic.\n' >&2
    exit 1
fi
printf 'Unsigned APK rejected as expected: '
grep -Ei 'No signatures found|Number of signers: 0|ERROR: Missing META-INF/MANIFEST.MF' unsigned-apksigner.log | head -n 1
