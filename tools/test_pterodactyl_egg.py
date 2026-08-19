from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
EGG_PATH = ROOT / "packaging/pterodactyl/egg-onilink.json"
UPDATER_PATH = ROOT / "packaging/pterodactyl/start-onilink.sh"


class PterodactylEggTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.egg = json.loads(EGG_PATH.read_text(encoding="utf-8"))

    def test_is_importable_ptdl_v2_shape(self) -> None:
        self.assertEqual("PTDL_v2", self.egg["meta"]["version"])
        self.assertEqual("OniLink Bedrock Proxy", self.egg["name"])
        self.assertIn("java_21", " ".join(self.egg["docker_images"].values()))
        self.assertEqual("bash ./start-onilink.sh", self.egg["startup"])
        self.assertEqual("stop", self.egg["config"]["stop"])

        files = json.loads(self.egg["config"]["files"])
        startup = json.loads(self.egg["config"]["startup"])
        logs = json.loads(self.egg["config"]["logs"])
        self.assertIn("config.properties", files)
        self.assertEqual("properties", files["config.properties"]["parser"])
        self.assertEqual(
            "{{server.build.default.port}}",
            files["config.properties"]["find"]["listener.port"],
        )
        self.assertEqual(
            "{{server.build.default.port}}",
            files["config.properties"]["find"]["dashboard.port"],
        )
        self.assertEqual(
            "0.0.0.0", files["config.properties"]["find"]["dashboard.host"]
        )
        self.assertEqual(
            "{{env.DASHBOARD_ENABLED}}",
            files["config.properties"]["find"]["dashboard.enabled"],
        )
        self.assertEqual(
            "{{env.ALLOWLIST_ENABLED}}",
            files["config.properties"]["find"]["allowlist.enabled"],
        )
        self.assertEqual("OniLink listening on", startup["done"])
        self.assertEqual({}, logs)

    def test_installer_verifies_bootstrap_before_install_and_preserves_config(
        self,
    ) -> None:
        installation = self.egg["scripts"]["installation"]
        script = installation["script"]
        self.assertEqual(
            "ghcr.io/ptero-eggs/installers:debian", installation["container"]
        )
        self.assertEqual("bash", installation["entrypoint"])
        self.assertIn("set -euo pipefail", script)
        self.assertIn("releases/download/${ONILINK_VERSION}", script)
        self.assertIn("download SHA256SUMS", script)
        self.assertIn("download start-onilink.sh", script)
        self.assertIn("sha256sum -c -", script)
        self.assertLess(
            script.index("sha256sum -c -"),
            script.index('mv OniLink.jar.download "${SERVER_JARFILE}"'),
        )
        self.assertIn('if [[ ! -f "${CONFIG_FILE}" ]]', script)
        self.assertIn("mkdir -p cache dashboard logs plugins resource-packs", script)
        self.assertNotIn("releases/latest", script)
        self.assertNotIn("bedrock_server", script)

    def test_runtime_updater_is_latest_checksum_verified_and_fail_safe(self) -> None:
        script = UPDATER_PATH.read_text(encoding="utf-8")
        self.assertIn("/releases/latest", script)
        self.assertNotIn("/releases?per_page=1", script)
        self.assertIn('download "${release_url}/SHA256SUMS"', script)
        self.assertIn('download "${release_url}/OniLink.jar"', script)
        self.assertIn('download "${release_url}/start-onilink.sh"', script)
        self.assertIn('download "${release_url}/onilink.properties.example"', script)
        self.assertIn('[[ "${downloaded_sha}" != "${expected_sha}" ]]', script)
        self.assertIn('"${server_jar}.previous"', script)
        self.assertIn("start-onilink.sh.previous", script)
        self.assertIn("preserved active ${config_file}", script)
        self.assertIn("keeping the installed JAR", script)
        self.assertIn("exec java -Xms128M -XX:MaxRAMPercentage=95.0", script)

    def test_embedded_installer_and_updater_are_valid_bash(self) -> None:
        bash = shutil.which("bash")
        if bash is None:
            self.skipTest("bash is unavailable")
        for name, script in (
            ("egg installer", self.egg["scripts"]["installation"]["script"]),
            ("startup updater", UPDATER_PATH.read_text(encoding="utf-8")),
        ):
            completed = subprocess.run(
                [bash, "-n"], input=script, text=True, capture_output=True, check=False
            )
            self.assertEqual(0, completed.returncode, f"{name}: {completed.stderr}")

    @unittest.skipIf(os.name == "nt", "POSIX updater integration test runs in Linux CI")
    def test_runtime_updater_replaces_verified_jar_and_retains_previous(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture = root / "fixture"
            commands = root / "bin"
            fixture.mkdir()
            commands.mkdir()
            old_jar = b"old verified jar"
            new_jar = b"new verified jar"
            new_updater = b"#!/usr/bin/env bash\n# newer updater fixture\n"
            new_example = b"allowlist.enabled=false\n"
            (root / "OniLink.jar").write_bytes(old_jar)
            (root / "config.properties").write_text(
                "listener.port=19132\n", encoding="utf-8"
            )
            (root / "onilink.properties.example").write_text(
                "old template\n", encoding="utf-8"
            )
            (fixture / "OniLink.jar").write_bytes(new_jar)
            (fixture / "start-onilink.sh").write_bytes(new_updater)
            (fixture / "onilink.properties.example").write_bytes(new_example)
            jar_sha = hashlib.sha256(new_jar).hexdigest()
            updater_sha = hashlib.sha256(new_updater).hexdigest()
            example_sha = hashlib.sha256(new_example).hexdigest()
            (fixture / "SHA256SUMS").write_text(
                f"{jar_sha}  OniLink.jar\n"
                f"{updater_sha}  start-onilink.sh\n"
                f"{example_sha}  onilink.properties.example\n",
                encoding="utf-8",
            )
            (fixture / "release.json").write_text(
                '{"tag_name":"v9.9.9"}\n', encoding="utf-8"
            )

            fake_curl = commands / "curl"
            fake_curl.write_text(
                """#!/bin/sh
url=
destination=
while [ \"$#\" -gt 0 ]; do
    case \"$1\" in
        --output) destination=$2; shift 2 ;;
        https://*) url=$1; shift ;;
        *) shift ;;
    esac
done
case \"$url\" in
    */releases/latest) source=$FIXTURE/release.json ;;
    */SHA256SUMS) source=$FIXTURE/SHA256SUMS ;;
    */OniLink.jar) source=$FIXTURE/OniLink.jar ;;
    */start-onilink.sh) source=$FIXTURE/start-onilink.sh ;;
    */onilink.properties.example) source=$FIXTURE/onilink.properties.example ;;
    *) exit 22 ;;
esac
cp \"$source\" \"$destination\"
""",
                encoding="utf-8",
            )
            fake_java = commands / "java"
            fake_java.write_text(
                """#!/bin/sh
printf '%s\\n' \"$*\" > \"$JAVA_LOG\"
""",
                encoding="utf-8",
            )
            fake_curl.chmod(0o755)
            fake_java.chmod(0o755)

            environment = os.environ.copy()
            environment["PATH"] = f"{commands}:{environment['PATH']}"
            environment["FIXTURE"] = str(fixture)
            environment["JAVA_LOG"] = str(root / "java.log")
            completed = subprocess.run(
                ["bash", str(UPDATER_PATH)],
                cwd=root,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(new_jar, (root / "OniLink.jar").read_bytes())
            self.assertEqual(old_jar, (root / "OniLink.jar.previous").read_bytes())
            self.assertEqual(
                "v9.9.9\n", (root / ".onilink-version").read_text(encoding="utf-8")
            )
            self.assertEqual(new_updater, (root / "start-onilink.sh").read_bytes())
            self.assertTrue((root / "start-onilink.sh.previous").is_file())
            self.assertEqual(
                new_example, (root / "onilink.properties.example").read_bytes()
            )
            self.assertEqual(
                b"old template\n",
                (root / "onilink.properties.example.previous").read_bytes(),
            )
            self.assertEqual(
                "listener.port=19132\n",
                (root / "config.properties").read_text(encoding="utf-8"),
            )
            self.assertIn(
                "-jar OniLink.jar config.properties",
                (root / "java.log").read_text(encoding="utf-8"),
            )

    @unittest.skipIf(os.name == "nt", "POSIX updater integration test runs in Linux CI")
    def test_runtime_updater_failed_download_starts_existing_jar(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            commands = root / "bin"
            commands.mkdir()
            installed_jar = b"last known good jar"
            (root / "OniLink.jar").write_bytes(installed_jar)
            (root / "config.properties").write_text(
                "listener.port=19132\n", encoding="utf-8"
            )

            fake_curl = commands / "curl"
            fake_curl.write_text("#!/bin/sh\nexit 22\n", encoding="utf-8")
            fake_java = commands / "java"
            fake_java.write_text(
                """#!/bin/sh
printf '%s\\n' \"$*\" > \"$JAVA_LOG\"
""",
                encoding="utf-8",
            )
            fake_curl.chmod(0o755)
            fake_java.chmod(0o755)

            environment = os.environ.copy()
            environment["PATH"] = f"{commands}:{environment['PATH']}"
            environment["JAVA_LOG"] = str(root / "java.log")
            completed = subprocess.run(
                ["bash", str(UPDATER_PATH)],
                cwd=root,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(installed_jar, (root / "OniLink.jar").read_bytes())
            self.assertFalse((root / "OniLink.jar.previous").exists())
            self.assertIn("keeping the installed JAR", completed.stdout)
            self.assertIn(
                "-jar OniLink.jar config.properties",
                (root / "java.log").read_text(encoding="utf-8"),
            )

    def test_variables_are_unique_and_secrets_are_admin_only(self) -> None:
        variables = {item["env_variable"]: item for item in self.egg["variables"]}
        self.assertEqual(len(variables), len(self.egg["variables"]))
        for required in (
            "ONILINK_VERSION",
            "SERVER_JARFILE",
            "CONFIG_FILE",
            "BACKEND_HOST",
            "BACKEND_PORT",
            "DASHBOARD_ENABLED",
            "ONILINK_DASHBOARD_SETUP_CODE",
            "ALLOWLIST_ENABLED",
            "ONIBRIDGE_FORWARDING_SECRET",
        ):
            self.assertIn(required, variables)

        self.assertEqual("v0.1.6", variables["ONILINK_VERSION"]["default_value"])
        self.assertEqual("true", variables["DASHBOARD_ENABLED"]["default_value"])
        self.assertEqual("false", variables["ALLOWLIST_ENABLED"]["default_value"])
        for name in (
            "ONILINK_DASHBOARD_SETUP_CODE",
            "ONIBRIDGE_FORWARDING_SECRET",
            "ONIBRIDGE_SURVIVAL_SECRET",
            "ONIBRIDGE_JAVA_SECRET",
        ):
            variable = variables[name]
            self.assertFalse(variable["user_viewable"])
            self.assertFalse(variable["user_editable"])
            self.assertEqual("", variable["default_value"])


if __name__ == "__main__":
    unittest.main()
