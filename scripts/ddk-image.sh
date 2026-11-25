#!/usr/bin/env bash
# Print the exact, digest-pinned DDK image that CI uses for one KMI.
#
# The local podman build must not invent its own floating tag: CI's
# .github/workflows/build.yml is the single source of truth for the image
# reference (including the @sha256 pin).  This helper reads that reference and
# replaces the workflow's `${{ matrix.kmi }}` placeholder with the KMI passed
# on the command line, then prints the result verbatim.
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
workflow="$root/.github/workflows/build.yml"
placeholder='${{ matrix.kmi }}'

if [[ $# -ne 1 || -z ${1:-} ]]; then
    printf 'usage: %s <kmi>\n' "${0##*/}" >&2
    exit 2
fi
kmi=$1

# A KMI is a single token (e.g. android13-5.15). Reject whitespace, glob
# characters and expression remnants before they reach an image reference.
if [[ ! $kmi =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    printf 'ddk-image.sh: invalid KMI %q\n' "$kmi" >&2
    exit 2
fi

if [[ ! -f $workflow ]]; then
    printf 'ddk-image.sh: workflow not found: %s\n' "$workflow" >&2
    exit 1
fi

image=$(awk '
    /image: ghcr\.io\/ylarod\/ddk-min:/ {
        sub(/^[[:space:]]*image:[[:space:]]*/, "")
        sub(/[[:space:]]+$/, "")
        print
        exit
    }
' "$workflow")
if [[ -z $image ]]; then
    printf 'ddk-image.sh: no ddk-min image found in %s\n' "$workflow" >&2
    exit 1
fi
if [[ $image != *"$placeholder"* ]]; then
    printf 'ddk-image.sh: expected %s in image reference: %s\n' "$placeholder" "$image" >&2
    exit 1
fi
if [[ ! $image =~ @sha256:[0-9a-f]{64}$ ]]; then
    printf 'ddk-image.sh: ddk-min image is not digest-pinned: %s\n' "$image" >&2
    exit 1
fi

printf '%s\n' "${image//"$placeholder"/$kmi}"
