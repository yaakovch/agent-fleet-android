import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class ManagedFullRunnerTest(unittest.TestCase):
    def run_full(self, failure=""):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        debug = root / "scripts/debug"
        debug.mkdir(parents=True)
        for name in ("android-check.sh", "select-emulator-backend.sh", "check-instrumentation-result.sh"):
            shutil.copy2(ROOT / "scripts/debug" / name, debug / name)
        gradle = debug / "android-gradle.sh"
        gradle.write_text("""#!/usr/bin/env python3
import os, pathlib, shutil, sys
if '--print-java-home' in sys.argv:
    print('/test-jdk')
    raise SystemExit(0)
root = pathlib.Path.cwd()
phase = 'full'
assert not any('testInstrumentationRunnerArguments.' in arg for arg in sys.argv)
with (root / 'calls.txt').open('a') as output:
    output.write(phase + '\\n')
results = root / 'app/build/outputs/androidTest-results/managedDevice'
shutil.rmtree(results, ignore_errors=True)
destination = results / 'debug/agentFleetPixel7Api36'
destination.mkdir(parents=True)
failure = os.environ.get('TEST_RUNNER_FAILURE', '')
if failure != 'missing-' + phase:
    count = 60
    (destination / 'adb.028.am.instrument.ok.txt').write_text(
        'INSTRUMENTATION_CODE: 0\\n' if failure == 'incomplete-' + phase
        else f'OK ({count} tests)\\nINSTRUMENTATION_CODE: -1\\n')
raise SystemExit(1 if failure == 'gradle-' + phase else 0)
""")
        gradle.chmod(0o755)
        environment = os.environ.copy()
        environment.update(AGENT_FLEET_EMULATOR_BACKEND="managed", TEST_RUNNER_FAILURE=failure)
        environment.pop("ANDROID_SERIAL", None)
        result = subprocess.run(["bash", str(debug / "android-check.sh"), "full"],
                                env=environment, capture_output=True, text=True)
        return root, result

    def test_full_runs_once_and_preserves_raw_report(self):
        root, result = self.run_full()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((root / "calls.txt").read_text(), "full\n")
        reports = list((root / "build/reports/agent-fleet/emulator").glob("*-full/results/**/adb.*.txt"))
        self.assertEqual(len(reports), 1)
        self.assertEqual(reports[0].read_text(), "OK (60 tests)\nINSTRUMENTATION_CODE: -1\n")

    def test_successful_gradle_cannot_hide_incomplete_instrumentation(self):
        root, result = self.run_full("incomplete-full")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((root / "calls.txt").read_text(), "full\n")
        self.assertIn("instrumentation did not finish successfully", result.stderr)

    def test_full_rejects_missing_report_or_gradle_failure(self):
        for failure in ("missing-full", "gradle-full"):
            with self.subTest(failure=failure):
                root, result = self.run_full(failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual((root / "calls.txt").read_text(), "full\n")


if __name__ == "__main__":
    unittest.main()
