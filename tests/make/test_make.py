#!/usr/bin/env python3
"""Fast, dependency-free contract tests for the top-level Make build.

Standard library only. No Podman and no real signing secrets are involved:
checks are either static assertions over Makefile/script text or `make -n` dry
runs (the two real `make` cases use stub programs and throwaway directories).
Every subprocess gets a whitelisted environment so host state cannot mask a
failure or leak a credential into the assertions.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAKEFILE = ROOT / "Makefile"
LKM_MAKEFILE = ROOT / "lkm" / "Makefile"
IMAGE_SCRIPT = ROOT / "scripts" / "ddk-image.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "build.yml"

KMI = "android13-5.15"
OLD_KMIS = (
    "android12-5.10",
    "android13-5.10",
    "android14-5.15",
    "android14-6.1",
    "android15-6.6",
    "android16-6.12",
    "android17-6.18",
)
PLACEHOLDER = "${{ matrix.kmi }}"

# Only these host variables are inherited; everything else is cleared so a
# caller's KMIS/DDK_IMAGE/JAVA/MAKEFLAGS/credentials cannot change a result.
SAFE_ENV_KEYS = ("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR")
CLEARED_ENV_KEYS = (
    "KMIS", "DDK_IMAGE", "ENGINE", "JAVA",
    "MAKEFLAGS", "MAKEOVERRIDES", "SIGN_PASS", "SIGN_ALIAS",
)
FORBIDDEN_CONTAINER_FLAGS = (
    "--cap-add", "--privileged", "--pid", "--ipc=host", "--user=", "--userns=host",
)
CREDENTIAL_ECHO_TOKENS = ("$$SIGN_PASS", "$$SIGN_ALIAS")


def clean_env(extra: dict[str, str] | None = None) -> dict[str, str]:
    env = {key: os.environ[key] for key in SAFE_ENV_KEYS if key in os.environ}
    for key in CLEARED_ENV_KEYS:
        env.pop(key, None)
    env.update(extra or {})
    return env


def make_code(text: str | None = None) -> str:
    """Makefile text with comment lines removed (comments may name retired code)."""
    source = MAKEFILE.read_text(encoding="utf-8") if text is None else text
    return "\n".join(
        line for line in source.splitlines() if not line.lstrip().startswith("#")
    )


def workflow_image(text: str | None = None) -> str:
    source = WORKFLOW.read_text(encoding="utf-8") if text is None else text
    for line in source.splitlines():
        match = re.match(r"^\s*image:\s*(ghcr\.io/ylarod/ddk-min:.*?)\s*$", line)
        if match:
            return match.group(1)
    raise AssertionError("no ddk-min image reference in the workflow")


def run_make(
    *args: str,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
    dry_run: bool = True,
    makefile: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    command = ["make", "-f", str(makefile or MAKEFILE), "-C", str(cwd or ROOT)]
    if dry_run:
        command.append("-n")
    command.extend(args)
    return subprocess.run(
        command,
        cwd=ROOT,
        env=env if env is not None else clean_env(),
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=60,
        check=False,
    )


def run_make_with_text(text: str, *args: str) -> subprocess.CompletedProcess[str]:
    """Run `make -n` against a mutated Makefile without touching the real one."""
    with tempfile.TemporaryDirectory(prefix="dfroot-make-mutation-") as directory:
        mutated = Path(directory) / "Makefile"
        mutated.write_text(text, encoding="utf-8")
        return run_make(*args, makefile=mutated)


def run_script(
    *args: str,
    script: Path | None = None,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["bash", str(script or IMAGE_SCRIPT), *args],
        cwd=cwd or ROOT,
        env=env if env is not None else clean_env(),
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=30,
        check=False,
    )


def assert_no_forbidden_container_flags(dry_run_output: str) -> None:
    for flag in FORBIDDEN_CONTAINER_FLAGS:
        if flag in dry_run_output:
            raise AssertionError(f"forbidden container flag {flag!r} in dry-run output")


def assert_no_credential_echo(makefile_text: str) -> None:
    for token in CREDENTIAL_ECHO_TOKENS:
        if token in makefile_text:
            raise AssertionError(f"recipe echoes credentials via {token!r}")


class CredentialContractTests(unittest.TestCase):
    def test_command_line_credentials_are_rejected_at_parse_time(self) -> None:
        cases = (
            ("SIGN_PASS=cli-secret",),
            ("SIGN_ALIAS=cli-alias",),
            ("SIGN_PASS=cli-secret", "SIGN_ALIAS=cli-alias"),
        )
        for assignments in cases:
            with self.subTest(assignments=assignments):
                result = run_make(*assignments, "clean")
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("command line", result.stderr)
                self.assertIn("export", result.stderr)
                self.assertIn("SIGN_PASS", result.stderr + result.stdout)
                self.assertNotIn("cli-secret", result.stdout)
                self.assertNotIn("cli-alias", result.stdout)

    def test_credentials_smuggled_via_makeflags_or_makeoverrides_are_rejected(self) -> None:
        cases = (
            {"MAKEFLAGS": "SIGN_PASS=smuggled"},
            {"MAKEFLAGS": "SIGN_ALIAS=smuggled"},
            {"MAKEOVERRIDES": "SIGN_PASS=smuggled"},
            {"MAKEOVERRIDES": "SIGN_ALIAS=smuggled"},
        )
        for extra in cases:
            with self.subTest(env=extra):
                result = run_make("clean", env=clean_env(extra))
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("command line", result.stderr)
                self.assertIn("SIGN_PASS" if "SIGN_PASS" in str(extra) else "SIGN_ALIAS", result.stderr)
                self.assertNotIn("smuggled", result.stdout)

    def test_environment_credentials_still_work_and_are_not_echoed(self) -> None:
        env = clean_env({"SIGN_PASS": "env-secret-not-echoed", "SIGN_ALIAS": "env-alias-not-echoed"})
        result = run_make("out/DFRoot.apk", env=env)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("env-secret-not-echoed", result.stdout + result.stderr)
        self.assertNotIn("env-alias-not-echoed", result.stdout + result.stderr)
        self.assertIn("./gradlew :app:assembleRelease", result.stdout)

    def test_no_credentials_needed_for_clean_and_dry_run(self) -> None:
        for target in ("clean", "all", "out/DFRoot.apk"):
            with self.subTest(target=target):
                result = run_make(target)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_clean_runs_for_real_without_credentials(self) -> None:
        with tempfile.TemporaryDirectory(prefix="dfroot-make-clean-") as directory:
            project = Path(directory)
            ko_dir = project / "app" / "src" / "main" / "jni" / "ko"
            ko_dir.mkdir(parents=True)
            (ko_dir / "dfroot-android13-5.15.ko").write_bytes(b"placeholder\n")
            result = run_make("clean", cwd=project, dry_run=False)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertFalse(ko_dir.exists())

    def test_credentials_are_not_exported_globally(self) -> None:
        makefile = make_code()
        self.assertNotIn("CMDLINE_ORIGIN", makefile)
        for variable in ("SIGN_PASS", "SIGN_ALIAS"):
            self.assertNotIn(f"\nexport {variable}\n", makefile)
            export_line = (
                "keystore.jks signing.properties verify-signing-permissions: "
                f"export {variable} := $({variable})"
            )
            self.assertIn(export_line, makefile)

    def test_no_recipe_echoes_credential_values(self) -> None:
        assert_no_credential_echo(make_code())

    def test_mutation_adding_a_credential_echo_is_detected(self) -> None:
        mutated = MAKEFILE.read_text(encoding="utf-8").replace(
            "clean:\n", 'clean:\n\techo "$$SIGN_PASS"\n', 1
        )
        self.assertNotEqual(mutated, MAKEFILE.read_text(encoding="utf-8"))
        with self.assertRaisesRegex(AssertionError, r"\$\$SIGN_PASS"):
            assert_no_credential_echo(make_code(mutated))
        result = run_make_with_text(mutated, "clean")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('echo "$SIGN_PASS"', result.stdout)


class DdkImageScriptTests(unittest.TestCase):
    def test_output_matches_ci_image_exactly(self) -> None:
        result = run_script(KMI)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        expected = workflow_image().replace(PLACEHOLDER, KMI)
        self.assertEqual(result.stdout, expected + "\n")
        self.assertIn("@sha256:", result.stdout)
        self.assertRegex(result.stdout, r"@sha256:[0-9a-f]{64}\n\Z")
        self.assertIn(KMI, result.stdout)
        self.assertNotIn(PLACEHOLDER, result.stdout)

    def test_make_uses_the_script_as_the_image_source(self) -> None:
        makefile = make_code()
        self.assertIn("scripts/ddk-image.sh", makefile)
        self.assertNotIn("DDK_RELEASE", makefile)
        self.assertNotIn("awk", makefile)
        result = run_make("out/DFRoot.apk")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(workflow_image().replace(PLACEHOLDER, KMI), result.stdout)

    def test_script_rejects_bad_invocations_with_a_validation_error(self) -> None:
        cases = (
            ((), "usage:"),
            ((KMI, "extra"), "usage:"),
            (("android12-5.10 android14-6.1",), "invalid KMI"),
            ((PLACEHOLDER,), "invalid KMI"),
            (("x;echo PWNED_PHASE4",), "invalid KMI"),
        )
        for args, expected_message in cases:
            with self.subTest(args=args):
                result = run_script(*args)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(result.stdout, "")
                self.assertIn(expected_message, result.stderr)

    def test_script_fails_closed_when_workflow_is_unpinned(self) -> None:
        digest = re.search(r"@sha256:([0-9a-f]{64})", workflow_image()).group(1)
        mutated = WORKFLOW.read_text(encoding="utf-8").replace("@sha256:" + digest, "")
        self.assertNotEqual(mutated, WORKFLOW.read_text(encoding="utf-8"))
        result = self.run_with_workflow(mutated)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("digest-pinned", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_script_fails_closed_without_the_matrix_placeholder(self) -> None:
        mutated = WORKFLOW.read_text(encoding="utf-8").replace(PLACEHOLDER, KMI)
        self.assertNotEqual(mutated, WORKFLOW.read_text(encoding="utf-8"))
        result = self.run_with_workflow(mutated)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(PLACEHOLDER, result.stderr)
        self.assertEqual(result.stdout, "")

    @staticmethod
    def run_with_workflow(text: str) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="dfroot-ddk-workflow-") as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            (root / ".github" / "workflows").mkdir(parents=True)
            shutil.copy2(IMAGE_SCRIPT, root / "scripts" / "ddk-image.sh")
            (root / ".github" / "workflows" / "build.yml").write_text(text, encoding="utf-8")
            return run_script(KMI, script=root / "scripts" / "ddk-image.sh", cwd=root)

    def test_ddk_image_cannot_be_overridden_by_the_caller(self) -> None:
        injected = "evil/unpinned:latest"
        cli = run_make("clean", f"DDK_IMAGE={injected}")
        self.assertNotEqual(cli.returncode, 0, cli.stdout + cli.stderr)
        self.assertIn("DDK_IMAGE", cli.stderr)
        self.assertNotIn(injected, cli.stdout + cli.stderr)
        env = run_make("clean", env=clean_env({"DDK_IMAGE": injected}))
        self.assertNotEqual(env.returncode, 0, env.stdout + env.stderr)
        self.assertIn("DDK_IMAGE", env.stderr)
        self.assertNotIn(injected, env.stdout + env.stderr)


class ContainerCommandTests(unittest.TestCase):
    def test_container_is_least_privilege_and_mounts_only_lkm_and_ko(self) -> None:
        result = run_make("out/DFRoot.apk")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        output = result.stdout

        self.assertIn("podman run", output)
        assert_no_forbidden_container_flags(output)
        self.assertIn("--network=none", output)
        self.assertIn("--cap-drop=ALL", output)
        self.assertIn("--security-opt=no-new-privileges", output)

        self.assertIn(f"-v {ROOT / 'lkm'}:/proj/lkm", output)
        self.assertIn(f"-v {ROOT / 'app/src/main/jni/ko'}:/proj/ko", output)
        self.assertNotIn(f"-v {ROOT}:/proj", output)
        self.assertNotIn("-v .:/proj", output)
        self.assertEqual(output.count("-v "), 2)
        self.assertIn(f"-e KMI={KMI}", output)
        self.assertIn("mkdir -p app/src/main/jni/ko", output)

        # The DDK phase must not receive or reference signing material, and must
        # be scheduled before any signing recipe in the dry run.
        container_partition = output.split(': "${SIGN_PASS:?', 1)[0]
        for forbidden in ("signing.properties", "keystore.jks", "SIGN_PASS", "SIGN_ALIAS"):
            self.assertNotIn(forbidden, container_partition)
        self.assertLess(output.index("podman run"), output.index("WriteSigningProperties.java"))

    def test_signing_files_are_not_inputs_to_ddk(self) -> None:
        makefile = make_code()
        lkm_rule = next(line for line in makefile.splitlines() if line.startswith("$(LKM_KOS):"))
        self.assertIn(": $(LKM_SRCS)", lkm_rule)
        for forbidden in ("signing.properties", "keystore.jks", "SIGN_PASS", "SIGN_ALIAS"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, lkm_rule)

    def test_single_static_pattern_rule_builds_the_stem_once(self) -> None:
        makefile = make_code()
        self.assertIn("$(LKM_KOS): $(LKM_KO_DIR)/dfroot-%.ko: $(LKM_SRCS)", makefile)
        self.assertNotIn("for kmi in", makefile)
        result = run_make("out/DFRoot.apk")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.count("podman run"), 1)

    def test_mutation_adding_cap_add_is_detected(self) -> None:
        original = MAKEFILE.read_text(encoding="utf-8")
        mutated = original.replace(
            "    --cap-drop=ALL \\\n",
            "    --cap-drop=ALL \\\n    --cap-add=SYS_ADMIN \\\n",
            1,
        )
        self.assertNotEqual(mutated, original)
        with self.assertRaisesRegex(AssertionError, "--cap-add"):
            assert_no_forbidden_container_flags(
                "podman run --cap-drop=ALL --cap-add=SYS_ADMIN\n"
            )
        result = run_make_with_text(mutated, "out/DFRoot.apk")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("--cap-add=SYS_ADMIN", result.stdout)
        with self.assertRaisesRegex(AssertionError, "--cap-add"):
            assert_no_forbidden_container_flags(result.stdout)


class SingleKmiTests(unittest.TestCase):
    def test_raw_value_is_validated_before_any_use(self) -> None:
        makefile = make_code()
        self.assertIn("RAW_KMIS := $(value KMIS)", makefile)
        self.assertLess(makefile.index("RAW_KMIS := $(value KMIS)"), makefile.index("DDK_IMAGE ?="))
        self.assertIn("KMIS := android13-5.15", makefile)
        for old in OLD_KMIS:
            self.assertNotIn(old, makefile)

    def test_default_is_the_single_shipped_kmi(self) -> None:
        result = run_make("out/DFRoot.apk")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"-e KMI={KMI}", result.stdout)
        for old in OLD_KMIS:
            self.assertNotIn(old, result.stdout)

    def test_raw_shell_injection_fails_and_is_never_printed(self) -> None:
        result = run_make("KMIS=x&&echo PWNED_PHASE4", "out/DFRoot.apk")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Invalid KMIS", result.stderr)
        self.assertNotIn("PWNED_PHASE4", result.stdout + result.stderr)

    def test_another_real_kmi_is_rejected(self) -> None:
        for kmi in ("android14-6.1", "android12-5.10"):
            with self.subTest(kmi=kmi):
                result = run_make(f"KMIS={kmi}", "out/DFRoot.apk")
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("Invalid KMIS", result.stderr)
                self.assertIn("android13-5.15", result.stderr)

    def test_multi_kmi_invocation_fails_with_a_clear_error(self) -> None:
        result = run_make("KMIS=android12-5.10 android14-6.1", "out/DFRoot.apk")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Invalid KMIS", result.stderr)

    def test_empty_kmi_is_the_explicit_no_ddk_escape_hatch(self) -> None:
        result = run_make("KMIS=", "out/DFRoot.apk")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("podman run", result.stdout)
        self.assertIn("./gradlew :app:assembleRelease", result.stdout)


class ApkTargetTests(unittest.TestCase):
    def test_apk_is_phony_and_always_invokes_gradle(self) -> None:
        makefile = make_code()
        self.assertIn(".PHONY:", makefile)
        self.assertIn("$(APK)", makefile.split(".PHONY:", 1)[1].splitlines()[0])

        with tempfile.TemporaryDirectory(prefix="dfroot-make-apk-") as directory:
            project = Path(directory)
            apk = project / "out" / "DFRoot.apk"
            apk.parent.mkdir()
            apk.write_bytes(b"stale apk placeholder\n")
            for target in ("out/DFRoot.apk", "all"):
                with self.subTest(target=target):
                    result = run_make("KMIS=", target, cwd=project)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn("./gradlew :app:assembleRelease", result.stdout)

    def test_no_handwritten_source_prerequisites(self) -> None:
        makefile = make_code()
        self.assertNotIn("APK_SRCS", makefile)
        for token in ("*.java", "*.kt", "app/src/main/res", "app/build.gradle.kts", "AndroidManifest.xml"):
            self.assertNotIn(token, makefile)
        apk_rule = next(line for line in makefile.splitlines() if line.startswith("$(APK):"))
        self.assertIn("$(LKM_KOS)", apk_rule)
        self.assertLess(apk_rule.index("$(LKM_KOS)"), apk_rule.index("signing.properties"))
        self.assertIn(".NOTPARALLEL: $(APK)", makefile)

    def test_phase_two_signing_order_is_preserved(self) -> None:
        makefile = make_code()
        self.assertIn("WriteSigningProperties.java verify-local", makefile)
        self.assertIn("signing.properties: | verify-signing-permissions", makefile)
        self.assertIn("verify-signing-permissions: | keystore.jks", makefile)
        self.assertNotIn("stat -c", makefile)


class StubJavaSigningRecipeTests(unittest.TestCase):
    """Real (non-dry-run) recipe checks with a stub JAVA and fake credentials."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory(prefix="dfroot-make-stub-java-")
        self.addCleanup(self.directory.cleanup)
        self.project = Path(self.directory.name)
        self.capture = self.project / "captured.env"
        self.stub = self.project / "fake-java"
        self.stub.write_text(
            "#!/usr/bin/env bash\n"
            "set -u\n"
            "{\n"
            '  printf "SIGN_PASS=%s\\n" "${SIGN_PASS-<unset>}"\n'
            '  printf "SIGN_ALIAS=%s\\n" "${SIGN_ALIAS-<unset>}"\n'
            '  printf "ARGS=%s\\n" "$*"\n'
            '} > "$CAPTURE_FILE"\n',
            encoding="utf-8",
        )
        self.stub.chmod(0o755)

    def test_credentials_reach_the_java_recipe(self) -> None:
        env = clean_env({
            "JAVA": str(self.stub),
            "CAPTURE_FILE": str(self.capture),
            "SIGN_PASS": "stub-pass-123",
            "SIGN_ALIAS": "stub-alias-456",
        })
        result = run_make("KMIS=", "keystore.jks", cwd=self.project, env=env, dry_run=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        captured = self.capture.read_text(encoding="utf-8")
        self.assertIn("SIGN_PASS=stub-pass-123", captured)
        self.assertIn("SIGN_ALIAS=stub-alias-456", captured)
        self.assertIn("create-local", captured)

    def test_unset_credentials_fail_closed_without_invoking_java(self) -> None:
        env = clean_env({"JAVA": str(self.stub), "CAPTURE_FILE": str(self.capture)})
        result = run_make("KMIS=", "keystore.jks", cwd=self.project, env=env, dry_run=False)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("SIGN_PASS is not set", result.stderr)
        self.assertFalse(self.capture.exists())


class LkmMakefileTests(unittest.TestCase):
    def test_objcopy_defaults_to_llvm_objcopy(self) -> None:
        makefile = LKM_MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("OBJCOPY ?= llvm-objcopy", makefile)
        self.assertIn("$(OBJCOPY) --strip-unneeded", makefile)


if __name__ == "__main__":
    unittest.main()
