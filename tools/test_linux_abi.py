from __future__ import annotations

import unittest

from check_linux_abi import glibc_versions, version_tuple


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


if __name__ == "__main__":
    unittest.main()
