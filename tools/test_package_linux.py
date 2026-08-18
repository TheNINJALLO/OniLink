from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import package_linux


class PackageLinuxTests(unittest.TestCase):
    def test_rejects_lock_profile_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "OniBridge/profiles/1.2.3").mkdir(parents=True)
            (root / "OniBridge/bds.lock.json").write_text(json.dumps({
                "platforms": {"linux-x86_64": {
                    "version": "1.2.3", "executable_sha256": "locked", "archive_sha256": "archive"
                }}
            }), encoding="utf-8")
            (root / "OniBridge/profiles/1.2.3/linux-x86_64.json").write_text(json.dumps({
                "executable_sha256": "different"
            }), encoding="utf-8")
            with mock.patch("sys.argv", ["package_linux.py", "--version", "1.0.0", "--bds-version", "1.2.3"]):
                previous = Path.cwd()
                try:
                    os.chdir(root)
                    with self.assertRaisesRegex(ValueError, "profile hash"):
                        package_linux.main()
                finally:
                    os.chdir(previous)


if __name__ == "__main__":
    unittest.main()
