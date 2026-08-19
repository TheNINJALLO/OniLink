from __future__ import annotations

import copy
import json
from pathlib import Path
import struct
import tempfile
import unittest

from sdkgen.binary import BinaryError, load_image
from sdkgen.headers import generate_minimal_header
from sdkgen.profile import (
    PRODUCTION_GATES,
    ProfileError,
    create_candidate,
    validate_profile,
)


SIGNATURE = bytes.fromhex("55 48 89 e5 48 83 ec 20")


def elf_fixture(
    code: bytes = SIGNATURE, machine: int = 62, executable: bool = True
) -> bytes:
    data = bytearray(0x300)
    data[:4] = b"\x7fELF"
    data[4] = 2
    data[5] = 1
    struct.pack_into("<H", data, 16, 2)
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 40, 0x200)
    struct.pack_into("<H", data, 52, 64)
    struct.pack_into("<H", data, 58, 64)
    struct.pack_into("<H", data, 60, 3)
    struct.pack_into("<H", data, 62, 2)
    data[0x100 : 0x100 + len(code)] = code
    names = b"\0.text\0.shstrtab\0"
    data[0x180 : 0x180 + len(names)] = names
    struct.pack_into(
        "<IIQQQQIIQQ",
        data,
        0x240,
        1,
        1,
        0x6 if executable else 0x2,
        0x1000,
        0x100,
        0x40,
        0,
        0,
        16,
        0,
    )
    struct.pack_into(
        "<IIQQQQIIQQ", data, 0x280, 7, 3, 0, 0, 0x180, len(names), 0, 0, 1, 0
    )
    return bytes(data)


def pe_fixture(
    code: bytes = SIGNATURE, machine: int = 0x8664, executable: bool = True
) -> bytes:
    data = bytearray(0x300)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 0x3C, 0x80)
    data[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<HHIIIHH", data, 0x84, machine, 1, 0, 0, 0, 0xF0, 0x2022)
    struct.pack_into("<H", data, 0x98, 0x20B)
    struct.pack_into("<Q", data, 0xB0, 0x140000000)
    section = 0x188
    data[section : section + 8] = b".text\0\0\0"
    struct.pack_into(
        "<IIIIIIHHI",
        data,
        section + 8,
        0x100,
        0x1000,
        0x100,
        0x200,
        0,
        0,
        0,
        0,
        0x60000020 if executable else 0x40000040,
    )
    data[0x200 : 0x200 + len(code)] = code
    return bytes(data)


class BinaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name: str, value: bytes) -> Path:
        path = self.root / name
        path.write_bytes(value)
        return path

    def test_valid_elf(self):
        image = load_image(self.write("server", elf_fixture()))
        self.assertEqual((image.file_format, image.abi), ("ELF64", "sysv-amd64"))

    def test_valid_pe(self):
        image = load_image(self.write("server.exe", pe_fixture()))
        self.assertEqual((image.file_format, image.abi), ("PE32+", "microsoft-x64"))

    def test_wrong_architecture(self):
        with self.assertRaisesRegex(BinaryError, "architecture"):
            load_image(self.write("server", elf_fixture(machine=3)))

    def test_truncated_executable(self):
        with self.assertRaisesRegex(BinaryError, "truncated"):
            load_image(self.write("server", b"\x7fELF"))

    def test_missing_executable_section(self):
        with self.assertRaisesRegex(BinaryError, "no executable section"):
            load_image(self.write("server", elf_fixture(executable=False)))


class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.binary = self.root / "server"
        self.binary.write_bytes(elf_fixture())
        self.evidence = {gate: True for gate in PRODUCTION_GATES}

    def tearDown(self):
        self.temp.cleanup()

    def candidate(
        self, signature: str = "55 48 89 E5 48 83 EC 20", patch_length: int = 8
    ):
        return create_candidate(
            self.binary,
            "fixture",
            "linux-x86_64",
            signature,
            patch_length,
            "test",
            self.evidence,
        )

    def test_unique_signature_production(self):
        profile = self.candidate()
        self.assertEqual(profile["validation_status"], "production")
        validate_profile(profile, self.binary)

    def test_duplicate_signature(self):
        self.binary.write_bytes(elf_fixture(SIGNATURE + b"\x90" * 8 + SIGNATURE))
        profile = self.candidate()
        self.assertEqual(profile["signature_match_count"], 2)
        self.assertEqual(profile["validation_status"], "candidate")

    def test_no_signature_match(self):
        profile = self.candidate("DE AD BE EF")
        self.assertEqual(profile["signature_match_count"], 0)

    def test_target_outside_executable_section(self):
        self.binary.write_bytes(elf_fixture(SIGNATURE, executable=False))
        with self.assertRaisesRegex(BinaryError, "no executable section"):
            self.candidate()

    def test_invalid_instruction_boundary_is_release_blocker(self):
        evidence = dict(self.evidence)
        evidence["instruction_decode_validated"] = False
        profile = create_candidate(
            self.binary, "fixture", "linux-x86_64", "55 48 89 E5", 8, "test", evidence
        )
        with self.assertRaisesRegex(ProfileError, "instruction_decode_validated"):
            validate_profile(profile, self.binary)

    def test_unsafe_patch_length(self):
        profile = self.candidate(patch_length=4)
        self.assertIn("minimum patch length", profile["release_blockers"][0])

    def test_unsupported_abi(self):
        with self.assertRaisesRegex(ProfileError, "does not match"):
            create_candidate(
                self.binary,
                "fixture",
                "windows-x86_64",
                "55 48",
                5,
                "test",
                self.evidence,
            )

    def test_expected_byte_mismatch(self):
        profile = self.candidate()
        profile["expected_prologue_bytes"] = "90" * 8
        with self.assertRaisesRegex(ProfileError, "expected target bytes"):
            validate_profile(profile, self.binary)

    def test_stale_previous_profile(self):
        profile = self.candidate()
        changed = bytearray(self.binary.read_bytes())
        changed[-1] ^= 1
        self.binary.write_bytes(changed)
        with self.assertRaisesRegex(ProfileError, "hash or size"):
            validate_profile(profile, self.binary)

    def test_cross_platform_offset_leakage_prevented(self):
        profile = self.candidate()
        windows = self.root / "server.exe"
        windows.write_bytes(pe_fixture())
        with self.assertRaisesRegex(ProfileError, "hash or size|architecture or ABI"):
            validate_profile(profile, windows)

    def test_structure_size_mismatch_blocks_header(self):
        abi = {"status": "verified", "sizes": {}, "alignments": {}, "offsets": {}}
        with self.assertRaisesRegex(ProfileError, "size or alignment"):
            generate_minimal_header(abi, self.root / "generated.hpp")

    def test_field_offset_mismatch_blocks_header(self):
        abi = {
            "status": "verified",
            "sizes": {"PlayerAuthenticationInfo": 64},
            "alignments": {"PlayerAuthenticationInfo": 8},
            "offsets": {},
        }
        with self.assertRaisesRegex(ProfileError, "xuid offset"):
            generate_minimal_header(abi, self.root / "generated.hpp")


if __name__ == "__main__":
    unittest.main()
