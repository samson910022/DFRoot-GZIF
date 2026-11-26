#!/usr/bin/env bash
# Builds signed and unsigned releases in a disposable ext4 copy. No real secrets.
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SMOKE_ROOT=${1:-$HOME}

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

# No placeholder KO: this smoke exercises signing only and explicitly opts out
# of the native build. A real/distributable APK must still pass the KO byte gate.
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

./gradlew :app:assembleRelease --no-daemon --console=plain -Pdfroot.signingSmokeSkipNative=true

# Identity isolation: this smoke uses a distinct applicationId/versionName and
# must emit exactly one APK with the isolated SigningSmoke name. A production
# DFRoot_*.apk must NEVER appear, even if some path tried to reuse this build.
SIGNED_APK=app/build/outputs/apk/release/SigningSmoke-4.8-gzif-signingsmoke.apk
[[ ${SIGNED_APK##*/} == 'SigningSmoke-4.8-gzif-signingsmoke.apk' ]]
[[ -f $SIGNED_APK ]]
if compgen -G 'app/build/outputs/apk/*/DFRoot_*.apk' >/dev/null; then
    printf 'ERROR: signing smoke produced a production-named DFRoot_*.apk.\n' >&2
    exit 1
fi
shopt -s nullglob
release_apks=(app/build/outputs/apk/release/*.apk)
shopt -u nullglob
if [[ ${#release_apks[@]} -ne 1 || ${release_apks[0]} != "$SIGNED_APK" ]]; then
    printf 'ERROR: expected exactly the isolated signing-smoke APK, found: %s\n' \
        "${release_apks[*]:-<none>}" >&2
    exit 1
fi
"$APKSIGNER" verify --verbose --print-certs "$SIGNED_APK"

# With no real signing material, PR #3 disables the release variant outright:
# a fresh configuration must emit NO unsigned or dummy-signed release APK.
rm -f signing.properties keystore.jks "$SIGNED_APK"
if ./gradlew :app:clean :app:assembleRelease --no-daemon --console=plain \
    -Pdfroot.signingSmokeSkipNative=true >unsigned-release.log 2>&1; then
    printf 'ERROR: assembleRelease succeeded without real signing material.\n' >&2
    cat unsigned-release.log >&2
    exit 1
fi
if compgen -G 'app/build/outputs/apk/*/*.apk' >/dev/null; then
    printf 'ERROR: an unsigned/dummy APK was produced without real signing material.\n' >&2
    exit 1
fi
printf 'Unsigned/dummy release rejected as expected: no release APK was produced.\n'
