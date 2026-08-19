from __future__ import annotations

import unittest

from check_linux_abi import (
    forbidden_libstdcxx_symbols,
    glibc_versions,
    libcxx_abi_symbols,
    needed_libraries,
    version_tuple,
)


class LinuxAbiTests(unittest.TestCase):
    def test_extracts_sorts_and_deduplicates_versions(self) -> None:
        output = "GLIBC_2.4 GLIBC_2.35 GLIBC_2.9 GLIBC_2.35 GLIBCXX_3.4.29"
        self.assertEqual(glibc_versions(output), ["2.4", "2.9", "2.35"])

    def test_numeric_comparison_rejects_glibc_2_38(self) -> None:
        self.assertGreater(version_tuple("2.38"), version_tuple("2.35"))

    def test_numeric_comparison_accepts_glibc_2_35(self) -> None:
        self.assertLessEqual(version_tuple("2.35"), version_tuple("2.35"))

    def test_rejects_invalid_version(self) -> None:
        with self.assertRaisesRegex(ValueError, "invalid version"):
            version_tuple("2.35-rc1")

    def test_extracts_needed_cxx_runtime_libraries(self) -> None:
        output = """
 0x0000000000000001 (NEEDED) Shared library: [libc++.so.1]
 0x0000000000000001 (NEEDED) Shared library: [libc.so.6]
"""
        self.assertEqual(needed_libraries(output), ["libc++.so.1", "libc.so.6"])

    def test_detects_unresolved_libstdcxx_abi_symbol(self) -> None:
        output = """
  108: 0000000000000000     0 NOTYPE  GLOBAL DEFAULT  UND _ZTVNSt7__cxx1115basic_stringbufIcSt11char_traitsIcESaIcEEE
"""
        self.assertEqual(
            forbidden_libstdcxx_symbols(output),
            ["_ZTVNSt7__cxx1115basic_stringbufIcSt11char_traitsIcESaIcEEE"],
        )

    def test_ignores_normal_libcxx_undefined_symbols(self) -> None:
        output = "  108: 0000000000000000 0 NOTYPE GLOBAL DEFAULT UND _ZNSt3__16localeD1Ev"
        self.assertEqual(forbidden_libstdcxx_symbols(output), [])
        self.assertEqual(libcxx_abi_symbols(output), ["_ZNSt3__16localeD1Ev"])

    def test_libcxx_evidence_only_uses_undefined_symbols(self) -> None:
        output = """
  108: 0000000000000000 0 NOTYPE GLOBAL DEFAULT UND _ZNSt3__16localeD1Ev
  109: 0000000000001000 4 FUNC GLOBAL DEFAULT 12 _ZNSt3__16vectorIiNS_9allocatorIiEEE5clearEv
"""
        self.assertEqual(libcxx_abi_symbols(output), ["_ZNSt3__16localeD1Ev"])


if __name__ == "__main__":
    unittest.main()
