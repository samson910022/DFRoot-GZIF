#!/usr/bin/env python3
"""Fast, dependency-free contract tests for the top-level Make build.

Standard library only. No Podman and no real signing secrets are involved:
checks are either static assertions over Makefile/script text or `make -n` dry
runs (the two real `make` cases use stub programs and throwaway directories).
Every subprocess gets a whitelisted environment so host state cannot mask a
failure or leak a credential into the assertions.
"""

from __future__ import annotations

import base64
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
# Exact digest of the single digest-pinned DDK image that CI declares and that
# scripts/ddk-image.sh resolves for the local podman build.
DDK_IMAGE_DIGEST = "9e68cd9fcab748584755d31ba88ef134efc938dbde17f258c2652315f6cd81cd"

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

# Values that are mangled by re-expanding an environment-origin Make variable
# (`$(SIGN_PASS)`) but survive `$(value SIGN_PASS)` byte-for-byte.  No embedded
# newlines: the stub's capture format is line-oriented.
RAW_CREDENTIAL_CASES = (
    ("literal_dollars", "abc$xyz$$tail", "alias$xy"),
    ("trailing_dollars", "password$$", "alias$$"),
    ("backslashes", r"back\slash\\tail", r"alias\back"),
    ("leading_trailing_whitespace", "  padded password  ", "  padded alias  "),
    ("tabs", "\tTabbed\tpassword\t", "\tTabbed\talias\t"),
    ("non_bmp_unicode", "p😀ss汉🔐字", "ali😀as漢"),
    ("combined", "  c😀mb\\ined$$ \t 汉 ", "  a\\lias$😀\t字  "),
)


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


def assert_exact_ddk_image_digest(image: str) -> None:
    """A format-valid but different digest is still a supply-chain change."""
    match = re.search(r"@sha256:([0-9a-f]{64})$", image)
    if not match or match.group(1) != DDK_IMAGE_DIGEST:
        raise AssertionError(
            f"DDK image digest must be exactly {DDK_IMAGE_DIGEST}, got {image!r}"
        )


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

    def test_clean_recipe_environment_omits_credentials(self) -> None:
        """Env-origin credentials must not leak into the (non-signing) clean recipe.

        An `rm` shim first on PATH records SIGN_PASS/SIGN_ALIAS on every rm call,
        then execs the real rm so we also prove the genuine recipe ran.
        """
        with tempfile.TemporaryDirectory(prefix="dfroot-make-clean-env-") as directory:
            project = Path(directory)
            ko_dir = project / "app" / "src" / "main" / "jni" / "ko"
            ko_dir.mkdir(parents=True)
            (ko_dir / "dfroot-android13-5.15.ko").write_bytes(b"placeholder\n")
            capture = project / "rm.env"
            shim_dir = project / "bin"
            shim_dir.mkdir()
            real_rm = shutil.which("rm")
            self.assertIsNotNone(real_rm)
            shim = shim_dir / "rm"
            shim.write_text(
                "#!/usr/bin/env bash\n"
                "set -u\n"
                "{\n"
                '  printf "SIGN_PASS=%s\\n" "${SIGN_PASS-<unset>}"\n'
                '  printf "SIGN_ALIAS=%s\\n" "${SIGN_ALIAS-<unset>}"\n'
                '} >> "$RM_CAPTURE"\n'
                f'exec "{real_rm}" "$@"\n',
                encoding="utf-8",
            )
            shim.chmod(0o755)

            env = clean_env({
                "PATH": f"{shim_dir}{os.pathsep}{clean_env().get('PATH', '')}",
                "RM_CAPTURE": str(capture),
                "SIGN_PASS": "SENTINEL-P",
                "SIGN_ALIAS": "SENTINEL-A",
            })
            result = run_make("clean", cwd=project, env=env, dry_run=False)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertTrue(capture.exists(), "rm shim was not invoked by clean")
            captured = capture.read_text(encoding="utf-8")
            self.assertIn("SIGN_PASS=<unset>", captured)
            self.assertIn("SIGN_ALIAS=<unset>", captured)
            self.assertNotIn("SENTINEL-P", captured)
            self.assertNotIn("SENTINEL-A", captured)
            # The real rm ran, so the recipe really executed.
            self.assertFalse(ko_dir.exists(), "real rm did not execute")

    def test_ddk_wrapper_environment_omits_credentials(self) -> None:
        """Env-origin credentials must not leak into the LKM/DDK wrapper recipe."""
        with tempfile.TemporaryDirectory(prefix="dfroot-make-ddk-env-") as directory:
            project = Path(directory)
            (project / "scripts").mkdir()
            (project / ".github" / "workflows").mkdir(parents=True)
            shutil.copy2(IMAGE_SCRIPT, project / "scripts" / "ddk-image.sh")
            shutil.copy2(WORKFLOW, project / ".github" / "workflows" / "build.yml")
            (project / "lkm").mkdir()
            (project / "lkm" / "dfroot.c").write_text(
                "// stub LKM source\n", encoding="utf-8"
            )
            (project / "lkm" / "Makefile").write_text("all:\n\t@true\n", encoding="utf-8")
            (project / "app" / "src" / "main" / "jni" / "ko").mkdir(parents=True)

            capture = project / "engine.env"
            engine = project / "fake-engine"
            engine.write_text(
                "#!/usr/bin/env bash\n"
                "set -u\n"
                "{\n"
                '  printf "ARGS=%s\\n" "$*"\n'
                '  printf "SIGN_PASS=%s\\n" "${SIGN_PASS-<unset>}"\n'
                '  printf "SIGN_ALIAS=%s\\n" "${SIGN_ALIAS-<unset>}"\n'
                '} > "$ENGINE_CAPTURE"\n',
                encoding="utf-8",
            )
            engine.chmod(0o755)

            env = clean_env({
                "ENGINE_CAPTURE": str(capture),
                "SIGN_PASS": "SENTINEL-P",
                "SIGN_ALIAS": "SENTINEL-A",
            })
            result = run_make(
                f"ENGINE={engine}",
                "app/src/main/jni/ko/dfroot-android13-5.15.ko",
                cwd=project, env=env, dry_run=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertTrue(capture.exists(), "ENGINE stub was not invoked")
            captured = capture.read_text(encoding="utf-8")
            self.assertIn("ARGS=run --rm", captured)
            self.assertIn("SIGN_PASS=<unset>", captured)
            self.assertIn("SIGN_ALIAS=<unset>", captured)
            self.assertNotIn("SENTINEL-P", captured)
            self.assertNotIn("SENTINEL-A", captured)

        # The APK dry run must never pass credentials into the container via -e.
        dry = run_make("out/DFRoot.apk")
        self.assertEqual(dry.returncode, 0, dry.stdout + dry.stderr)
        self.assertNotIn("-e SIGN_PASS", dry.stdout)
        self.assertNotIn("-e SIGN_ALIAS", dry.stdout)

    def test_unexport_is_global_and_no_global_export_exists(self) -> None:
        makefile = make_code()
        # The old textual-only check was replaced by behavioral presence/absence
        # tests below; this guard pins the structural shape they rely on.
        self.assertNotIn("CMDLINE_ORIGIN", makefile)
        self.assertIn("unexport SIGN_PASS SIGN_ALIAS", makefile)
        self.assertLess(
            makefile.index("unexport SIGN_PASS SIGN_ALIAS"),
            makefile.index(
                "keystore.jks signing.properties verify-signing-permissions: "
                "export SIGN_PASS"
            ),
        )
        for variable in ("SIGN_PASS", "SIGN_ALIAS"):
            self.assertNotIn(f"\nexport {variable}\n", makefile)
            export_line = (
                "keystore.jks signing.properties verify-signing-permissions: "
                f"export {variable} := $(value {variable})"
            )
            self.assertIn(export_line, makefile)
        # No overrideable RAW_* helper was introduced (it could be bypassed with
        # `make RAW_PASS=...`); raw values come from $(value ...) only.
        self.assertNotIn("export RAW_", makefile)

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
        assert_exact_ddk_image_digest(expected)
        assert_exact_ddk_image_digest(result.stdout.strip())
        self.assertEqual(result.stdout.strip().split("@sha256:", 1)[1], DDK_IMAGE_DIGEST)
        self.assertIn(KMI, result.stdout)
        self.assertNotIn(PLACEHOLDER, result.stdout)

    def test_different_valid_ddk_digest_is_rejected(self) -> None:
        # 64 `f` characters is a valid digest shape; only the exact pin may pass.
        original = WORKFLOW.read_text(encoding="utf-8")
        mutated = original.replace(
            "@sha256:" + DDK_IMAGE_DIGEST, "@sha256:" + "f" * 64, 1
        )
        self.assertNotEqual(mutated, original)
        with self.assertRaisesRegex(AssertionError, "DDK image digest must be exactly"):
            assert_exact_ddk_image_digest(workflow_image(mutated))

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
        self.byte_capture = Path(str(self.capture) + ".bytes")
        self.stub = self.project / "fake-java"
        self.stub.write_text(
            "#!/usr/bin/env bash\n"
            "set -u\n"
            "{\n"
            '  printf "SIGN_PASS=%s\\n" "${SIGN_PASS-<unset>}"\n'
            '  printf "SIGN_ALIAS=%s\\n" "${SIGN_ALIAS-<unset>}"\n'
            '  printf "ARGS=%s\\n" "$*"\n'
            '} > "$CAPTURE_FILE"\n'
            # Append-only, mode-tagged base64 capture: a single make invocation
            # can call the stub more than once (verify-local then local), and
            # the block above truncates $CAPTURE_FILE on each call.
            'printf "MODE=%s\\n" "${2-}" >> "$CAPTURE_FILE.bytes"\n'
            'printf \'SIGN_PASS_B64=%s\\n\' "$(printf \'%s\' "${SIGN_PASS-}" | base64 -w0)" >> "$CAPTURE_FILE.bytes"\n'
            'printf \'SIGN_ALIAS_B64=%s\\n\' "$(printf \'%s\' "${SIGN_ALIAS-}" | base64 -w0)" >> "$CAPTURE_FILE.bytes"\n',
            encoding="utf-8",
        )
        self.stub.chmod(0o755)

    def reset_byte_capture(self) -> None:
        self.byte_capture.unlink(missing_ok=True)

    def assert_raw_credentials(
        self, password: str, alias: str, modes: tuple[str, ...]
    ) -> None:
        """Decode the stub's base64 capture and require exact UTF-8 bytes per mode."""
        self.assertTrue(
            self.byte_capture.exists(), "stub JAVA did not run (no byte capture file)"
        )
        lines = self.byte_capture.read_text(encoding="ascii").splitlines()
        self.assertTrue(lines, "stub JAVA did not capture any values")
        captured: dict[str, dict[str, bytes]] = {}
        mode: str | None = None
        for line in lines:
            if line.startswith("MODE="):
                mode = line[len("MODE="):]
                captured.setdefault(mode, {})
                continue
            key, _, encoded = line.partition("=")
            self.assertIsNotNone(mode, f"byte capture line without a MODE: {line!r}")
            self.assertNotIn(key, captured[mode], f"duplicate capture for {key} in {mode}")
            captured[mode][key] = base64.b64decode(encoded, validate=True)
        self.assertEqual(tuple(captured), modes, f"unexpected stub modes: {captured!r}")
        for expected_mode in modes:
            with self.subTest(mode=expected_mode):
                values = captured[expected_mode]
                self.assertEqual(
                    set(values),
                    {"SIGN_PASS_B64", "SIGN_ALIAS_B64"},
                    f"incomplete byte capture for {expected_mode}: {values!r}",
                )
                self.assertEqual(values["SIGN_PASS_B64"], password.encode("utf-8"))
                self.assertEqual(values["SIGN_ALIAS_B64"], alias.encode("utf-8"))

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

    def test_signing_recipes_receive_credentials_after_unexport(self) -> None:
        """Global unexport must not strip credentials at the signing boundary.

        Both branches are covered: a new keystore (create-local) and an existing
        keystore, where the prerequisite chain signing.properties ->
        verify-signing-permissions -> keystore.jks must still receive them.
        The full raw-byte matrix is covered by the two recipe-boundary tests
        below; this test makes the R6 presence contract explicit.
        """
        password = "SENTINEL-P$raw$$tail"
        alias = "SENTINEL-A$xy"

        self.reset_byte_capture()
        result = run_make(
            "KMIS=", "keystore.jks", cwd=self.project,
            env=self.stub_env(password, alias), dry_run=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_raw_credentials(password, alias, ("create-local",))

        self.reset_byte_capture()
        (self.project / "keystore.jks").write_bytes(b"dummy keystore\n")
        result = run_make(
            "KMIS=", "signing.properties", cwd=self.project,
            env=self.stub_env(password, alias), dry_run=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assert_raw_credentials(password, alias, ("verify-local", "local"))

    def test_unset_credentials_fail_closed_without_invoking_java(self) -> None:
        env = clean_env({"JAVA": str(self.stub), "CAPTURE_FILE": str(self.capture)})
        result = run_make("KMIS=", "keystore.jks", cwd=self.project, env=env, dry_run=False)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("SIGN_PASS is not set", result.stderr)
        self.assertFalse(self.capture.exists())

    def stub_env(self, password: str, alias: str) -> dict[str, str]:
        return clean_env({
            "JAVA": str(self.stub),
            "CAPTURE_FILE": str(self.capture),
            "SIGN_PASS": password,
            "SIGN_ALIAS": alias,
        })

    def test_recipe_boundary_preserves_raw_credentials_for_new_keystore(self) -> None:
        for name, password, alias in RAW_CREDENTIAL_CASES:
            with self.subTest(case=name):
                self.reset_byte_capture()
                result = run_make(
                    "KMIS=", "keystore.jks", cwd=self.project,
                    env=self.stub_env(password, alias), dry_run=False,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assert_raw_credentials(password, alias, ("create-local",))

    def test_recipe_boundary_preserves_raw_credentials_for_existing_keystore(self) -> None:
        for name, password, alias in RAW_CREDENTIAL_CASES:
            with self.subTest(case=name):
                self.reset_byte_capture()
                # Stub JAVA does not validate the keystore, so any regular file
                # is enough to select the existing-keystore path.
                (self.project / "keystore.jks").write_bytes(b"dummy keystore\n")
                result = run_make(
                    "KMIS=", "signing.properties", cwd=self.project,
                    env=self.stub_env(password, alias), dry_run=False,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assert_raw_credentials(password, alias, ("verify-local", "local"))


class LkmMakefileTests(unittest.TestCase):
    def test_objcopy_defaults_to_llvm_objcopy(self) -> None:
        makefile = LKM_MAKEFILE.read_text(encoding="utf-8")
        self.assertIn("OBJCOPY ?= llvm-objcopy", makefile)
        self.assertIn("$(OBJCOPY) --strip-unneeded", makefile)


if __name__ == "__main__":
    unittest.main()
