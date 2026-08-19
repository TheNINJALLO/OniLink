from __future__ import annotations

import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import tenantctl


SETTINGS = {
    "panel_url": "https://panel.example.com",
    "egg_id": 12,
    "docker_image": "ghcr.io/ptero-eggs/yolks:java_21",
    "startup": "bash ./start-onilink.sh",
    "onilink_version": "v0.1.5",
    "bds_profile": "profile-test",
    "plans": {
        "starter": {
            "memory": 768,
            "disk": 1024,
            "cpu": 100,
            "max_players": 50,
        }
    },
}


class TenantCtlTests(unittest.TestCase):
    def plan(self) -> dict:
        return tenantctl.build_plan(
            copy.deepcopy(SETTINGS),
            tenant="acme",
            user_id=42,
            allocation_id=310,
            proxy=tenantctl.parse_endpoint("45.143.196.108:19140", "proxy"),
            backend=tenantctl.parse_endpoint("198.51.100.20:25571", "backend"),
            proxy_source_ip="45.143.196.108",
            plan_name="starter",
        )

    def test_plan_creates_one_isolated_server_and_hidden_runtime_secrets(self) -> None:
        plan = self.plan()
        request = plan["server_request"]

        self.assertEqual("dedicated-pterodactyl-server", plan["isolation"])
        self.assertEqual("onilink-tenant-acme", request["external_id"])
        self.assertEqual({"default": 310}, request["allocation"])
        self.assertEqual(0, request["feature_limits"]["allocations"])
        self.assertEqual("198.51.100.20", request["environment"]["BACKEND_HOST"])
        self.assertEqual("25571", request["environment"]["BACKEND_PORT"])
        self.assertEqual("50", request["environment"]["MAX_PLAYERS"])
        self.assertGreaterEqual(
            len(request["environment"]["ONILINK_DASHBOARD_SETUP_CODE"]), 16
        )
        secret = request["environment"]["ONIBRIDGE_FORWARDING_SECRET"]
        self.assertEqual(32, len(__import__("base64").b64decode(secret)))
        self.assertEqual(secret, plan["handoff"]["forwarding_secret"])
        self.assertIn(
            'trusted_proxy_cidrs = ["45.143.196.108/32"]',
            plan["handoff"]["onibridge_toml"],
        )

    def test_handoff_zip_contains_only_customer_setup_material(self) -> None:
        plan = self.plan()
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "acme.handoff.zip"
            tenantctl.write_handoff(archive, plan)
            with zipfile.ZipFile(archive) as source:
                self.assertEqual(
                    {
                        "CUSTOMER-START-HERE.txt",
                        "backend/default.key",
                        "backend/onibridge.toml",
                    },
                    set(source.namelist()),
                )
                start = source.read("CUSTOMER-START-HERE.txt").decode("utf-8")
                self.assertIn("uses one primary allocation", start)
                self.assertIn("45.143.196.108:19140", start)
                self.assertIn(plan["handoff"]["dashboard_setup_code"], start)

    def test_plan_validation_rejects_cross_tenant_allocations(self) -> None:
        plan = self.plan()
        plan["server_request"]["allocation"]["additional"] = [311]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            path.write_text(json.dumps(plan), encoding="utf-8")
            with self.assertRaisesRegex(tenantctl.TenantError, "exactly one"):
                tenantctl.read_plan(path)

    def test_plan_validation_rejects_mismatched_tenant_identity(self) -> None:
        plan = self.plan()
        plan["server_request"]["external_id"] = "onilink-tenant-someone-else"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            path.write_text(json.dumps(plan), encoding="utf-8")
            with self.assertRaisesRegex(tenantctl.TenantError, "external ID"):
                tenantctl.read_plan(path)

    def test_invalid_tenant_and_ambiguous_ipv6_are_rejected(self) -> None:
        with self.assertRaises(tenantctl.TenantError):
            tenantctl.validate_tenant("Other Customer")
        with self.assertRaisesRegex(tenantctl.TenantError, "host:port"):
            tenantctl.parse_endpoint("2001:db8::1:19132", "proxy")
        endpoint = tenantctl.parse_endpoint("[2001:db8::1]:19132", "proxy")
        self.assertEqual("[2001:db8::1]:19132", endpoint.display)
        with self.assertRaisesRegex(tenantctl.TenantError, "user ID"):
            tenantctl.build_plan(
                copy.deepcopy(SETTINGS),
                tenant="acme",
                user_id=0,
                allocation_id=310,
                proxy=tenantctl.parse_endpoint("45.143.196.108:19140", "proxy"),
                backend=tenantctl.parse_endpoint("198.51.100.20:25571", "backend"),
                proxy_source_ip="45.143.196.108",
                plan_name="starter",
            )


if __name__ == "__main__":
    unittest.main()
