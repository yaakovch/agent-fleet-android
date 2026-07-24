import base64
import importlib.util
import json
import os
import pathlib
import stat
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


def run(command, *, env=None, cwd=ROOT):
    merged = os.environ.copy()
    if env:
        merged.update(env)
    return subprocess.run(command, cwd=cwd, env=merged, text=True, capture_output=True)


def load_sequence_module():
    path = ROOT / "scripts/release/check-release-sequence.py"
    spec = importlib.util.spec_from_file_location("check_release_sequence", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AndroidGradleLauncherTest(unittest.TestCase):
    def fake_jdk(self, directory: pathlib.Path, major: int):
        binary = directory / "bin/java"
        binary.parent.mkdir(parents=True)
        binary.write_text(f"#!/bin/sh\necho 'openjdk version \"{major}.0.1\"' >&2\n", encoding="utf-8")
        binary.chmod(0o755)

    def test_selects_explicit_java_17_and_rejects_java_11(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            good = root / "jdk17"
            bad = root / "jdk11"
            self.fake_jdk(good, 17)
            self.fake_jdk(bad, 11)
            selected = run(
                ["scripts/debug/android-gradle.sh", "--print-java-home"],
                env={"AGENT_FLEET_JAVA_HOME": str(good), "JAVA_HOME": str(bad)},
            )
            self.assertEqual(0, selected.returncode, selected.stderr)
            self.assertEqual(str(good), selected.stdout.strip())
            rejected = run(
                ["scripts/debug/android-gradle.sh", "--print-java-home"],
                env={
                    "AGENT_FLEET_JAVA_HOME": str(bad),
                    "JAVA_HOME": str(bad),
                    "AGENT_FLEET_DEFAULT_JAVA_HOME": str(bad),
                },
            )
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("requires JDK 17", rejected.stderr)


class EmulatorBackendTest(unittest.TestCase):
    def test_windows_emulator_cold_starts_without_loading_or_saving_snapshots(self):
        text = (ROOT / "scripts/debug/android-check.sh").read_text(encoding="utf-8")
        self.assertIn("-no-snapshot-load -no-snapshot-save", text)

    def test_windows_backend_and_legacy_conflict(self):
        with tempfile.TemporaryDirectory() as temporary:
            sdk = pathlib.Path(temporary)
            for relative in ("platform-tools/adb.exe", "emulator/emulator.exe"):
                binary = sdk / relative
                binary.parent.mkdir(parents=True, exist_ok=True)
                binary.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
                binary.chmod(0o755)
            selected = run(
                ["scripts/debug/select-emulator-backend.sh"],
                env={
                    "AGENT_FLEET_EMULATOR_BACKEND": "windows",
                    "AGENT_FLEET_WINDOWS_ANDROID_SDK": str(sdk),
                    "AGENT_FLEET_USE_WINDOWS_AVD": "0",
                },
            )
            self.assertEqual(0, selected.returncode, selected.stderr)
            self.assertEqual("windows", selected.stdout.strip())
            conflict = run(
                ["scripts/debug/select-emulator-backend.sh"],
                env={"AGENT_FLEET_EMULATOR_BACKEND": "managed", "AGENT_FLEET_USE_WINDOWS_AVD": "1"},
            )
            self.assertNotEqual(0, conflict.returncode)
            self.assertIn("conflicting", conflict.stderr)

    def test_physical_serial_is_refused_before_device_work(self):
        result = run(["bash", "scripts/debug/android-check.sh", "fast"], env={"ANDROID_SERIAL": "R5CT-device"})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("physical device", result.stderr)

    def test_focused_mode_requires_an_app_test_class_before_device_work(self):
        result = run(["bash", "scripts/debug/android-check.sh", "focused"])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("AGENT_FLEET_INSTRUMENTATION_CLASS", result.stderr)


class InstrumentationResultTest(unittest.TestCase):
    def check(self, content):
        with tempfile.TemporaryDirectory() as temporary:
            report = pathlib.Path(temporary) / "instrumentation.txt"
            report.write_text(content, encoding="utf-8")
            return run(["scripts/debug/check-instrumentation-result.sh", str(report)])

    def test_accepts_only_a_complete_success_result(self):
        result = self.check(
            "INSTRUMENTATION_RESULT: stream=\n\n"
            "Time: 71.371\n\n"
            "OK (46 tests)\n\n"
            "INSTRUMENTATION_CODE: -1\n"
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_android_process_crash_even_when_instrumentation_exits_zero(self):
        result = self.check(
            "INSTRUMENTATION_STATUS_CODE: -2\n"
            "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n"
            "INSTRUMENTATION_CODE: 0\n"
        )
        self.assertNotEqual(0, result.returncode)

    def test_rejects_truncated_output_without_the_final_ok_record(self):
        result = self.check(
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_CODE: -1\n"
        )
        self.assertNotEqual(0, result.returncode)


class ReleasePreflightTest(unittest.TestCase):
    def fake_jdk(self, directory: pathlib.Path):
        bin_dir = directory / "bin"
        bin_dir.mkdir(parents=True)
        (bin_dir / "java").write_text("#!/bin/sh\necho 'openjdk version \"17.0.1\"' >&2\n", encoding="utf-8")
        (bin_dir / "keytool").write_text(
            "#!/bin/sh\n[ \"$AGENT_FLEET_STORE_PASSWORD\" = correct-password ]\n", encoding="utf-8"
        )
        for path in bin_dir.iterdir():
            path.chmod(0o755)

    def shell_preflight(self, repo, signing, java_home):
        script = f"""
set -euo pipefail
source '{ROOT}/scripts/release/release-common.sh'
agent_fleet_release_load_credentials
agent_fleet_release_check_keystore '{ROOT}'
"""
        return run(
            ["bash", "-c", script],
            env={
                "AGENT_FLEET_SIGNING_DIR": str(signing),
                "AGENT_FLEET_JAVA_HOME": str(java_home),
                "JAVA_HOME": str(java_home),
            },
            cwd=repo,
        )

    def test_credentials_are_permission_checked_and_verified(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            signing = root / "signing"
            signing.mkdir()
            (signing / "agent-fleet-release.jks").write_bytes(b"fixture")
            password = signing / "agent-fleet-release.pass"
            password.write_text("correct-password", encoding="utf-8")
            password.chmod(0o600)
            jdk = root / "jdk"
            self.fake_jdk(jdk)
            passed = self.shell_preflight(root, signing, jdk)
            self.assertEqual(0, passed.returncode, passed.stderr)
            password.chmod(0o644)
            rejected = self.shell_preflight(root, signing, jdk)
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("mode 600", rejected.stderr)

    def test_git_preflight_rejects_dirty_and_unpushed_revisions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            remote = root / "remote.git"
            checkout = root / "checkout"
            subprocess.run(["git", "init", "--bare", str(remote)], check=True, capture_output=True)
            subprocess.run(["git", "init", str(checkout)], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(checkout), "config", "user.email", "test@example.com"], check=True)
            subprocess.run(["git", "-C", str(checkout), "config", "user.name", "Test"], check=True)
            (checkout / "tracked").write_text("one", encoding="utf-8")
            subprocess.run(["git", "-C", str(checkout), "add", "tracked"], check=True)
            subprocess.run(["git", "-C", str(checkout), "commit", "-m", "initial"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(checkout), "remote", "add", "origin", str(remote)], check=True)
            subprocess.run(["git", "-C", str(checkout), "push", "-u", "origin", "HEAD"], check=True, capture_output=True)
            command = [
                "bash", "-c",
                f"source '{ROOT}/scripts/release/release-common.sh'; agent_fleet_release_check_git '{checkout}'",
            ]
            self.assertEqual(0, run(command).returncode)
            (checkout / "tracked").write_text("dirty", encoding="utf-8")
            dirty = run(command)
            self.assertNotEqual(0, dirty.returncode)
            self.assertIn("clean", dirty.stderr)
            subprocess.run(["git", "-C", str(checkout), "add", "tracked"], check=True)
            subprocess.run(["git", "-C", str(checkout), "commit", "-m", "ahead"], check=True, capture_output=True)
            ahead = run(command)
            self.assertNotEqual(0, ahead.returncode)
            self.assertIn("pushed", ahead.stderr)


class ReleaseSequenceAndOrderingTest(unittest.TestCase):
    def test_shared_sequence_uses_the_greater_app_or_runtime_value(self):
        module = load_sequence_module()
        payload = base64.urlsafe_b64encode(json.dumps({"sequence": 1081}).encode()).decode().rstrip("=")
        self.assertEqual(1082, module.required_floor({"versionCode": 1082}, {"payload": payload}))
        payload = base64.urlsafe_b64encode(json.dumps({"sequence": 1083}).encode()).decode().rstrip("=")
        self.assertEqual(1083, module.required_floor({"versionCode": 1082}, {"payload": payload}))

    def test_release_pipeline_has_one_build_and_holds_before_publication(self):
        text = (ROOT / "scripts/release/app-release.sh").read_text(encoding="utf-8")
        ordered = [
            "run_stage preflight",
            "run_stage full-api36",
            "run_stage release-lint",
            "run_stage signed-build",
            "run_stage release-verification",
            "run_stage publication",
            "run_stage served-verification",
        ]
        positions = [text.index(value) for value in ordered]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(1, text.count("run_stage signed-build"))
        self.assertLess(text.index('if [[ "$hold" == "1" ]]'), text.index("run_stage publication"))
        self.assertNotIn("AGENT_FLEET_STORE_PASSWORD", (ROOT / "scripts/release/app-release.sh").read_text())
        self.assertIn("if signed_build_passed and manifest_path.is_file():", text)

    def test_build_preflight_precedes_runtime_verification_and_gradle(self):
        text = (ROOT / "scripts/release/build-signed-release.sh").read_text(encoding="utf-8")
        credential = text.index("agent_fleet_release_check_keystore")
        runtime = text.index("verify-embedded-runtime.py")
        gradle = text.index("gradlew.bat app:assembleRelease")
        self.assertLess(credential, runtime)
        self.assertLess(runtime, gradle)


if __name__ == "__main__":
    unittest.main()
