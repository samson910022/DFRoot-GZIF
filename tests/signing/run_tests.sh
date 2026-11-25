#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)

if [[ -z ${JAVA:-} ]]; then
    if [[ -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java ]]; then
        JAVA=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
    else
        JAVA=$(command -v java)
    fi
fi
export JAVA

version=$("$JAVA" -version 2>&1)
if [[ $version != *'version "21.'* ]]; then
    printf 'Java 21 is required for signing tests; selected: %s\n' "$JAVA" >&2
    exit 1
fi

cd "$ROOT"
exec python3 -B -m unittest discover -s tests/signing -p 'test_*.py' -v
