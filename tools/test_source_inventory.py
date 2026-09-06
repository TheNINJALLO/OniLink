from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from source_inventory import source_files


class SourceInventoryTests(unittest.TestCase):
    def test_excluded_trees_are_pruned_before_their_files_are_inspected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "src").mkdir()
            (root / "src/main.java").write_text("source")
            (root / "node_modules").mkdir()
            (root / "node_modules/locked.js").write_text("dependency")
            output = root / "inventory.txt"
            output.write_text("previous inventory")
            original = Path.resolve

            def resolve(path, *args, **kwargs):
                if "node_modules" in path.parts:
                    raise AssertionError("inspected an excluded dependency")
                return original(path, *args, **kwargs)

            with patch.object(Path, "resolve", resolve):
                self.assertEqual([root / "src/main.java"], source_files(root, output))


if __name__ == "__main__":
    unittest.main()
