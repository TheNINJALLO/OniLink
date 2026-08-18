from __future__ import annotations

import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import URLError
import zipfile

from bdsctl.archive import extract_safely, inspect_executable, safe_member_name, sha256_file, validate_zip
from bdsctl.errors import MetadataError, SecurityError, ValidationError
from bdsctl.metadata import parse_metadata
from bdsctl.model import Artifact, LockFile
from bdsctl.store import acquire, import_local, require_eula, verify_artifact
from bdsctl.transport import HttpTransport, Response, validate_url


LINUX_URL = "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-1.21.100.1.zip"
WINDOWS_URL = "https://www.minecraft.net/bedrockdedicatedserver/bin-win/bedrock-server-1.21.100.1.zip"


def metadata(*entries: tuple[str, str]) -> bytes:
    return json.dumps({"result": {"links": [
        {"downloadType": kind, "downloadUrl": url} for kind, url in entries
    ]}}).encode()


def elf(machine: int = 62) -> bytes:
    value = bytearray(64)
    value[:4] = b"\x7fELF"
    value[4] = 2
    value[5] = 1
    value[18:20] = machine.to_bytes(2, "little")
    return bytes(value)


def pe(machine: int = 0x8664, magic: int = 0x20B) -> bytes:
    value = bytearray(512)
    value[:2] = b"MZ"
    struct.pack_into("<I", value, 0x3C, 0x80)
    value[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<H", value, 0x84, machine)
    struct.pack_into("<H", value, 0x98, magic)
    return bytes(value)


def make_zip(path: Path, platform: str, executable_data: bytes | None = None, extra: dict[str, bytes] | None = None) -> None:
    name = "bedrock_server" if platform == "linux-x86_64" else "bedrock_server.exe"
    executable_data = executable_data if executable_data is not None else (elf() if platform.startswith("linux") else pe())
    files = {
        name: executable_data,
        "server.properties": b"server-name=fixture\n",
        "allowlist.json": b"[]\n",
        "permissions.json": b"[]\n",
    }
    files.update(extra or {})
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for filename, data in files.items():
            archive.writestr(filename, data)


class MetadataTests(unittest.TestCase):
    def test_valid_official_metadata(self):
        lock = parse_metadata(metadata(
            ("serverBedrockLinux", LINUX_URL),
            ("serverBedrockWindows", WINDOWS_URL),
        ), "application/json; charset=utf-8", "stable", ("linux-x86_64", "windows-x86_64"))
        self.assertTrue(lock.paired_version)
        self.assertEqual(lock.platforms["linux-x86_64"].version, "1.21.100.1")

    def test_missing_linux_entry(self):
        with self.assertRaisesRegex(MetadataError, "serverBedrockLinux"):
            parse_metadata(metadata(("serverBedrockWindows", WINDOWS_URL)), "application/json", "stable", ("linux-x86_64", "windows-x86_64"))

    def test_missing_windows_entry(self):
        with self.assertRaisesRegex(MetadataError, "serverBedrockWindows"):
            parse_metadata(metadata(("serverBedrockLinux", LINUX_URL)), "application/json", "stable", ("linux-x86_64", "windows-x86_64"))

    def test_duplicate_entry(self):
        with self.assertRaisesRegex(MetadataError, "duplicate"):
            parse_metadata(metadata(
                ("serverBedrockLinux", LINUX_URL), ("serverBedrockLinux", LINUX_URL)
            ), "application/json", "stable", ("linux-x86_64",))

    def test_malformed_json(self):
        with self.assertRaises(MetadataError):
            parse_metadata(b"{not json", "application/json", "stable", ("linux-x86_64",))

    def test_wrong_content_type(self):
        with self.assertRaisesRegex(MetadataError, "content type"):
            parse_metadata(b"{}", "text/html", "stable", ("linux-x86_64",))

    def test_linux_windows_version_mismatch(self):
        windows = WINDOWS_URL.replace("1.21.100.1", "1.21.101.2")
        lock = parse_metadata(metadata(
            ("serverBedrockLinux", LINUX_URL), ("serverBedrockWindows", windows)
        ), "application/json", "stable", ("linux-x86_64", "windows-x86_64"))
        self.assertFalse(lock.paired_version)

    def test_stable_preview_separation(self):
        preview = metadata(
            ("serverBedrockPreviewLinux", LINUX_URL),
            ("serverBedrockLinux", LINUX_URL),
        )
        stable = parse_metadata(preview, "application/json", "stable", ("linux-x86_64",))
        candidate = parse_metadata(preview, "application/json", "preview", ("linux-x86_64",))
        self.assertEqual(stable.platforms["linux-x86_64"].download_type, "serverBedrockLinux")
        self.assertEqual(candidate.platforms["linux-x86_64"].download_type, "serverBedrockPreviewLinux")


class TransportTests(unittest.TestCase):
    def test_redirect_to_unapproved_host(self):
        with self.assertRaises(SecurityError):
            validate_url("https://attacker.invalid/payload.zip")

    def test_timeout_retries_then_fails(self):
        transport = HttpTransport(retries=2)
        with patch.object(transport, "_open", side_effect=URLError(TimeoutError("timed out"))):
            with self.assertRaisesRegex(ValidationError, "transient retries"):
                transport.get_bytes(LINUX_URL, 100)


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_invalid_zip(self):
        path = self.root / "bad.zip"
        path.write_bytes(b"not a zip")
        with self.assertRaisesRegex(ValidationError, "invalid BDS ZIP"):
            validate_zip(path, "linux-x86_64")

    def test_html_masquerading_as_zip(self):
        artifact = Artifact("serverBedrockLinux", "1.21.100.1", "bds.zip", LINUX_URL, "bedrock_server")
        lock = LockFile(1, "stable", "now", True, {"linux-x86_64": artifact})
        fake = unittest.mock.Mock()
        fake.get_bytes.return_value = Response(b"<!doctype html><title>error</title>", "text/html", LINUX_URL)
        with patch.dict(os.environ, {"MINECRAFT_EULA_ACCEPTED": "TRUE"}, clear=True):
            with self.assertRaisesRegex(ValidationError, "HTML"):
                acquire(lock, self.root / "cache", fake)
        self.assertFalse(any((self.root / "cache").rglob("*.partial")))

    def test_path_traversal(self):
        with self.assertRaises(SecurityError):
            safe_member_name("../outside")

    def test_absolute_path(self):
        with self.assertRaises(SecurityError):
            safe_member_name("/outside")

    def test_symlink_member(self):
        path = self.root / "link.zip"
        with zipfile.ZipFile(path, "w") as archive:
            info = zipfile.ZipInfo("bedrock_server")
            info.create_system = 3
            info.external_attr = (0o120777 << 16)
            archive.writestr(info, "elsewhere")
            for name in ("server.properties", "allowlist.json", "permissions.json"):
                archive.writestr(name, "[]")
        with self.assertRaisesRegex(SecurityError, "symlink"):
            validate_zip(path, "linux-x86_64")

    def test_executable_missing(self):
        path = self.root / "missing.zip"
        make_zip(path, "linux-x86_64")
        with zipfile.ZipFile(path, "w") as archive:
            for name in ("server.properties", "allowlist.json", "permissions.json"):
                archive.writestr(name, "[]")
        with self.assertRaisesRegex(ValidationError, "bedrock_server"):
            validate_zip(path, "linux-x86_64")

    def test_wrong_architecture(self):
        path = self.root / "wrong"
        path.write_bytes(elf(machine=3))
        with self.assertRaisesRegex(ValidationError, "x86_64"):
            inspect_executable(path, "linux-x86_64")

    def test_valid_pe(self):
        path = self.root / "server.exe"
        path.write_bytes(pe())
        self.assertEqual(inspect_executable(path, "windows-x86_64").file_format, "PE32+")

    def test_extract_will_not_overwrite(self):
        path = self.root / "valid.zip"
        make_zip(path, "linux-x86_64")
        destination = self.root / "existing"
        destination.mkdir()
        with self.assertRaisesRegex(SecurityError, "overwrite"):
            extract_safely(path, destination)


class AcquisitionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def artifact(self) -> Artifact:
        return Artifact("serverBedrockLinux", "1.21.100.1", "bds.zip", LINUX_URL, "bedrock_server")

    def test_eula_variable_missing(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(SecurityError, "MINECRAFT_EULA_ACCEPTED=TRUE"):
                require_eula()

    def test_oversized_archive(self):
        artifact = self.artifact()
        lock = LockFile(1, "stable", "now", True, {"linux-x86_64": artifact})
        fake = unittest.mock.Mock()
        fake.get_bytes.side_effect = ValidationError("response exceeds 12 byte limit")
        with patch.dict(os.environ, {"MINECRAFT_EULA_ACCEPTED": "TRUE"}, clear=True):
            with self.assertRaisesRegex(ValidationError, "exceeds"):
                acquire(lock, self.root / "cache", fake, max_size=12)

    def test_partial_download_is_cleaned(self):
        artifact = self.artifact()
        lock = LockFile(1, "stable", "now", True, {"linux-x86_64": artifact})
        fake = unittest.mock.Mock()
        fake.get_bytes.return_value = Response(b"PK truncated", "application/zip", LINUX_URL)
        with patch.dict(os.environ, {"MINECRAFT_EULA_ACCEPTED": "TRUE"}, clear=True):
            with self.assertRaises(ValidationError):
                acquire(lock, self.root / "cache", fake)
        self.assertFalse((self.root / "cache" / "bds" / artifact.version / "linux-x86_64").exists())

    def test_hash_mismatch(self):
        source = self.root / "source.zip"
        make_zip(source, "linux-x86_64")
        artifact = self.artifact()
        artifact.archive_sha256 = "0" * 64
        lock = LockFile(1, "stable", "now", True, {"linux-x86_64": artifact})
        fake = unittest.mock.Mock()
        fake.get_bytes.return_value = Response(source.read_bytes(), "application/zip", LINUX_URL)
        with patch.dict(os.environ, {"MINECRAFT_EULA_ACCEPTED": "TRUE"}, clear=True):
            with self.assertRaisesRegex(ValidationError, "SHA-256 mismatch"):
                acquire(lock, self.root / "cache", fake)

    def test_valid_archive_acquire_and_verify(self):
        source = self.root / "source.zip"
        make_zip(source, "linux-x86_64")
        artifact = self.artifact()
        lock = LockFile(1, "stable", "now", True, {"linux-x86_64": artifact})
        fake = unittest.mock.Mock()
        fake.get_bytes.return_value = Response(source.read_bytes(), "application/zip", LINUX_URL)
        with patch.dict(os.environ, {"MINECRAFT_EULA_ACCEPTED": "TRUE"}, clear=True):
            acquire(lock, self.root / "cache", fake)
        self.assertEqual(artifact.archive_sha256, sha256_file(source))
        self.assertEqual(verify_artifact(artifact, "linux-x86_64", self.root / "cache")["status"], "verified")

    def test_local_dual_platform_import(self):
        linux_source = self.root / "linux.zip"
        windows_source = self.root / "windows.zip"
        make_zip(linux_source, "linux-x86_64")
        make_zip(windows_source, "windows-x86_64")
        linux = self.artifact()
        windows = Artifact(
            "serverBedrockWindows", "1.21.100.1", "bds.zip", WINDOWS_URL, "bedrock_server.exe")
        lock = LockFile(1, "stable", "now", True, {
            "linux-x86_64": linux,
            "windows-x86_64": windows,
        })
        with patch.dict(os.environ, {"MINECRAFT_EULA_ACCEPTED": "TRUE"}, clear=True):
            import_local(lock, self.root / "cache", {
                "linux-x86_64": linux_source,
                "windows-x86_64": windows_source,
            })
        self.assertEqual(linux.archive_sha256, sha256_file(linux_source))
        self.assertEqual(windows.archive_sha256, sha256_file(windows_source))
        metadata_value = json.loads((
            self.root / "cache" / "bds" / "1.21.100.1" / "windows-x86_64" / "metadata.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual(metadata_value["acquisition_method"], "user-supplied-official-page-download")

    def test_local_import_requires_exact_platform_set(self):
        source = self.root / "linux.zip"
        make_zip(source, "linux-x86_64")
        artifact = self.artifact()
        lock = LockFile(1, "stable", "now", True, {"linux-x86_64": artifact})
        with patch.dict(os.environ, {"MINECRAFT_EULA_ACCEPTED": "TRUE"}, clear=True):
            with self.assertRaisesRegex(ValidationError, "exactly match"):
                import_local(lock, self.root / "cache", {})


if __name__ == "__main__":
    unittest.main()
