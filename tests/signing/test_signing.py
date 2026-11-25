#!/usr/bin/env python3
"""Regression tests for signing-material serialization, publication, and configuration."""

from __future__ import annotations

import base64
import ctypes
import os
from pathlib import Path
import select
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
WRITER = ROOT / "scripts" / "WriteSigningProperties.java"
ROUND_TRIP_READER = Path(__file__).with_name("PropertiesRoundTrip.java")
KEYSTORE_READER = Path(__file__).with_name("KeystoreRoundTrip.java")
KEYSTORE_FIXTURE = Path(__file__).with_name("KeystoreFixture.java")
JAVA = Path(os.environ.get("JAVA") or shutil.which("java") or "java").resolve()
PROPERTY_KEYS = ("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
CI_KEYS = ("KEYSTORE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
FIXTURE_KEYS = ("FIXTURE_INPUT_PASSWORD", "FIXTURE_STORE_PASSWORD", "FIXTURE_KEY_PASSWORD", "FIXTURE_ALIAS")
LOCALES = ("C", "C.UTF-8")
SOURCE_PASSWORD = "source-password"
SPECIAL_VALUES = {
    "ascii": "ascii value",
    "leading_whitespace": "  leading",
    "trailing_whitespace": "trailing  ",
    "all_whitespace": " \t\n\r\f ",
    "backslash": r"back\slash\\",
    "equals": "a=b=c",
    "colon": "a:b:c",
    "hash": "#hash",
    "bang": "!bang",
    "tab": "\tTabbed\t",
    "line_feed": "line1\nline2",
    "carriage_return": "line1\rline2",
    "form_feed": "form\ffeed",
    "latin_1": "café £ © ÿ",
    "bmp_cjk": "漢字かな한글",
    "non_bmp": "password-😀-🔐-𝄞",
}

# Make-specific boundary cases.  These deliberately mirror the byte-level cases
# in tests/make/test_make.py (RAW_CREDENTIAL_CASES); values must also satisfy
# keytool's >=6-character password rule in the create-local path.  If keytool
# legitimately rejects a value (for example a password that is too short), the
# strict byte-boundary assertions in test_make.py remain the authoritative
# coverage and only the keytool-compatible subset belongs here.
MAKE_SPECIAL_VALUES = (
    ("literal_dollar", "bc$defgh", "alias$x"),
    ("double_dollar", "pass$$word", "alias$$xy"),
    ("backslashes", r"back\slash\\pwd", r"alias\back"),
    ("whitespace", "  spaced pass  ", "  spaced alias  "),
    ("tabs", "tab\there\tvalue", "tab\talias\tval"),
    ("cjk", "漢字かなパス", "漢字エイリアス"),
    ("non_bmp", "p😀ss🔐word", "ali😀as𝄞xyz"),
    ("combined", "  c😀mb\\ined$$ \t 汉字 ", "  a\\lias$😀\t字  "),
)


def filesystem_type(path: Path) -> str:
    try:
        return subprocess.check_output(
            ["findmnt", "-n", "-o", "FSTYPE", "-T", str(path)], text=True
        ).strip()
    except (FileNotFoundError, subprocess.CalledProcessError):
        return subprocess.check_output(
            ["stat", "-f", "-c", "%T", str(path)], text=True
        ).strip()


def permission_root() -> Path:
    configured = os.environ.get("PHASE2_PERMISSION_ROOT")
    if configured:
        return Path(configured)
    home = Path.home()
    return home if home.is_dir() and os.access(home, os.W_OK) else Path("/dev/shm")


def supporting_roots() -> list[tuple[Path, str]]:
    roots: list[tuple[Path, str]] = []
    seen: set[Path] = set()
    for candidate in (Path("/dev/shm"), permission_root()):
        candidate = candidate.resolve()
        if candidate in seen or not candidate.is_dir() or not os.access(candidate, os.W_OK):
            continue
        seen.add(candidate)
        filesystem = filesystem_type(candidate)
        if filesystem in ("tmpfs", "ext4"):
            roots.append((candidate, filesystem))
    return roots


SUPPORTING_ROOTS = supporting_roots()


def java_environment(values: dict[str, str], locale: str) -> dict[str, str]:
    environment = os.environ.copy()
    for key in (*CI_KEYS, "SIGN_PASS", "SIGN_ALIAS", *FIXTURE_KEYS):
        environment.pop(key, None)
    environment.update(values)
    environment["LC_ALL"] = locale
    environment["LANG"] = locale
    return environment


def run_java(
    source: Path,
    *args: str,
    environment: dict[str, str],
    timeout: int = 60,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(JAVA), str(source), *args],
        cwd=ROOT,
        env=environment,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )


def generate_source_keystore(directory: Path) -> Path:
    source = directory / "source.jks"
    environment = java_environment({"FIXTURE_INPUT_PASSWORD": SOURCE_PASSWORD}, "C.UTF-8")
    keytool = JAVA.parent.parent / "bin" / "keytool"
    result = subprocess.run(
        [
            str(keytool),
            "-genkeypair",
            "-noprompt",
            "-keystore", str(source),
            "-storetype", "JKS",
            "-storepass:env", "FIXTURE_INPUT_PASSWORD",
            "-keypass:env", "FIXTURE_INPUT_PASSWORD",
            "-alias", "source",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=DFRoot test fixture",
        ],
        env=environment,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=60,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("Could not generate source JKS: " + result.stdout + result.stderr)
    return source


def write_fixture_keystore(
    source: Path,
    destination: Path,
    password: str,
    alias: str,
    key_password: str | None = None,
    cert_only: bool = False,
) -> Path:
    environment = java_environment(
        {
            "FIXTURE_INPUT_PASSWORD": SOURCE_PASSWORD,
            "FIXTURE_STORE_PASSWORD": password,
            "FIXTURE_KEY_PASSWORD": password if key_password is None else key_password,
            "FIXTURE_ALIAS": alias,
        },
        "C.UTF-8",
    )
    result = run_java(
        KEYSTORE_FIXTURE,
        str(source),
        str(destination),
        "cert" if cert_only else "key",
        environment=environment,
    )
    if result.returncode != 0:
        raise RuntimeError("Could not rewrite JKS fixture: " + result.stdout + result.stderr)
    os.chmod(destination, 0o600)
    return destination


def make_secure_directory(path: Path) -> Path:
    path.mkdir(mode=0o700)
    os.chmod(path, 0o700)
    return path


def expected_properties(
    password: str, alias: str, key_password: str | None = None
) -> dict[str, str]:
    return {
        "KEYSTORE_FILE": "../keystore.jks",
        "KEYSTORE_PASSWORD": password,
        "KEY_ALIAS": alias,
        "KEY_PASSWORD": password if key_password is None else key_password,
    }


def serialize_properties_fixture(properties: dict[str, str]) -> bytes:
    """Encode a test fixture in the ISO-8859-1 format consumed by Properties.load."""
    lines = []
    for key, value in properties.items():
        escaped = []
        leading = True
        for character in value:
            code = ord(character)
            if character == "\\":
                escaped.append("\\\\")
            elif character == "\t":
                escaped.append("\\t")
            elif character == "\n":
                escaped.append("\\n")
            elif character == "\r":
                escaped.append("\\r")
            elif character == "\f":
                escaped.append("\\f")
            elif character == " " and leading:
                escaped.append("\\ ")
            elif character in "=:#!":
                escaped.append("\\" + character)
            elif code < 0x20 or code > 0x7E:
                if code <= 0xFFFF:
                    escaped.append(f"\\u{code:04x}")
                else:
                    supplementary = code - 0x10000
                    high = 0xD800 + (supplementary >> 10)
                    low = 0xDC00 + (supplementary & 0x3FF)
                    escaped.append(f"\\u{high:04x}\\u{low:04x}")
            else:
                escaped.append(character)
            if character != " ":
                leading = False
        lines.append(f"{key}={''.join(escaped)}\n")
    return "".join(lines).encode("latin-1")


class CreateWatch:
    """Deterministic Linux inotify watch used for publication-race regressions."""

    IN_CREATE = 0x00000100
    IN_CLOSE_WRITE = 0x00000008
    IN_ONLYDIR = 0x01000000
    IN_ISDIR = 0x40000000

    def __init__(self, root: Path):
        self.root = root
        self.library = ctypes.CDLL(None, use_errno=True)
        self.library.inotify_init1.argtypes = [ctypes.c_int]
        self.library.inotify_init1.restype = ctypes.c_int
        self.library.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
        self.library.inotify_add_watch.restype = ctypes.c_int
        self.descriptor = -1

    def __enter__(self) -> "CreateWatch":
        self.descriptor = self.library.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
        if self.descriptor < 0:
            raise OSError(ctypes.get_errno(), "inotify_init1 failed")
        mask = self.IN_CREATE | self.IN_CLOSE_WRITE | self.IN_ONLYDIR
        if self.library.inotify_add_watch(self.descriptor, os.fsencode(self.root), mask) < 0:
            error = ctypes.get_errno()
            os.close(self.descriptor)
            self.descriptor = -1
            raise OSError(error, "inotify_add_watch failed")
        return self

    def wait_for(self, prefix: str, event_mask: int, process: subprocess.Popen[str]) -> None:
        while True:
            ready, _, _ = select.select([self.descriptor], [], [], 30)
            if not ready:
                output = process.communicate(timeout=5)[0] if process.poll() is not None else ""
                raise AssertionError(f"Timed out waiting for {prefix}; writer output: {output}")
            data = os.read(self.descriptor, 65536)
            offset = 0
            while offset + 16 <= len(data):
                _, mask, _, length = struct.unpack_from("iIII", data, offset)
                name = data[offset + 16 : offset + 16 + length].split(b"\0", 1)[0].decode()
                offset += 16 + length
                if mask & event_mask and name.startswith(prefix):
                    return
            if process.poll() is not None:
                stdout, stderr = process.communicate()
                raise AssertionError(
                    f"Writer exited before inotify event {event_mask:#x} for {prefix}: {stdout}{stderr}"
                )

    def __exit__(self, *_: object) -> None:
        if self.descriptor >= 0:
            os.close(self.descriptor)
            self.descriptor = -1


class SigningWriterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if not SUPPORTING_ROOTS:
            raise RuntimeError("At least one writable tmpfs/ext4 filesystem is required for signing tests")
        cls.fixture_root = Path(tempfile.mkdtemp(prefix="dfroot-signing-fixtures-", dir=SUPPORTING_ROOTS[0][0]))
        cls.addClassCleanup(shutil.rmtree, cls.fixture_root, True)
        cls.source_keystore = generate_source_keystore(cls.fixture_root)
        cls.default_keystore = cls.fixture_root / "default.jks"
        write_fixture_keystore(cls.source_keystore, cls.default_keystore, "password", "alias")
        cls.keystore_bytes = cls.default_keystore.read_bytes()
        cls.keystore_base64 = base64.b64encode(cls.keystore_bytes).decode("ascii")
        sys.stderr.write(
            "Supporting filesystem coverage: "
            + ", ".join(f"{kind} ({path})" for path, kind in SUPPORTING_ROOTS)
            + "\n"
        )

    def setUp(self) -> None:
        self.support_root = Path(tempfile.mkdtemp(prefix="dfroot-signing-unit-", dir=SUPPORTING_ROOTS[0][0]))
        self.addCleanup(shutil.rmtree, self.support_root, True)

    def run_writer(
        self,
        mode: str,
        root: Path,
        values: dict[str, str],
        locale: str = "C.UTF-8",
    ) -> subprocess.CompletedProcess[str]:
        return run_java(WRITER, mode, str(root), environment=java_environment(values, locale))

    def create_test_keystore(
        self,
        root: Path,
        password: str = "password",
        alias: str = "alias",
        key_password: str | None = None,
        cert_only: bool = False,
    ) -> Path:
        return write_fixture_keystore(
            self.source_keystore,
            root / "keystore.jks",
            password,
            alias,
            key_password,
            cert_only,
        )

    def valid_ci_values(
        self, password: str = "password", alias: str = "alias", key_password: str | None = None
    ) -> dict[str, str]:
        return {
            "KEYSTORE": self.keystore_base64,
            "KEYSTORE_PASSWORD": password,
            "KEY_ALIAS": alias,
            "KEY_PASSWORD": password if key_password is None else key_password,
        }

    def assert_success(self, result: subprocess.CompletedProcess[str]) -> None:
        self.assertEqual(result.returncode, 0, f"writer failed:\n{result.stdout}\n{result.stderr}")

    def assert_failure(self, result: subprocess.CompletedProcess[str], message: str = "") -> None:
        self.assertNotEqual(result.returncode, 0, "writer unexpectedly succeeded")
        if message:
            self.assertIn(message, result.stderr)

    def assert_private_file(self, path: Path) -> None:
        metadata = path.lstat()
        self.assertTrue(stat.S_ISREG(metadata.st_mode), f"not a regular file: {path}")
        self.assertEqual(stat.S_IMODE(metadata.st_mode), 0o600, f"insecure mode: {path}")
        self.assertEqual(metadata.st_uid, os.getuid(), f"wrong owner: {path}")

    def assert_no_outputs(self, root: Path) -> None:
        self.assertFalse(os.path.lexists(root / "signing.properties"))
        self.assertFalse(os.path.lexists(root / "keystore.jks"))

    def assert_no_temporary_files(self, root: Path) -> None:
        leftovers = [path.name for path in root.iterdir() if path.name.startswith(".")]
        self.assertEqual(leftovers, [], f"temporary files were not cleaned up: {leftovers}")

    def assert_round_trip(self, root: Path, expected: dict[str, str], locale: str) -> None:
        result = run_java(
            ROUND_TRIP_READER,
            str(root / "signing.properties"),
            environment=java_environment({}, locale),
        )
        self.assert_success(result)
        actual = {}
        for line in result.stdout.splitlines():
            key, encoded = line.split("=", 1)
            actual[key] = base64.b64decode(encoded, validate=True).decode("utf-8")
        self.assertEqual(actual, expected)

    def test_ci_round_trip_all_edge_cases_under_both_locales(self) -> None:
        for name, value in SPECIAL_VALUES.items():
            with self.subTest(value=name):
                with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
                    case = Path(directory)
                    fixture = case / "fixture"
                    fixture.mkdir()
                    self.create_test_keystore(fixture, value, value, value)
                    encoded = base64.b64encode((fixture / "keystore.jks").read_bytes()).decode("ascii")
                    for locale in LOCALES:
                        with self.subTest(locale=locale):
                            root = make_secure_directory(case / locale.replace(".", "-"))
                            result = self.run_writer(
                                "ci",
                                root,
                                {
                                    "KEYSTORE": encoded,
                                    "KEYSTORE_PASSWORD": value,
                                    "KEY_ALIAS": value,
                                    "KEY_PASSWORD": value,
                                },
                                locale,
                            )
                            self.assert_success(result)
                            self.assert_private_file(root / "signing.properties")
                            self.assert_private_file(root / "keystore.jks")
                            self.assertEqual((root / "keystore.jks").read_bytes(), (fixture / "keystore.jks").read_bytes())
                            self.assert_round_trip(root, expected_properties(value, value), locale)
                            self.assert_no_temporary_files(root)

    def test_ci_preserves_distinct_store_and_key_passwords(self) -> None:
        store_password = "  store-password-😀  "
        key_password = "  key-password-🔐  "
        alias = "  distinct-alias-𝄞  "
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            case = Path(directory)
            fixture = case / "fixture"
            output = case / "output"
            fixture.mkdir()
            make_secure_directory(output)
            self.create_test_keystore(fixture, store_password, alias, key_password)
            encoded = base64.b64encode((fixture / "keystore.jks").read_bytes()).decode("ascii")
            result = self.run_writer(
                "ci",
                output,
                {
                    "KEYSTORE": encoded,
                    "KEYSTORE_PASSWORD": store_password,
                    "KEY_ALIAS": alias,
                    "KEY_PASSWORD": key_password,
                },
            )
            self.assert_success(result)
            self.assert_round_trip(output, expected_properties(store_password, alias, key_password), "C.UTF-8")

    def test_ci_validates_keystore_alias_passwords_and_private_key(self) -> None:
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            case = Path(directory)
            valid = case / "valid"
            cert = case / "cert"
            valid.mkdir()
            cert.mkdir()
            self.create_test_keystore(valid, "store-password", "key-alias", "key-password")
            self.create_test_keystore(cert, "store-password", "key-alias", cert_only=True)
            valid_encoded = base64.b64encode((valid / "keystore.jks").read_bytes()).decode("ascii")
            cert_encoded = base64.b64encode((cert / "keystore.jks").read_bytes()).decode("ascii")
            cases = (
                ("malformed JKS", base64.b64encode(b"not a keystore").decode(), "password", "alias", "password", "KEYSTORE"),
                ("wrong store password", valid_encoded, "wrong-password", "key-alias", "key-password", "KEYSTORE_PASSWORD"),
                ("wrong key password", valid_encoded, "store-password", "key-alias", "wrong-password", "KEY_PASSWORD"),
                ("missing alias", valid_encoded, "store-password", "missing-alias", "key-password", "KEY_ALIAS"),
                ("certificate only", cert_encoded, "store-password", "key-alias", "key-password", "private-key"),
            )
            for name, encoded, store_password, alias, key_password, message in cases:
                with self.subTest(case=name):
                    output = make_secure_directory(case / name.replace(" ", "-"))
                    result = self.run_writer(
                        "ci",
                        output,
                        {
                            "KEYSTORE": encoded,
                            "KEYSTORE_PASSWORD": store_password,
                            "KEY_ALIAS": alias,
                            "KEY_PASSWORD": key_password,
                        },
                    )
                    self.assert_failure(result, message)
                    self.assert_no_outputs(output)
                    self.assert_no_temporary_files(output)

    def test_local_round_trip_under_both_locales(self) -> None:
        for locale in LOCALES:
            with self.subTest(locale=locale):
                with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
                    root = Path(directory)
                    password = "  café-漢字-😀\\=:\n\r\f\t  "
                    alias = "  alias-🔐-𝄞  "
                    self.create_test_keystore(root, password, alias)
                    result = self.run_writer("local", root, {"SIGN_PASS": password, "SIGN_ALIAS": alias}, locale)
                    self.assert_success(result)
                    self.assert_private_file(root / "signing.properties")
                    self.assert_round_trip(root, expected_properties(password, alias), locale)
                    self.assert_no_temporary_files(root)

    def test_local_accepts_whitespace_only_credentials(self) -> None:
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            password = " \t "
            alias = "\r\n\f "
            self.create_test_keystore(root, password, alias)
            result = self.run_writer("local", root, {"SIGN_PASS": password, "SIGN_ALIAS": alias})
            self.assert_success(result)
            self.assert_round_trip(root, expected_properties(password, alias), "C.UTF-8")

    def test_verify_local_checks_keystore_and_valid_properties_without_rewriting(self) -> None:
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            self.create_test_keystore(root)
            properties = root / "signing.properties"
            valid = serialize_properties_fixture(expected_properties("password", "alias"))
            properties.write_bytes(valid)
            os.chmod(properties, 0o600)
            before = properties.stat()
            result = self.run_writer("verify-local", root, {"SIGN_PASS": "password", "SIGN_ALIAS": "alias"})
            self.assert_success(result)
            self.assertEqual(properties.read_bytes(), valid)
            self.assertEqual(properties.stat().st_mtime_ns, before.st_mtime_ns)
            result = self.run_writer("verify-local", root, {"SIGN_PASS": "password", "SIGN_ALIAS": "wrong-alias"})
            self.assert_failure(result, "SIGN_ALIAS")
            self.assertEqual(properties.read_bytes(), valid)

    def test_verify_local_allows_absent_properties_without_creating_them(self) -> None:
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            self.create_test_keystore(root)
            result = self.run_writer("verify-local", root, {"SIGN_PASS": "password", "SIGN_ALIAS": "alias"})
            self.assert_success(result)
            self.assertFalse((root / "signing.properties").exists())

    def test_ci_requires_every_nonempty_environment_value(self) -> None:
        valid = self.valid_ci_values()
        for key in CI_KEYS:
            for case in ("missing", "empty"):
                with self.subTest(key=key, case=case):
                    with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
                        root = Path(directory)
                        values = valid.copy()
                        if case == "missing":
                            del values[key]
                        else:
                            values[key] = ""
                        result = self.run_writer("ci", root, values)
                        self.assert_failure(result, key)
                        self.assert_no_outputs(root)
                        self.assert_no_temporary_files(root)

    def test_ci_rejects_malformed_or_empty_decoded_base64(self) -> None:
        for value in ("%%%not-base64%%%", "A", "====", " \t\n", "\r\n", "\n"):
            with self.subTest(value=repr(value)):
                with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
                    root = Path(directory)
                    values = self.valid_ci_values()
                    values["KEYSTORE"] = value
                    result = self.run_writer("ci", root, values)
                    self.assert_failure(result, "KEYSTORE")
                    self.assert_no_outputs(root)
                    self.assert_no_temporary_files(root)

    def test_ci_refuses_existing_pair_and_preserves_preexisting_material(self) -> None:
        for obstruction in ("file", "directory", "symlink"):
            for existing in ("keystore.jks", "signing.properties"):
                with self.subTest(obstruction=obstruction, existing=existing):
                    with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
                        root = Path(directory)
                        old = b"pre-existing signing material\n"
                        old_path = root / existing
                        old_path.write_bytes(old)
                        os.chmod(old_path, 0o600)
                        other = "signing.properties" if existing == "keystore.jks" else "keystore.jks"
                        other_path = root / other
                        victim = root / "victim"
                        if obstruction == "file":
                            other_path.write_bytes(b"other old material\n")
                            os.chmod(other_path, 0o600)
                        elif obstruction == "directory":
                            other_path.mkdir()
                            (other_path / "marker").write_text("keep", encoding="utf-8")
                        else:
                            victim.write_bytes(b"symlink victim\n")
                            other_path.symlink_to(victim.name)

                        result = self.run_writer("ci", root, self.valid_ci_values())
                        self.assert_failure(result, "already exists")
                        self.assertEqual(old_path.read_bytes(), old)
                        if victim.exists():
                            self.assertEqual(victim.read_bytes(), b"symlink victim\n")
                        if obstruction == "file":
                            self.assertEqual(other_path.read_bytes(), b"other old material\n")
                        elif obstruction == "directory":
                            self.assertTrue(other_path.is_dir())
                            self.assertEqual((other_path / "marker").read_text(), "keep")
                        else:
                            self.assertTrue(other_path.is_symlink())
                            self.assertEqual(os.readlink(other_path), victim.name)
                        self.assert_no_temporary_files(root)

    def test_ci_second_publish_failure_rolls_back_only_created_keystore(self) -> None:
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            environment = java_environment(self.valid_ci_values(), "C.UTF-8")
            with CreateWatch(root) as watch:
                process = subprocess.Popen(
                    [str(JAVA), str(WRITER), "ci", str(root)],
                    cwd=ROOT,
                    env=environment,
                    text=True,
                    encoding="utf-8",
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )
                try:
                    watch.wait_for(".keystore.jks-", CreateWatch.IN_CREATE, process)
                    obstruction = root / "signing.properties"
                    obstruction.mkdir()
                    (obstruction / "marker").write_text("concurrent properties", encoding="utf-8")
                    stdout, stderr = process.communicate(timeout=60)
                finally:
                    if process.poll() is None:
                        process.kill()
                    process.communicate(timeout=5)
            result = subprocess.CompletedProcess(process.args, process.returncode, stdout, stderr)
            self.assert_failure(result, "already exists")
            self.assertFalse(os.path.lexists(root / "keystore.jks"), "first published file was not rolled back")
            self.assertTrue(obstruction.is_dir())
            self.assertEqual((obstruction / "marker").read_text(), "concurrent properties")
            self.assert_no_temporary_files(root)

    def test_local_refuses_to_replace_existing_properties(self) -> None:
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            self.create_test_keystore(root)
            properties = root / "signing.properties"
            valid = serialize_properties_fixture(expected_properties("password", "alias"))
            properties.write_bytes(valid)
            os.chmod(properties, 0o600)
            result = self.run_writer("local", root, {"SIGN_PASS": "password", "SIGN_ALIAS": "alias"})
            self.assert_failure(result, "already exists")
            self.assertEqual(properties.read_bytes(), valid)
            self.assert_no_temporary_files(root)

    def test_local_requires_credentials_and_existing_private_keystore(self) -> None:
        for key in ("SIGN_PASS", "SIGN_ALIAS"):
            for case in ("missing", "empty"):
                with self.subTest(key=key, case=case):
                    with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
                        root = Path(directory)
                        self.create_test_keystore(root)
                        values = {"SIGN_PASS": "password", "SIGN_ALIAS": "alias"}
                        if case == "missing":
                            del values[key]
                        else:
                            values[key] = ""
                        result = self.run_writer("local", root, values)
                        self.assert_failure(result, key)
                        self.assertFalse((root / "signing.properties").exists())

        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            result = self.run_writer("local", root, {"SIGN_PASS": "password", "SIGN_ALIAS": "alias"})
            self.assert_failure(result, "keystore.jks")
            self.assert_no_outputs(root)

        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            keystore = self.create_test_keystore(root)
            os.chmod(keystore, 0o644)
            result = self.run_writer("local", root, {"SIGN_PASS": "password", "SIGN_ALIAS": "alias"})
            self.assert_failure(result, "0600")
            self.assertFalse((root / "signing.properties").exists())

        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            self.create_test_keystore(root)
            result = self.run_writer("verify-local", root, {"SIGN_PASS": "wrong-password", "SIGN_ALIAS": "alias"})
            self.assert_failure(result, "SIGN_PASS")
            self.assertFalse((root / "signing.properties").exists())

    def test_credentials_never_appear_in_writer_output(self) -> None:
        password = "TOP-SECRET-PASSWORD-😀"
        alias = "TOP-SECRET-ALIAS-🔐"
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            case = Path(directory)
            fixture = case / "fixture"
            output = case / "output"
            fixture.mkdir()
            make_secure_directory(output)
            self.create_test_keystore(fixture, password, alias)
            encoded = base64.b64encode((fixture / "keystore.jks").read_bytes()).decode("ascii")
            result = self.run_writer(
                "ci",
                output,
                {
                    "KEYSTORE": encoded,
                    "KEYSTORE_PASSWORD": password,
                    "KEY_ALIAS": alias,
                    "KEY_PASSWORD": password,
                },
            )
            self.assert_success(result)
            self.assertNotIn(password, result.stdout + result.stderr)
            self.assertNotIn(alias, result.stdout + result.stderr)

    def test_create_local_keystore_preserves_non_bmp_credentials(self) -> None:
        password = "  create-password-😀-🔐  "
        alias = "  create-alias-😀-𝄞  "
        values = {"SIGN_PASS": password, "SIGN_ALIAS": alias}
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            result = self.run_writer("create-local", root, values)
            self.assert_success(result)
            self.assertNotIn(password, result.stdout + result.stderr)
            self.assertNotIn(alias, result.stdout + result.stderr)
            self.assert_private_file(root / "keystore.jks")
            self.assertFalse((root / "signing.properties").exists())
            self.assert_no_temporary_files(root)

            verified = run_java(
                KEYSTORE_READER,
                str(root / "keystore.jks"),
                environment=java_environment(values, "C.UTF-8"),
            )
            self.assert_success(verified)

            result = self.run_writer("local", root, values)
            self.assert_success(result)
            self.assert_round_trip(root, expected_properties(password, alias), "C.UTF-8")

    def test_create_local_requires_credentials_and_never_overwrites(self) -> None:
        for key in ("SIGN_PASS", "SIGN_ALIAS"):
            for case in ("missing", "empty"):
                with self.subTest(key=key, case=case):
                    with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
                        root = Path(directory)
                        values = {"SIGN_PASS": "password", "SIGN_ALIAS": "alias"}
                        if case == "missing":
                            del values[key]
                        else:
                            values[key] = ""
                        result = self.run_writer("create-local", root, values)
                        self.assert_failure(result, key)
                        self.assert_no_outputs(root)
                        self.assertEqual(list(root.iterdir()), [])

        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            keystore = self.create_test_keystore(root)
            original = keystore.read_bytes()
            result = self.run_writer("create-local", root, {"SIGN_PASS": "password", "SIGN_ALIAS": "alias"})
            self.assert_failure(result, "already exists")
            self.assertEqual(keystore.read_bytes(), original)

    def test_create_local_publication_race_preserves_concurrent_keystore(self) -> None:
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            environment = java_environment({"SIGN_PASS": "password", "SIGN_ALIAS": "alias"}, "C.UTF-8")
            with CreateWatch(root) as watch:
                process = subprocess.Popen(
                    [str(JAVA), str(WRITER), "create-local", str(root)],
                    cwd=ROOT,
                    env=environment,
                    text=True,
                    encoding="utf-8",
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )
                try:
                    watch.wait_for(".signing-keystore-", CreateWatch.IN_CREATE | CreateWatch.IN_ISDIR, process)
                    concurrent = root / "keystore.jks"
                    descriptor = os.open(concurrent, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    with os.fdopen(descriptor, "wb") as output:
                        output.write(b"concurrent keystore owner\n")
                    stdout, stderr = process.communicate(timeout=60)
                finally:
                    if process.poll() is None:
                        process.kill()
                    process.communicate(timeout=5)
            result = subprocess.CompletedProcess(process.args, process.returncode, stdout, stderr)
            self.assert_failure(result, "already exists")
            self.assertEqual((root / "keystore.jks").read_bytes(), b"concurrent keystore owner\n")
            self.assertFalse((root / "signing.properties").exists())
            self.assert_no_temporary_files(root)

    def test_create_local_resolves_keytool_from_java_home(self) -> None:
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            fake_bin = root / "bin"
            fake_bin.mkdir()
            marker = root / "path-keytool-ran"
            fake_keytool = fake_bin / "keytool"
            fake_keytool.write_text(f"#!/bin/sh\ntouch '{marker}'\nexit 1\n", encoding="utf-8")
            fake_keytool.chmod(0o755)
            environment = java_environment({"SIGN_PASS": "password", "SIGN_ALIAS": "alias"}, "C.UTF-8")
            environment["PATH"] = str(fake_bin)
            result = run_java(WRITER, "create-local", str(root), environment=environment)
            self.assert_success(result)
            self.assertFalse(marker.exists(), "keytool was resolved through PATH")
            self.assert_private_file(root / "keystore.jks")

    def test_rejects_group_or_other_writable_root(self) -> None:
        with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
            root = Path(directory)
            os.chmod(root, 0o777)
            try:
                result = self.run_writer("ci", root, self.valid_ci_values())
                self.assert_failure(result, "group/other")
                self.assert_no_outputs(root)
            finally:
                os.chmod(root, 0o700)

    def test_rejects_non_sticky_writable_intermediate_ancestor(self) -> None:
        for mode in (0o777, 0o1777):
            with self.subTest(mode=oct(mode)):
                with tempfile.TemporaryDirectory(dir=self.support_root) as directory:
                    base = Path(directory)
                    intermediate = base / "unsafe-intermediate"
                    intermediate.mkdir()
                    os.chmod(intermediate, mode)
                    root = make_secure_directory(intermediate / "project")
                    try:
                        result = self.run_writer("ci", root, self.valid_ci_values())
                        self.assert_failure(result, "ancestor")
                        self.assertIn("sticky", result.stderr)
                        self.assertEqual(list(root.iterdir()), [])
                    finally:
                        os.chmod(intermediate, 0o700)

    def test_allows_root_owned_ancestors_but_still_requires_a_keystore(self) -> None:
        foreign = Path("/etc")
        if not foreign.is_dir() or foreign.stat().st_uid != 0:
            self.skipTest("No root-owned readable directory is available")
        result = self.run_writer("verify-local", foreign, {"SIGN_PASS": "password", "SIGN_ALIAS": "alias"})
        self.assert_failure(result)
        self.assertNotIn("current user", result.stderr)
        self.assertIn("keystore.jks", result.stderr)

    def test_permissions_succeed_on_all_supporting_filesystems(self) -> None:
        for directory, filesystem in SUPPORTING_ROOTS:
            with self.subTest(filesystem=filesystem, root=str(directory)):
                with tempfile.TemporaryDirectory(prefix="dfroot-signing-mode-", dir=directory) as temporary:
                    root = Path(temporary)
                    result = self.run_writer("ci", root, self.valid_ci_values())
                    self.assert_success(result)
                    self.assert_private_file(root / "signing.properties")
                    self.assert_private_file(root / "keystore.jks")
                    self.assertEqual(filesystem_type(root), filesystem)
                    self.assert_no_temporary_files(root)

    def test_permissions_fail_closed_on_fuseblk(self) -> None:
        if filesystem_type(ROOT) != "fuseblk":
            self.skipTest("worktree is not on fuseblk")
        with tempfile.TemporaryDirectory(prefix="dfroot-signing-fuseblk-", dir=ROOT) as directory:
            root = Path(directory)
            result = self.run_writer("ci", root, self.valid_ci_values())
            self.assert_failure(result, "group/other")
            self.assert_no_outputs(root)
            self.assert_no_temporary_files(root)

        with tempfile.TemporaryDirectory(prefix="dfroot-signing-fuseblk-", dir=ROOT) as directory:
            root = Path(directory)
            result = self.run_writer("create-local", root, {"SIGN_PASS": "password", "SIGN_ALIAS": "alias"})
            self.assert_failure(result, "group/other")
            self.assert_no_outputs(root)
            self.assert_no_temporary_files(root)


class SigningIntegrationTests(unittest.TestCase):
    def test_both_build_paths_use_the_shared_writer(self) -> None:
        for relative in (".github/workflows/build.yml", "Makefile"):
            with self.subTest(path=relative):
                content = (ROOT / relative).read_text(encoding="utf-8")
                self.assertNotIn("esc_props", content)
                self.assertIn("WriteSigningProperties.java", content)

    def test_ci_sets_up_java_21_before_writing_signing_material(self) -> None:
        workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        self.assertLess(workflow.index("actions/setup-java"), workflow.index("WriteSigningProperties.java"))

    def test_make_uses_java_verifier_as_order_only_prerequisite(self) -> None:
        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        self.assertIn("WriteSigningProperties.java verify-local", makefile)
        self.assertIn("signing.properties: | verify-signing-permissions", makefile)
        self.assertNotIn("stat -c", makefile)

    def test_staging_uses_atomic_creation_attributes_without_reopening(self) -> None:
        writer = WRITER.read_text(encoding="utf-8")
        self.assertIn("StandardOpenOption.CREATE_NEW", writer)
        self.assertIn("LinkOption.NOFOLLOW_LINKS", writer)
        # Named staging is required for hard-link publication. Only the keytool-created
        # file inside the verified 0700 directory is tightened by pathname.
        self.assertNotIn("StandardOpenOption.DELETE_ON_CLOSE", writer)
        self.assertEqual(writer.count("Files.setPosixFilePermissions"), 1)


class MakeSigningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if not SUPPORTING_ROOTS:
            raise RuntimeError("At least one writable tmpfs/ext4 filesystem is required for Make tests")
        cls.fixture_root = Path(tempfile.mkdtemp(prefix="dfroot-make-fixtures-", dir=SUPPORTING_ROOTS[0][0]))
        cls.addClassCleanup(shutil.rmtree, cls.fixture_root, True)
        cls.source_keystore = generate_source_keystore(cls.fixture_root)

    def run_make(
        self,
        project: Path,
        values: dict[str, str],
        locale: str,
        target: str = "signing.properties",
        dry_run: bool = False,
        extra_args: tuple[str, ...] = (),
    ) -> subprocess.CompletedProcess[str]:
        environment = java_environment(values, locale)
        environment["JAVA"] = str(JAVA)
        command = ["make", "-f", str(ROOT / "Makefile"), "-C", str(project)]
        if dry_run:
            command.append("-n")
        command.extend(extra_args)
        command.append(target)
        return subprocess.run(
            command,
            cwd=ROOT,
            env=environment,
            text=True,
            encoding="utf-8",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=90,
            check=False,
        )

    def prepare_project(self, project: Path, password: str, alias: str, create_keystore: bool = True) -> None:
        (project / "scripts").symlink_to(ROOT / "scripts", target_is_directory=True)
        (project / ".github").symlink_to(ROOT / ".github", target_is_directory=True)
        if create_keystore:
            write_fixture_keystore(self.source_keystore, project / "keystore.jks", password, alias)

    def write_properties(self, project: Path, password: str, alias: str) -> Path:
        properties = project / "signing.properties"
        properties.write_bytes(serialize_properties_fixture(expected_properties(password, alias)))
        os.chmod(properties, 0o600)
        return properties

    def assert_properties(self, project: Path, password: str, alias: str, locale: str) -> None:
        result = run_java(
            ROUND_TRIP_READER,
            str(project / "signing.properties"),
            environment=java_environment({}, locale),
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        actual = {}
        for line in result.stdout.splitlines():
            key, encoded = line.split("=", 1)
            actual[key] = base64.b64decode(encoded, validate=True).decode("utf-8")
        self.assertEqual(actual, expected_properties(password, alias))

    def test_make_signing_properties_under_both_locales_without_needless_rewrite(self) -> None:
        password = "  make-password-😀-🔐  "
        alias = "  make-alias-😀-𝄞  "
        values = {"SIGN_PASS": password, "SIGN_ALIAS": alias}
        for locale in LOCALES:
            with self.subTest(locale=locale):
                with tempfile.TemporaryDirectory(prefix="dfroot-make-test-", dir=SUPPORTING_ROOTS[0][0]) as directory:
                    project = Path(directory)
                    self.prepare_project(project, password, alias)
                    first = self.run_make(project, values, locale)
                    self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
                    self.assertIn("Verified existing keystore.jks", first.stdout)
                    self.assertIn("Wrote signing.properties", first.stdout)
                    self.assert_properties(project, password, alias, locale)
                    properties = project / "signing.properties"
                    self.assertEqual(stat.S_IMODE(properties.stat().st_mode), 0o600)
                    before = properties.stat()

                    second = self.run_make(project, values, locale)
                    self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
                    self.assertIn("Verified existing keystore.jks", second.stdout)
                    self.assertNotIn("Wrote signing.properties", second.stdout)
                    self.assertEqual(properties.stat().st_mtime_ns, before.st_mtime_ns)
                    self.assertEqual(properties.stat().st_ino, before.st_ino)

    def test_make_rejects_invalid_or_stale_existing_properties(self) -> None:
        password = "  make-password-😀-🔐  "
        alias = "  make-alias-😀-𝄞  "
        values = {"SIGN_PASS": password, "SIGN_ALIAS": alias}
        for case in ("mode-0644", "symlink", "stale-alias", "stale-password"):
            with self.subTest(case=case):
                with tempfile.TemporaryDirectory(prefix="dfroot-make-invalid-", dir=SUPPORTING_ROOTS[0][0]) as directory:
                    project = Path(directory)
                    self.prepare_project(project, password, alias)
                    properties = project / "signing.properties"
                    victim = project / "properties-victim"
                    if case == "mode-0644":
                        self.write_properties(project, password, alias)
                        os.chmod(properties, 0o644)
                        original = properties.read_bytes()
                    elif case == "symlink":
                        victim.write_bytes(serialize_properties_fixture(expected_properties(password, alias)))
                        os.chmod(victim, 0o600)
                        properties.symlink_to(victim.name)
                        original = victim.read_bytes()
                    elif case == "stale-alias":
                        properties.write_bytes(serialize_properties_fixture(expected_properties(password, "stale-alias")))
                        os.chmod(properties, 0o600)
                        original = properties.read_bytes()
                    else:
                        properties.write_bytes(serialize_properties_fixture(expected_properties("stale-password", alias)))
                        os.chmod(properties, 0o600)
                        original = properties.read_bytes()

                    result = self.run_make(project, values, "C.UTF-8")
                    self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn("remove", (result.stdout + result.stderr).lower())
                    self.assertIn("regenerate", (result.stdout + result.stderr).lower())
                    if case == "symlink":
                        self.assertTrue(properties.is_symlink())
                        self.assertEqual(victim.read_bytes(), original)
                    else:
                        self.assertEqual(properties.read_bytes(), original)
                        self.assertEqual(stat.S_IMODE(properties.stat().st_mode), 0o644 if case == "mode-0644" else 0o600)

    def test_make_dry_run_schedules_gradle_after_missing_keystore_creation(self) -> None:
        password = "  make-stale-apk-😀  "
        alias = "  make-stale-apk-𝄞  "
        values = {"SIGN_PASS": password, "SIGN_ALIAS": alias}
        with tempfile.TemporaryDirectory(prefix="dfroot-make-stale-apk-", dir=SUPPORTING_ROOTS[0][0]) as directory:
            project = Path(directory)
            self.prepare_project(project, password, alias, create_keystore=False)
            self.write_properties(project, password, alias)
            output = project / "out"
            output.mkdir()
            apk = output / "DFRoot.apk"
            apk.write_bytes(b"existing APK placeholder\n")
            result = self.run_make(
                project,
                values,
                "C.UTF-8",
                target="out/DFRoot.apk",
                dry_run=True,
                extra_args=("KMIS=", "APK_SRCS="),
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            create = result.stdout.index("WriteSigningProperties.java create-local")
            verify = result.stdout.index("WriteSigningProperties.java verify-local")
            gradle = result.stdout.index("./gradlew :app:assembleRelease")
            self.assertLess(create, verify)
            self.assertLess(verify, gradle)

    def test_make_creates_missing_keystore_under_c_locale(self) -> None:
        password = "  make-create-😀-🔐  "
        alias = "  make-create-𝄞  "
        values = {"SIGN_PASS": password, "SIGN_ALIAS": alias}
        with tempfile.TemporaryDirectory(prefix="dfroot-make-create-", dir=SUPPORTING_ROOTS[0][0]) as directory:
            project = Path(directory)
            self.prepare_project(project, password, alias, create_keystore=False)
            result = self.run_make(project, values, "C")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(stat.S_IMODE((project / "keystore.jks").stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE((project / "signing.properties").stat().st_mode), 0o600)
            self.assert_properties(project, password, alias, "C")

    def test_make_new_keystore_round_trips_shell_metacharacters_and_unicode(self) -> None:
        for name, password, alias in MAKE_SPECIAL_VALUES:
            with self.subTest(value=name):
                for locale in LOCALES:
                    with self.subTest(locale=locale):
                        with tempfile.TemporaryDirectory(
                            prefix="dfroot-make-special-create-", dir=SUPPORTING_ROOTS[0][0]
                        ) as directory:
                            project = Path(directory)
                            self.prepare_project(project, password, alias, create_keystore=False)
                            result = self.run_make(
                                project,
                                {"SIGN_PASS": password, "SIGN_ALIAS": alias},
                                locale,
                            )
                            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                            self.assert_properties(project, password, alias, locale)

    def test_make_existing_keystore_unlocks_with_independently_established_credentials(self) -> None:
        for name, password, alias in MAKE_SPECIAL_VALUES:
            with self.subTest(value=name):
                for locale in LOCALES:
                    with self.subTest(locale=locale):
                        with tempfile.TemporaryDirectory(
                            prefix="dfroot-make-special-existing-", dir=SUPPORTING_ROOTS[0][0]
                        ) as directory:
                            project = Path(directory)
                            self.prepare_project(project, password, alias, create_keystore=False)
                            # Established without Make's create-local: this is the
                            # credential value Make must deliver unchanged when it
                            # unlocks an already-existing keystore.
                            write_fixture_keystore(
                                self.source_keystore, project / "keystore.jks", password, alias
                            )
                            result = self.run_make(
                                project,
                                {"SIGN_PASS": password, "SIGN_ALIAS": alias},
                                locale,
                            )
                            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                            self.assert_properties(project, password, alias, locale)


class GradleSigningConfigTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if not SUPPORTING_ROOTS:
            raise RuntimeError("At least one writable tmpfs/ext4 filesystem is required for Gradle tests")
        cls.java_home = Path(JAVA).parent.parent
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        cls.android_sdk = Path(sdk) if sdk else Path.home() / "Android" / "Sdk"

    def run_gradle(self, properties: dict[str, str] | None) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="dfroot-signing-gradle-", dir=SUPPORTING_ROOTS[0][0]) as directory:
            project = Path(directory)
            files = (
                "build.gradle.kts",
                "settings.gradle.kts",
                "gradle.properties",
                "gradlew",
                "gradlew.bat",
                "app/build.gradle.kts",
                "app/src/main/AndroidManifest.xml",
            )
            for relative in files:
                destination = project / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / relative, destination)
            shutil.copytree(ROOT / "gradle", project / "gradle")
            # PR #2 owns the packaged-loader verification task classes in
            # buildSrc; a hermetic Gradle fixture must include them too.
            shutil.copytree(
                ROOT / "buildSrc",
                project / "buildSrc",
                ignore=shutil.ignore_patterns("build", ".gradle"),
            )
            (project / "gradlew").chmod(0o755)
            (project / "local.properties").write_text(f"sdk.dir={self.android_sdk}\n", encoding="utf-8")
            if properties is not None:
                (project / "signing.properties").write_bytes(serialize_properties_fixture(properties))

            environment = os.environ.copy()
            environment["JAVA_HOME"] = str(self.java_home)
            environment["ANDROID_HOME"] = str(self.android_sdk)
            environment["ANDROID_SDK_ROOT"] = str(self.android_sdk)
            return subprocess.run(
                [str(ROOT / "gradlew"), "-p", str(project), ":app:tasks", "--all", "--console=plain", "--no-daemon"],
                cwd=ROOT,
                env=environment,
                text=True,
                encoding="utf-8",
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=180,
                check=False,
            )

    @staticmethod
    def complete_properties() -> dict[str, str]:
        return {
            "KEYSTORE_FILE": "../keystore.jks",
            "KEYSTORE_PASSWORD": "password",
            "KEY_ALIAS": "alias",
            "KEY_PASSWORD": "password",
        }

    def test_missing_signing_config_is_allowed(self) -> None:
        result = self.run_gradle(None)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("release builds will be unsigned", result.stdout + result.stderr)

    def test_partial_signing_config_is_rejected_with_all_missing_keys(self) -> None:
        result = self.run_gradle({"KEYSTORE_FILE": "../keystore.jks"})
        self.assertNotEqual(result.returncode, 0)
        output = result.stdout + result.stderr
        self.assertIn("missing required key(s)", output)
        for key in ("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"):
            self.assertIn(key, output)

    def test_empty_signing_value_is_rejected(self) -> None:
        properties = self.complete_properties()
        properties["KEY_ALIAS"] = ""
        result = self.run_gradle(properties)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("KEY_ALIAS", result.stdout + result.stderr)

    def test_whitespace_only_keystore_file_is_rejected(self) -> None:
        properties = self.complete_properties()
        properties["KEYSTORE_FILE"] = " \t\r\n\f "
        result = self.run_gradle(properties)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("KEYSTORE_FILE", result.stdout + result.stderr)

    def test_complete_signing_config_is_accepted(self) -> None:
        result = self.run_gradle(self.complete_properties())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_complete_config_accepts_whitespace_only_credentials(self) -> None:
        properties = self.complete_properties()
        properties["KEYSTORE_PASSWORD"] = " \t "
        properties["KEY_ALIAS"] = "\r\n\f "
        properties["KEY_PASSWORD"] = "   "
        result = self.run_gradle(properties)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_complete_config_accepts_non_bmp_credentials(self) -> None:
        properties = self.complete_properties()
        properties["KEYSTORE_PASSWORD"] = "  password-😀  "
        properties["KEY_ALIAS"] = "  alias-🔐-𝄞  "
        properties["KEY_PASSWORD"] = "password-😀"
        result = self.run_gradle(properties)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
