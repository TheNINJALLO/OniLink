from pathlib import Path
import tempfile
import unittest

from check_protocol_upstreams import audit, git, inspect


class ProtocolUpstreamTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="onilink-upstream-test-")
        self.addCleanup(self.temp.cleanup)
        self.checkout = Path(self.temp.name)
        git(self.checkout, "init", "-q")
        (self.checkout / "protocol").mkdir()
        (self.checkout / "protocol/packet.py").write_text("baseline\n")
        (self.checkout / "protocol/removed.py").write_text("removed\n")
        self.commit()
        self.source = {
            "repository": "example/protocol",
            "reviewedCommit": git(self.checkout, "rev-parse", "HEAD"),
            "paths": ["protocol"],
        }

    def commit(self):
        git(self.checkout, "add", ".")
        git(
            self.checkout,
            "-c",
            "user.name=OniLink fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "-c",
            "commit.gpgsign=false",
            "commit",
            "-qm",
            "Fixture",
        )

    def test_unchanged_and_documentation_only_commits_are_current(self):
        self.assertEqual("current", inspect(self.checkout, self.source)["status"])
        (self.checkout / "README.md").write_text("Only documentation changed\n")
        self.commit()
        report = inspect(self.checkout, self.source)
        self.assertEqual("current", report["status"])
        self.assertNotEqual(report["reviewedCommit"], report["observedCommit"])

    def test_added_changed_and_deleted_schemas_require_review(self):
        (self.checkout / "protocol/packet.py").write_text("new wire field\n")
        (self.checkout / "protocol/added packet.py").write_text("new packet\n")
        (self.checkout / "protocol/removed.py").unlink()
        self.commit()
        report = inspect(self.checkout, self.source)
        self.assertEqual("review-required", report["status"])
        self.assertEqual(
            [
                {"status": "A", "path": "protocol/added packet.py"},
                {"status": "M", "path": "protocol/packet.py"},
                {"status": "D", "path": "protocol/removed.py"},
            ],
            report["changes"],
        )

    def test_missing_history_is_an_error_instead_of_a_green_check(self):
        manifest = {
            "schemaVersion": 1,
            "targetClient": "fixture",
            "sources": {"fixture": self.source | {"reviewedCommit": "0" * 40}},
        }
        report = audit(manifest, {"fixture": self.checkout})
        self.assertTrue(report["reviewRequired"])
        self.assertEqual("error", report["sources"]["fixture"]["status"])


if __name__ == "__main__":
    unittest.main()
