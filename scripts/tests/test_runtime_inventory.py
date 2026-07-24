import json
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "scripts" / "runtime" / "inventory-runtime.py"


class RuntimeInventoryTest(unittest.TestCase):
    def test_every_root_package_and_android_service_has_an_owner(self):
        result = subprocess.run(
            [str(TOOL), "--root", str(ROOT)],
            check=True,
            capture_output=True,
            text=True,
        )
        value = json.loads(result.stdout)
        self.assertEqual(value["schemaVersion"], 1)
        self.assertEqual(value["packageCount"], 84)
        self.assertEqual(value["packagePayloadSize"], 33_189_712)
        self.assertEqual(len(value["rootPackages"]), 14)
        self.assertTrue(all(item["owner"] and item["reason"] for item in value["rootPackages"]))
        self.assertEqual(
            {item["name"] for item in value["services"]},
            {".app.TermuxService", ".app.fleet.LocalSuggestionService"},
        )
        self.assertTrue(all(item["sourceReferenceCount"] >= 2 for item in value["services"]))
        self.assertEqual(value["removalDecision"]["packagesRemoved"], [])
        self.assertEqual(value["removalDecision"]["processesRemoved"], [])


if __name__ == "__main__":
    unittest.main()
