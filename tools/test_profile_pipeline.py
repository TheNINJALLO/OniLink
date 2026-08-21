from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from profile_pipeline import (
    REQUIRED_GENERATED_FILES,
    REQUIRED_PRODUCTION_EVIDENCE,
    validate_checked,
)


class CheckedProfileTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.version = "1.2.3.4"
        self.lock = {"schema": 1, "paired_version": True, "platforms": {}}
        for platform, operating_system, status in (
            ("linux-x86_64", "linux", "production"),
            ("windows-x86_64", "windows", "candidate"),
        ):
            executable_hash = ("a" if operating_system == "linux" else "b") * 64
            artifact = {
                "version": self.version,
                "architecture": "x86_64",
                "executable_sha256": executable_hash,
                "executable_size": 1234,
            }
            self.lock["platforms"][platform] = artifact
            evidence = {name: True for name in REQUIRED_PRODUCTION_EVIDENCE}
            profile = {
                "schema": 1,
                "bds_version": self.version,
                "operating_system": operating_system,
                "architecture": "x86_64",
                "executable_sha256": executable_hash,
                "executable_size": 1234,
                "required_structure_sizes": {"Example": 8},
                "required_field_offsets": {"Example.value": 0},
                "validation_status": status,
                "release_blockers": [] if status == "production" else ["live_tested"],
                "evidence": evidence if status == "production" else {},
            }
            profile_dir = self.root / "OniBridge" / "profiles" / self.version
            generated = (
                self.root / "OniBridge" / "generated" / "bds" / self.version / platform
            )
            recipe = (
                self.root
                / "OniBridge"
                / "analysis-recipes"
                / self.version
                / f"{platform}.json"
            )
            profile_dir.mkdir(parents=True, exist_ok=True)
            generated.mkdir(parents=True, exist_ok=True)
            recipe.parent.mkdir(parents=True, exist_ok=True)
            recipe.write_text("{}\n", encoding="utf-8")
            encoded = json.dumps(profile)
            (profile_dir / f"{platform}.json").write_text(encoded, encoding="utf-8")
            (generated / "profile.json").write_text(encoded, encoding="utf-8")
            abi = {
                "schema": 1,
                "status": "verified",
                "platform": platform,
                "bds_version": self.version,
                "executable_sha256": executable_hash,
                "sizes": profile["required_structure_sizes"],
                "offsets": profile["required_field_offsets"],
            }
            (generated / "abi.json").write_text(json.dumps(abi), encoding="utf-8")
            profile_id = f"bds-{self.version}-{platform}-{executable_hash[:16]}"
            (generated / "adapter.cpp").write_text(
                f'kProfileId[] = "{profile_id}";\n'
                f'kExecutableHash[] = "{executable_hash}";\n'
                "kExecutableSize = 1234ULL;\n"
                f"kProductionProfile = {'true' if status == 'production' else 'false'};\n",
                encoding="utf-8",
            )
            for relative in REQUIRED_GENERATED_FILES:
                path = generated / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                if not path.exists():
                    path.write_text("evidence\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_beta_accepts_checked_candidate_and_production_profiles(self) -> None:
        validate_checked(self.lock, allow_candidate=True, root=self.root)

    def test_stable_rejects_candidate_profile(self) -> None:
        with self.assertRaisesRegex(ValueError, "candidate releases are not allowed"):
            validate_checked(self.lock, allow_candidate=False, root=self.root)

    def test_rejects_profile_that_no_longer_matches_the_lock(self) -> None:
        self.lock["platforms"]["linux-x86_64"]["executable_sha256"] = "c" * 64
        with self.assertRaisesRegex(ValueError, "profile identity does not match"):
            validate_checked(self.lock, allow_candidate=True, root=self.root)


if __name__ == "__main__":
    unittest.main()
