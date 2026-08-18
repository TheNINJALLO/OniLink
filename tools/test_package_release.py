from __future__ import annotations

import io
from pathlib import Path
import tempfile
import unittest
import zipfile

import package_release


class PackageReleaseTests(unittest.TestCase):
    def test_benign_archive_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "profiles.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("profiles/1/linux-x86_64.json", "{}")
            package_release.reject_forbidden(archive)

    def test_forbidden_file_in_nested_archive_is_rejected(self) -> None:
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as output:
            output.writestr("server/bedrock_server", b"not a real server")
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "outer.jar"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("nested/payload.zip", nested.getvalue())
            with self.assertRaisesRegex(ValueError, "forbidden BDS-owned"):
                package_release.reject_forbidden(archive)

    def test_forbidden_top_level_name_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "forbidden BDS-owned"):
            package_release.reject_forbidden(Path("bedrock_server.exe"))

    def test_release_tokens_cannot_escape_output_paths(self) -> None:
        package_release.validate_release_token("version", "1.2.3-rc.1")
        with self.assertRaisesRegex(ValueError, "unsafe characters"):
            package_release.validate_release_token("version", "../../escape")


if __name__ == "__main__":
    unittest.main()
