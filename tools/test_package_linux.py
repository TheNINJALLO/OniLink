from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import package_linux


class PackageLinuxTests(unittest.TestCase):
    def test_checked_linux_profile_is_production_consistent(self) -> None:
        root = Path(__file__).resolve().parents[1]
        profile = json.loads(
            (root / "OniBridge/profiles/1.26.44.3/linux-x86_64.json").read_text(
                encoding="utf-8"
            )
        )
        generated = json.loads(
            (
                root / "OniBridge/generated/bds/1.26.44.3/linux-x86_64/profile.json"
            ).read_text(encoding="utf-8")
        )

        self.assertEqual(profile, generated)
        self.assertEqual("production", profile["validation_status"])
        self.assertEqual([], profile["release_blockers"])
        self.assertTrue(all(profile["evidence"].values()))
        self.assertEqual("approved", profile["human_review_status"])
        self.assertEqual("passed", profile["live_test_status"])

        adapter = (
            root / "OniBridge/generated/bds/1.26.44.3/linux-x86_64/adapter.cpp"
        ).read_text(encoding="utf-8")
        self.assertIn("constexpr bool kProductionProfile = true;", adapter)

        for path in (
            root / "OniBridge/onibridge.example.toml",
            root / "examples/single-bds/onibridge.toml",
        ):
            self.assertIn(
                "allow_unreviewed_profile = false", path.read_text(encoding="utf-8")
            )

    def test_rejects_lock_profile_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "OniBridge/profiles/1.2.3").mkdir(parents=True)
            (root / "OniBridge/bds.lock.json").write_text(
                json.dumps(
                    {
                        "platforms": {
                            "linux-x86_64": {
                                "version": "1.2.3",
                                "executable_sha256": "locked",
                                "archive_sha256": "archive",
                            }
                        }
                    }
                ),
                encoding="utf-8",
            )
            (root / "OniBridge/profiles/1.2.3/linux-x86_64.json").write_text(
                json.dumps({"executable_sha256": "different"}), encoding="utf-8"
            )
            with mock.patch(
                "sys.argv",
                ["package_linux.py", "--version", "1.0.0", "--bds-version", "1.2.3"],
            ):
                previous = Path.cwd()
                try:
                    os.chdir(root)
                    with self.assertRaisesRegex(ValueError, "profile hash"):
                        package_linux.main()
                finally:
                    os.chdir(previous)


if __name__ == "__main__":
    unittest.main()
