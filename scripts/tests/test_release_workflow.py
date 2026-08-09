import base64
import hashlib
import importlib.util
import json
import os
import pathlib
import shutil
import stat
import subprocess
import tempfile
import unittest
import urllib.error
from unittest import mock


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


def load_private_https_module():
    path = ROOT / "scripts/release/private_https.py"
    spec = importlib.util.spec_from_file_location("private_https", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_release_module(name, relative):
    path = ROOT / "scripts/release" / relative
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_executable(path: pathlib.Path, content: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def read_receipt(path: pathlib.Path):
    return json.loads(path.read_text(encoding="utf-8"))


PRODUCTION_CERTIFICATE = "c5b2539c028ae1dc539ded3113cc3c735f60145bbfc5e4a8254056452db5e873"


def write_release(directory: pathlib.Path, version: str, code: int, marker: bytes):
    directory.mkdir(parents=True, exist_ok=True)
    base = "https://release.example/fleet/latest"
    artifacts = []
    for abi in ("arm64-v8a", "universal"):
        name = f"agent-fleet-{version}-{abi}.apk"
        payload = marker + b"-" + abi.encode()
        (directory / name).write_bytes(payload)
        artifacts.append(
            {
                "abi": abi,
                "apkUrl": f"{base}/{name}",
                "apkSha256": hashlib.sha256(payload).hexdigest(),
                "size": len(payload),
            }
        )
    primary = artifacts[0]
    manifest = {
        "schemaVersion": 1,
        "applicationId": "com.yaakovch.fleet",
        "versionCode": code,
        "versionName": version,
        "apkUrl": primary["apkUrl"],
        "apkSha256": primary["apkSha256"],
        "certificateSha256": PRODUCTION_CERTIFICATE,
        "size": primary["size"],
        "createdAt": "2026-08-09T12:00:00Z",
        "gitCommit": "a" * 40,
        "artifacts": artifacts,
    }
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    sums = []
    for path in sorted([*(directory.glob("*.apk")), directory / "manifest.json"]):
        sums.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}")
    (directory / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="utf-8")
    candidate = load_release_module("release_candidate_fixture", "release_candidate.py")
    candidate.create_proof(directory, PRODUCTION_CERTIFICATE)
    return manifest


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

    def test_runner_collapses_retained_system_panels_after_emulator_validation(self):
        text = (ROOT / "scripts/debug/android-check.sh").read_text(encoding="utf-8")
        validation = text.index('ro.kernel.qemu')
        collapse = text.index('shell cmd statusbar collapse')
        instrumentation = text.index('shell am instrument')
        self.assertLess(validation, collapse)
        self.assertLess(collapse, instrumentation)

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


class ReleaseHttpsPolicyTest(unittest.TestCase):
    def test_direct_fetch_refuses_all_supported_redirect_statuses(self):
        module = load_private_https_module()
        handler = module.RejectRedirects()
        for code in (301, 302, 303, 307, 308):
            with self.subTest(code=code), self.assertRaises(urllib.error.HTTPError):
                handler.redirect_request(
                    mock.Mock(full_url="https://release.example/source"), None, code,
                    "redirect", {}, "https://release.example/target",
                )

    def test_urls_are_canonical_and_loopback_cannot_use_a_proxy(self):
        module = load_private_https_module()
        for value in (
            "http://release.example/file",
            "https://user@release.example/file",
            "https://release.example:443/file",
            "https://release.example/a/../file",
            "https://release.example/file#fragment",
            "https://release.example/file?query=1",
            "https://release.example/%2Ffile",
            "https://release.example/%ZZ",
            "https://release.example/\x7ffile",
            "https://release.example/\ufefffile",
            "https://r\ud800.example/file",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                module.canonical_https_url(value)
        command = module.loopback_curl_command("https://release.example/file", 20, 1024)
        self.assertEqual("*", command[command.index("--noproxy") + 1])
        self.assertEqual("0", command[command.index("--max-redirs") + 1])

    def test_remote_transport_is_batch_only_and_bounded(self):
        publisher = load_release_module("publish_release_transport", "publish_release.py")
        options = publisher.ssh_options()
        self.assertIn("BatchMode=yes", options)
        self.assertIn("StrictHostKeyChecking=yes", options)
        self.assertIn("ConnectTimeout=15", options)
        self.assertIn("ConnectionAttempts=1", options)
        self.assertIn("ServerAliveCountMax=3", options)
        self.assertLessEqual(publisher.SSH_TIMEOUT, 45)
        self.assertLessEqual(publisher.SCP_TIMEOUT, 600)
        source = (ROOT / "scripts/release/publish_release.py").read_text(encoding="utf-8")
        self.assertIn('"scp", "-B", *ssh_options()', source)

    def test_publisher_runtime_pin_matches_the_protected_embedded_descriptor(self):
        store = load_release_module("publication_store_pin", "publication_store.py")
        descriptor_path = ROOT / "app/src/main/agent-fleet/embedded-runtime-v1.json"
        descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
        self.assertEqual(1, len(descriptor["trustedRuntimeKeys"]))
        record = descriptor["trustedRuntimeKeys"][0]
        key_payload = (descriptor_path.parent / record["file"]).read_bytes()
        self.assertEqual(record["keyId"], store.RUNTIME_KEY_ID)
        self.assertEqual(record["sha256"], store.RUNTIME_KEY_SHA256)
        self.assertEqual(key_payload, store.RUNTIME_PUBLIC_KEY)
        for value in (
            "https://release.example/%2Ffile",
            "https://release.example/%ZZ",
            "https://release.example/\x7ffile",
            "https://release.example/\ufefffile",
            "https://r\ud800.example/file",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                store._canonical_https_url(value)


class PublicationStoreSecurityTest(unittest.TestCase):
    OLD_VERSION = "4.5.6-agentfleet.900"
    NEW_VERSION = "4.5.6-agentfleet.901"
    OLD_CODE = 1095
    NEW_CODE = 1097
    RUNTIME_SEQUENCE = 1096

    @classmethod
    def setUpClass(cls):
        cls.runtime_keys = tempfile.TemporaryDirectory()
        key_root = pathlib.Path(cls.runtime_keys.name)
        cls.runtime_private = key_root / "private.pem"
        cls.runtime_public = key_root / "public.pem"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(cls.runtime_private)],
            check=True, capture_output=True,
        )
        subprocess.run(
            [
                "openssl", "pkey", "-in", str(cls.runtime_private), "-pubout",
                "-out", str(cls.runtime_public),
            ],
            check=True, capture_output=True,
        )
        payload = json.dumps(
            {
                "artifactUrl": "https://release.example/runtime/wtmux.tar",
                "createdAt": "2026-08-09T12:00:00Z",
                "minAppVersionCode": cls.RUNTIME_SEQUENCE,
                "protocolVersion": 2,
                "schemaVersion": 1,
                "sequence": cls.RUNTIME_SEQUENCE,
                "sha256": "a" * 64,
                "size": 1234,
                "version": "git-deadbee",
            },
            sort_keys=True,
            separators=(",", ":"),
        ).encode()
        with tempfile.NamedTemporaryFile() as payload_file:
            payload_file.write(payload)
            payload_file.flush()
            signature = subprocess.run(
                [
                    "openssl", "pkeyutl", "-sign", "-rawin", "-inkey",
                    str(cls.runtime_private), "-in", payload_file.name,
                ],
                check=True, capture_output=True,
            ).stdout
        public_der = subprocess.run(
            ["openssl", "pkey", "-pubin", "-in", str(cls.runtime_public), "-outform", "DER"],
            check=True, capture_output=True,
        ).stdout
        cls.runtime_key_id = hashlib.sha256(public_der).hexdigest()[:32]
        def encode(value):
            return base64.urlsafe_b64encode(value).rstrip(b"=").decode()
        cls.runtime_payload = json.dumps(
            {
                "schemaVersion": 1,
                "keyId": cls.runtime_key_id,
                "payload": encode(payload),
                "signature": encode(signature),
            },
            sort_keys=True,
        ).encode() + b"\n"

    @classmethod
    def tearDownClass(cls):
        cls.runtime_keys.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.publisher = self.root / "publisher"
        self.release_store = self.publisher / "fleet" / "releases"
        self.release_store.mkdir(parents=True)
        old = self.release_store / self.OLD_VERSION
        write_release(old, self.OLD_VERSION, self.OLD_CODE, b"old-release")
        self.latest = self.publisher / "fleet" / "latest"
        self.latest.symlink_to(f"releases/{self.OLD_VERSION}")
        runtime = self.publisher / "runtime"
        runtime.mkdir()
        (runtime / "runtime-manifest.json").write_bytes(self.runtime_payload)
        self.source = self.root / "candidate"
        write_release(self.source, self.NEW_VERSION, self.NEW_CODE, b"new-release")
        self.store = load_release_module("publication_store_fixture", "publication_store.py")
        public_key = self.runtime_public.read_bytes()
        self.store.RUNTIME_PUBLIC_KEY = public_key
        self.store.RUNTIME_KEY_SHA256 = hashlib.sha256(public_key).hexdigest()
        self.store.RUNTIME_KEY_ID = self.runtime_key_id
        self.candidate = load_release_module("release_candidate_store_fixture", "release_candidate.py")
        self.publisher_module = load_release_module("publish_release_fixture", "publish_release.py")
        self.receipts = load_release_module("publication_receipt_fixture", "publication_receipt.py")
        self.proof, self.proof_payload = self.candidate.load_proof(self.source)
        self.old_descriptor = self.store.command_current(str(self.publisher))
        self.expected_app_sha = self.old_descriptor["manifestSha256"]
        self.runtime_sha = hashlib.sha256(self.runtime_payload).hexdigest()

    def tearDown(self):
        self.temporary.cleanup()

    def stage(self, token):
        self.store.command_prepare(str(self.publisher), self.NEW_VERSION, token)
        stage = self.release_store / f".staging-{self.NEW_VERSION}-{token}"
        for record in [*self.proof["files"], {"name": self.candidate.PROOF_NAME}]:
            shutil.copy2(self.source / record["name"], stage / record["name"])
        return stage

    def publish(self, token="1" * 32, reservation_token="2" * 32):
        self.stage(token)
        return self.store.command_publish(
            str(self.publisher), self.NEW_VERSION, token,
            f"releases/{self.OLD_VERSION}", self.expected_app_sha, self.runtime_sha,
            reservation_token, "3" * 64, hashlib.sha256(self.proof_payload).hexdigest(),
            self.NEW_CODE, self.RUNTIME_SEQUENCE,
        )

    def test_successful_publication_is_staged_switched_and_recorded(self):
        previous = self.publish()
        self.assertEqual(self.old_descriptor, previous)
        self.assertEqual(f"activations/{'1' * 32}", os.readlink(self.latest))
        self.assertEqual(f"../releases/{self.NEW_VERSION}", os.readlink(self.publisher / "fleet/activations" / ("1" * 32)))
        published = self.release_store / self.NEW_VERSION
        self.assertEqual(
            set(item["name"] for item in self.proof["files"]) | {self.candidate.PROOF_NAME},
            {path.name for path in published.iterdir()},
        )
        high_water = json.loads((self.publisher / "sequence-high-water-v1.json").read_text())
        self.assertEqual(self.NEW_CODE, high_water["sequence"])
        self.assertFalse(list(self.release_store.glob(".staging-*")))

    def test_existing_version_with_different_bytes_is_never_overwritten(self):
        existing = self.release_store / self.NEW_VERSION
        write_release(existing, self.NEW_VERSION, self.NEW_CODE, b"different-existing")
        with self.assertRaisesRegex(ValueError, "different bytes"):
            self.publish()
        self.assertEqual(f"releases/{self.OLD_VERSION}", os.readlink(self.latest))
        self.assertFalse((self.publisher / "sequence-high-water-v1.json").exists())

    def test_hostile_token_suffix_and_nested_symlink_are_rejected(self):
        with self.assertRaises(ValueError):
            self.store.command_prepare(str(self.publisher), self.NEW_VERSION, "1" * 32 + "/suffix")
        other = self.root / "other"
        other.mkdir()
        unsafe = self.root / "unsafe"
        unsafe.symlink_to(other, target_is_directory=True)
        with self.assertRaises((OSError, ValueError)):
            self.store.command_current(str(unsafe))

    def test_directory_lock_ignores_a_clobberable_lock_symlink(self):
        victim = self.root / "victim"
        victim.write_text("unchanged", encoding="utf-8")
        (self.publisher / ".publish.lock").symlink_to(victim)
        self.publish()
        self.assertEqual("unchanged", victim.read_text(encoding="utf-8"))

    def test_fsync_failure_before_latest_switch_keeps_legacy_target(self):
        self.stage("4" * 32)
        real_fsync = self.store.os.fsync

        def fail_activation(descriptor):
            path = os.readlink(f"/proc/self/fd/{descriptor}")
            if path.endswith("/fleet/activations"):
                raise OSError("injected activation fsync failure")
            return real_fsync(descriptor)

        with mock.patch.object(self.store.os, "fsync", side_effect=fail_activation):
            with self.assertRaisesRegex(OSError, "injected"):
                self.store.command_publish(
                    str(self.publisher), self.NEW_VERSION, "4" * 32,
                    f"releases/{self.OLD_VERSION}", self.expected_app_sha, self.runtime_sha,
                    "5" * 32, "6" * 64, hashlib.sha256(self.proof_payload).hexdigest(),
                    self.NEW_CODE, self.RUNTIME_SEQUENCE,
                )
        self.assertEqual(f"releases/{self.OLD_VERSION}", os.readlink(self.latest))

    def test_latest_directory_fsync_failure_rolls_back_the_switched_pointer(self):
        self.stage("c" * 32)
        real_fsync = self.store.os.fsync
        injected = False

        def fail_after_switch(descriptor):
            nonlocal injected
            path = os.readlink(f"/proc/self/fd/{descriptor}")
            if (
                not injected
                and path.endswith("/fleet")
                and os.readlink(self.latest) == f"activations/{'c' * 32}"
            ):
                injected = True
                raise OSError("injected latest fsync failure")
            return real_fsync(descriptor)

        with mock.patch.object(self.store.os, "fsync", side_effect=fail_after_switch):
            with self.assertRaisesRegex(OSError, "injected latest"):
                self.store.command_publish(
                    str(self.publisher), self.NEW_VERSION, "c" * 32,
                    f"releases/{self.OLD_VERSION}", self.expected_app_sha, self.runtime_sha,
                    "d" * 32, "e" * 64, hashlib.sha256(self.proof_payload).hexdigest(),
                    self.NEW_CODE, self.RUNTIME_SEQUENCE,
                )
        self.assertTrue(injected)
        self.assertEqual(f"releases/{self.OLD_VERSION}", os.readlink(self.latest))

    def test_same_version_aba_cannot_rollback_a_different_activation(self):
        self.publish(token="7" * 32, reservation_token="8" * 32)
        activations = self.publisher / "fleet/activations"
        (activations / ("9" * 32)).symlink_to(f"../releases/{self.NEW_VERSION}")
        replacement = self.publisher / "fleet/.latest-replacement"
        replacement.symlink_to(f"activations/{'9' * 32}")
        os.replace(replacement, self.latest)
        with self.assertRaisesRegex(ValueError, "refusing rollback"):
            self.store.command_rollback(
                str(self.publisher), f"activations/{'7' * 32}", f"releases/{self.OLD_VERSION}"
            )
        self.assertEqual(f"activations/{'9' * 32}", os.readlink(self.latest))

    def test_stable_snapshot_does_not_follow_a_source_swap(self):
        snapshot = self.publisher_module.stable_snapshot(self.source, self.proof, self.proof_payload)
        try:
            apk_name = next(item["name"] for item in self.proof["files"] if item["name"].endswith(".apk"))
            replacement = self.root / "replacement.apk"
            replacement.write_bytes(b"hostile replacement")
            os.replace(replacement, self.source / apk_name)
            self.assertNotEqual(b"hostile replacement", (pathlib.Path(snapshot.name) / apk_name).read_bytes())
        finally:
            snapshot.cleanup()

    def test_failed_staged_publication_always_attempts_cleanup(self):
        receipt = {"versionName": self.NEW_VERSION, "token": "6" * 32}
        reservation = {"token": "7" * 32}
        with (
            mock.patch.object(self.publisher_module, "stage_snapshot") as stage,
            mock.patch.object(
                self.publisher_module,
                "publish_destination",
                side_effect=self.publisher_module.PublishError("injected failure"),
            ),
            mock.patch.object(self.publisher_module, "cleanup_destination") as cleanup,
            self.assertRaisesRegex(ValueError, "injected failure"),
        ):
            self.publisher_module.stage_and_publish(
                f"local:{self.publisher}", self.source, receipt, reservation,
            )
        stage.assert_called_once_with(
            f"local:{self.publisher}", self.source, self.NEW_VERSION, "6" * 32,
        )
        cleanup.assert_called_once_with(
            f"local:{self.publisher}", self.NEW_VERSION, "6" * 32,
        )

    def test_prepared_receipt_reconciles_a_completed_switch(self):
        reservation = {
            "token": "b" * 32,
            "appManifestSha256": self.expected_app_sha,
            "runtimeManifestSha256": self.runtime_sha,
            "runtimeSequence": self.RUNTIME_SEQUENCE,
        }
        reservation_payload = b"reservation-proof\n"
        receipt = self.publisher_module.receipt_identity(
            "primary", f"local:{self.publisher}", self.proof, self.proof_payload,
            reservation, reservation_payload, self.old_descriptor,
        )
        receipt_path = self.root / "receipt.json"
        self.receipts.write(receipt_path, receipt)
        self.stage(receipt["token"])
        self.store.command_publish(
            str(self.publisher), self.NEW_VERSION, receipt["token"], receipt["previousTarget"],
            self.expected_app_sha, self.runtime_sha, reservation["token"],
            receipt["reservationSha256"], receipt["candidateProofSha256"],
            self.NEW_CODE, self.RUNTIME_SEQUENCE,
        )
        reconciled, done = self.publisher_module.reconcile_receipt(
            receipt_path, receipt, "primary", f"local:{self.publisher}", self.proof,
            self.proof_payload, reservation, reservation_payload,
        )
        self.assertTrue(done)
        self.assertEqual("switched", reconciled["state"])

    def test_distinct_unavailable_primary_never_auto_falls_back(self):
        with mock.patch.object(self.publisher_module, "probe_remote", side_effect=self.publisher_module.RemoteUnavailable("down")):
            with self.assertRaisesRegex(ValueError, "shared sequence authority"):
                self.publisher_module.select_destination(
                    "publisher@example:/srv/primary", "publisher@example:/srv/fallback"
                )

    def test_publisher_reproves_runtime_signature_and_rejects_a_supplied_sequence_mismatch(self):
        runtime_file = self.publisher / "runtime/runtime-manifest.json"
        envelope = json.loads(self.runtime_payload)
        envelope["signature"] = ("A" if envelope["signature"][0] != "A" else "B") + envelope["signature"][1:]
        tampered = json.dumps(envelope, sort_keys=True).encode() + b"\n"
        runtime_file.write_bytes(tampered)
        self.runtime_sha = hashlib.sha256(tampered).hexdigest()
        self.stage("a" * 32)
        with self.assertRaisesRegex(ValueError, "signature reproof"):
            self.store.command_publish(
                str(self.publisher), self.NEW_VERSION, "a" * 32,
                f"releases/{self.OLD_VERSION}", self.expected_app_sha, self.runtime_sha,
                "b" * 32, "c" * 64, hashlib.sha256(self.proof_payload).hexdigest(),
                self.NEW_CODE, self.RUNTIME_SEQUENCE,
            )
        runtime_file.write_bytes(self.runtime_payload)
        self.runtime_sha = hashlib.sha256(self.runtime_payload).hexdigest()
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.store.command_publish(
                str(self.publisher), self.NEW_VERSION, "a" * 32,
                f"releases/{self.OLD_VERSION}", self.expected_app_sha, self.runtime_sha,
                "b" * 32, "c" * 64, hashlib.sha256(self.proof_payload).hexdigest(),
                self.NEW_CODE, self.RUNTIME_SEQUENCE - 1,
            )

    def test_runtime_claim_api_is_blocked_until_it_can_reprove_the_signed_envelope(self):
        claim = {
            "schemaVersion": 1,
            "component": "android-runtime",
            "sequence": self.NEW_CODE,
            "token": "1" * 32,
            "version": "git-deadbee",
            "manifestSha256": "2" * 64,
            "sourceCommit": "3" * 40,
            "candidateProofSha256": "4" * 64,
            "reservationSha256": "5" * 64,
        }
        with self.assertRaisesRegex(ValueError, "not enabled"):
            self.store._validate_claim(claim)

    def test_candidate_proof_rejects_wrong_certificate_and_unexpected_files(self):
        with self.assertRaisesRegex(ValueError, "pinned certificate"):
            self.candidate.create_proof(self.source, "b" * 64)
        wrong_proof = dict(self.proof, certificateSha256="b" * 64)
        with self.assertRaisesRegex(ValueError, "production signer"):
            self.store.validate_proof(wrong_proof, self.store.canonical(wrong_proof))
        self.publisher_module.require_production_certificate(ROOT, self.proof)
        with self.assertRaisesRegex(ValueError, "production signer"):
            self.publisher_module.require_production_certificate(ROOT, wrong_proof)
        unexpected = self.source / "operator-notes.txt"
        unexpected.write_text("must stay local", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "unexpected entry"):
            self.candidate.create_proof(self.source, PRODUCTION_CERTIFICATE)

    def test_orphan_sequence_claim_prevents_crash_equivocation(self):
        handles = self.store.store_handles(str(self.publisher))
        first = {
            "schemaVersion": 1,
            "component": "android-app",
            "sequence": self.NEW_CODE,
            "token": "1" * 32,
            "version": self.NEW_VERSION,
            "manifestSha256": self.proof["manifestSha256"],
            "sourceCommit": "a" * 40,
            "candidateProofSha256": hashlib.sha256(self.proof_payload).hexdigest(),
            "reservationSha256": "2" * 64,
        }
        try:
            self.store.acquire_lock(handles[0])
            claims = self.store.open_or_create_directory(handles[0], "sequence-claims")
            try:
                payload = self.store.canonical(first)
                descriptor = os.open(
                    f"{self.NEW_CODE}.json", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644, dir_fd=claims
                )
                try:
                    os.write(descriptor, payload)
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
                os.fsync(claims)
            finally:
                os.close(claims)
        finally:
            self.store.close_handles(handles)
        second = dict(first, token="3" * 32, reservationSha256="4" * 64)
        handles = self.store.store_handles(str(self.publisher))
        try:
            self.store.acquire_lock(handles[0])
            with self.assertRaisesRegex(ValueError, "claim collision"):
                self.store.burn_claim(handles[0], second, self.RUNTIME_SEQUENCE)
        finally:
            self.store.close_handles(handles)
        self.assertFalse((self.publisher / "sequence-high-water-v1.json").exists())

    def test_publication_base_owner_mode_is_enforced(self):
        self.publisher.chmod(0o777)
        with self.assertRaisesRegex(ValueError, "publication base"):
            self.store.command_current(str(self.publisher))


class PublicationReceiptSecurityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.module = load_release_module("publication_receipt_security", "publication_receipt.py")
        self.path = self.root / "receipt.json"
        self.value = {
            "schemaVersion": 2,
            "publisher": "primary",
            "destinationSha256": "1" * 64,
            "versionName": "4.5.6-agentfleet.901",
            "versionCode": 902,
            "token": "2" * 32,
            "previousTarget": "-",
            "newTarget": f"activations/{'2' * 32}",
            "previousDescriptor": None,
            "candidateProofSha256": "3" * 64,
            "reservationSha256": "4" * 64,
            "reservationToken": "5" * 32,
            "state": "prepared",
        }

    def tearDown(self):
        self.temporary.cleanup()

    def test_receipt_is_canonical_durable_and_mode_0600(self):
        self.module.write(self.path, self.value)
        observed, payload = self.module.read(self.path)
        self.assertEqual(self.value, observed)
        self.assertEqual(self.module.canonical(self.value), payload)
        self.assertEqual(0o600, stat.S_IMODE(self.path.stat().st_mode))
        self.assertNotIn("local:", payload.decode())

    def test_symlink_wrong_mode_extra_fields_and_oversize_are_rejected(self):
        victim = self.root / "victim"
        victim.write_bytes(self.module.canonical(self.value))
        victim.chmod(0o600)
        self.path.symlink_to(victim)
        with self.assertRaises(OSError):
            self.module.read(self.path)
        self.path.unlink()
        self.module.write(self.path, self.value)
        self.path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "mode-0600"):
            self.module.read(self.path)
        changed = dict(self.value, unexpected=True)
        self.path.write_bytes(self.module.canonical(changed))
        self.path.chmod(0o600)
        with self.assertRaisesRegex(ValueError, "fields"):
            self.module.read(self.path)
        self.path.write_bytes(b"x" * (self.module.MAX_RECEIPT_BYTES + 1))
        with self.assertRaisesRegex(ValueError, "bounded"):
            self.module.read(self.path)

    def test_failed_receipt_fsync_preserves_the_previous_authoritative_state(self):
        self.module.write(self.path, self.value)
        replacement = dict(self.value, state="switched")
        real_fsync = self.module.os.fsync

        def fail_temporary(descriptor):
            target = os.readlink(f"/proc/self/fd/{descriptor}")
            if target.endswith(".tmp"):
                raise OSError("injected receipt fsync failure")
            return real_fsync(descriptor)

        with mock.patch.object(self.module.os, "fsync", side_effect=fail_temporary):
            with self.assertRaisesRegex(OSError, "injected"):
                self.module.write(self.path, replacement)
        self.assertEqual("prepared", self.module.read(self.path)[0]["state"])

    def test_default_receipt_is_user_state_not_repository_build_output(self):
        path = self.module.default_path("4.5.6-agentfleet.901")
        self.assertNotIn("/build/", str(path))
        self.assertIn("agent-fleet/android-releases", str(path))


class SignedSequenceReservationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.module = load_sequence_module()
        self.private = self.root / "private.pem"
        self.public = self.root / "public.pem"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(self.private)],
            check=True, capture_output=True,
        )
        subprocess.run(
            ["openssl", "pkey", "-in", str(self.private), "-pubout", "-out", str(self.public)],
            check=True, capture_output=True,
        )
        key_id = hashlib.sha256(self.module._public_der(self.public)).hexdigest()[:32]
        key_name = f"trusted-runtime-key-{key_id}.pem"
        self.key = self.root / key_name
        self.public.replace(self.key)
        self.descriptor = self.root / "embedded-runtime-v1.json"
        self.descriptor.write_text(
            json.dumps(
                {
                    "trustedRuntimeKeys": [
                        {
                            "keyId": key_id,
                            "file": key_name,
                            "sha256": hashlib.sha256(self.key.read_bytes()).hexdigest(),
                        }
                    ]
                }
            ) + "\n",
            encoding="utf-8",
        )
        app = self.root / "app"
        write_release(app, "4.5.6-agentfleet.900", 900, b"published")
        self.app_payload = (app / "manifest.json").read_bytes()

    def tearDown(self):
        self.temporary.cleanup()

    def envelope(self, *, sequence=901, private=None):
        payload_value = {
            "artifactUrl": "https://release.example/runtime/wtmux.tar",
            "createdAt": "2026-08-09T12:00:00Z",
            "minAppVersionCode": 900,
            "protocolVersion": 2,
            "schemaVersion": 1,
            "sequence": sequence,
            "sha256": "a" * 64,
            "size": 1234,
            "version": "git-deadbee",
        }
        payload = self.module.canonical(payload_value)
        with tempfile.NamedTemporaryFile() as payload_file:
            payload_file.write(payload)
            payload_file.flush()
            result = subprocess.run(
                [
                    "openssl", "pkeyutl", "-sign", "-rawin", "-inkey",
                    str(private or self.private), "-in", payload_file.name,
                ],
                check=True, capture_output=True,
            )
        key_id = hashlib.sha256(self.module._public_der(self.key)).hexdigest()[:32]
        def encode(value):
            return base64.urlsafe_b64encode(value).rstrip(b"=").decode()
        return json.dumps(
            {"schemaVersion": 1, "keyId": key_id, "payload": encode(payload), "signature": encode(result.stdout)},
            indent=2, sort_keys=True,
        ).encode() + b"\n"

    def test_signed_envelope_creates_an_exact_reservation_and_rejects_bool_sequence(self):
        runtime = self.envelope()
        reservation = self.module.make_reservation(1097, self.app_payload, runtime, self.descriptor)
        self.assertEqual(901, reservation["runtimeSequence"])
        self.assertEqual(hashlib.sha256(runtime).hexdigest(), reservation["runtimeManifestSha256"])
        with self.assertRaisesRegex(ValueError, "sequence"):
            self.module.make_reservation(1097, self.app_payload, self.envelope(sequence=True), self.descriptor)

    def test_tampered_signature_and_unpinned_key_are_rejected(self):
        envelope = json.loads(self.envelope())
        envelope["signature"] = ("A" if envelope["signature"][0] != "A" else "B") + envelope["signature"][1:]
        with self.assertRaisesRegex(ValueError, "signature"):
            self.module.verify_runtime_envelope(json.dumps(envelope).encode(), self.descriptor)
        empty = self.root / "empty.json"
        empty.write_text('{"trustedRuntimeKeys":[]}\n', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "unpinned"):
            self.module.verify_runtime_envelope(self.envelope(), empty)

    def test_reproof_detects_changed_published_app_bytes(self):
        runtime = self.envelope()
        reservation = self.module.make_reservation(1097, self.app_payload, runtime, self.descriptor)
        changed = self.app_payload.replace(b'"versionCode": 900', b'"versionCode": 899')
        observed = self.module.make_reservation(1097, changed, runtime, self.descriptor)
        observed["token"] = reservation["token"]
        self.assertNotEqual(observed, reservation)

    def test_reservation_is_canonical_private_and_no_follow(self):
        runtime = self.envelope()
        reservation = self.module.make_reservation(1097, self.app_payload, runtime, self.descriptor)
        path = self.root / "reservation.json"
        self.module.write_reservation(path, reservation)
        self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
        self.assertEqual(reservation, self.module.load_reservation(path)[0])
        path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "0600"):
            self.module.load_reservation(path)
        path.unlink()
        victim = self.root / "victim.json"
        victim.write_bytes(self.module.canonical(reservation, newline=True))
        victim.chmod(0o600)
        path.symlink_to(victim)
        with self.assertRaises(OSError):
            self.module.load_reservation(path)


class ReleaseOrchestratorTransactionTest(unittest.TestCase):
    OLD_VERSION = "4.5.6-agentfleet.900"
    NEW_VERSION = "4.5.6-agentfleet.901"

    def create_fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = pathlib.Path(temporary.name)
        repo = root / "repo"
        publisher = root / "publisher"
        for relative in (
            "scripts/release/app-release.sh",
        ):
            destination = repo / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, destination)
        (repo / "app").mkdir()
        (repo / "app/version.properties").write_text(
            f"VERSION_NAME={self.NEW_VERSION}\nVERSION_CODE=5001\n",
            encoding="utf-8",
        )
        common = f"""#!/usr/bin/env bash
agent_fleet_release_read_version() {{
  AGENT_FLEET_RELEASE_VERSION_NAME={self.NEW_VERSION}
  AGENT_FLEET_RELEASE_VERSION_CODE=5001
  export AGENT_FLEET_RELEASE_VERSION_NAME AGENT_FLEET_RELEASE_VERSION_CODE
}}
agent_fleet_release_load_config() {{
  AGENT_FLEET_RELEASE_BASE_URL=https://release.invalid/fleet/latest
  AGENT_FLEET_RUNTIME_MANIFEST_URL=https://release.invalid/runtime/manifest.json
  AGENT_FLEET_PUBLISH_PRIMARY='local:{publisher}'
  AGENT_FLEET_PUBLISH_FALLBACK=
  export AGENT_FLEET_RELEASE_BASE_URL AGENT_FLEET_RUNTIME_MANIFEST_URL
  export AGENT_FLEET_PUBLISH_PRIMARY AGENT_FLEET_PUBLISH_FALLBACK
}}
agent_fleet_release_load_credentials() {{ :; }}
agent_fleet_release_check_keystore() {{ :; }}
agent_fleet_release_check_git() {{ :; }}
agent_fleet_release_find_sdk() {{ :; }}
"""
        write_executable(repo / "scripts/release/release-common.sh", common)
        write_executable(
            repo / "scripts/release/check-release-sequence.py",
            "#!/usr/bin/env bash\n"
            "repo=$(cd \"$(dirname \"$0\")/../..\" && pwd)\n"
            "count=0\n"
            "if [[ -f \"$repo/sequence-count\" ]]; then count=$(<\"$repo/sequence-count\"); fi\n"
            "count=$((count + 1))\n"
            "printf '%s\\n' \"$count\" >\"$repo/sequence-count\"\n"
            "printf 'sequence:%s\\n' \"$count\" >>\"$TEST_LOG\"\n"
            "reservation=\n"
            "while [[ $# -gt 0 ]]; do\n"
            "  if [[ $1 == --reservation-out ]]; then reservation=$2; shift 2; else shift; fi\n"
            "done\n"
            "if [[ -n $reservation ]]; then printf 'reservation\\n' >\"$reservation\"; chmod 600 \"$reservation\"; fi\n"
            "if [[ ${TEST_LATE_SEQUENCE_ADVANCE:-0} == 1 && $count -ge 2 ]]; then\n"
            "  echo 'published sequence advanced during build' >&2\n"
            "  exit 42\n"
            "fi\n",
        )
        write_executable(
            repo / "scripts/debug/android-check.sh",
            "#!/usr/bin/env bash\n"
            "[[ -z ${AGENT_FLEET_STORE_PASSWORD:-}${AGENT_FLEET_KEY_PASSWORD:-} ]] || exit 97\n"
            "printf 'full-api36\\n' >>\"$TEST_LOG\"\n",
        )
        write_executable(
            repo / "scripts/debug/android-gradle.sh",
            "#!/usr/bin/env bash\n"
            "[[ -z ${AGENT_FLEET_STORE_PASSWORD:-}${AGENT_FLEET_KEY_PASSWORD:-} ]] || exit 97\n"
            "printf 'release-lint\\n' >>\"$TEST_LOG\"\n",
        )
        write_executable(
            repo / "scripts/release/build-signed-release.sh",
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "[[ -z ${AGENT_FLEET_STORE_PASSWORD:-}${AGENT_FLEET_KEY_PASSWORD:-} ]] || exit 97\n"
            "repo=$(cd \"$(dirname \"$0\")/../..\" && pwd)\n"
            "version=$1\n"
            "code=$2\n"
            "out=\"$repo/dist/$version\"\n"
            "mkdir -p \"$out\"\n"
            "printf 'signed candidate bytes\\n' >\"$out/agent-fleet.apk\"\n"
            "size=$(stat -c '%s' \"$out/agent-fleet.apk\")\n"
            "sha=$(sha256sum \"$out/agent-fleet.apk\" | awk '{print $1}')\n"
            "printf '{\"versionName\":\"%s\",\"versionCode\":%s,\"artifacts\":[{\"abi\":\"arm64-v8a\",\"apkSha256\":\"%s\",\"size\":%s}]}\\n' \"$version\" \"$code\" \"$sha\" \"$size\" >\"$out/manifest.json\"\n"
            "printf 'signed-build\\n' >>\"$TEST_LOG\"\n",
        )
        write_executable(
            repo / "scripts/release/verify-release.sh",
            "#!/usr/bin/env bash\n"
            "[[ -z ${AGENT_FLEET_STORE_PASSWORD:-}${AGENT_FLEET_KEY_PASSWORD:-} ]] || exit 97\n"
            "printf 'release-verification\\n' >>\"$TEST_LOG\"\n",
        )
        write_executable(
            repo / "scripts/release/publication_receipt.py",
            "#!/usr/bin/env python3\n"
            "import os, pathlib, sys\n"
            "if sys.argv[1] == 'default-path': print(os.environ['TEST_RECEIPT'])\n"
            "elif sys.argv[1] == 'redact': pathlib.Path(sys.argv[3]).write_text('{}\\n')\n"
            "else: raise SystemExit(2)\n",
        )
        write_executable(
            repo / "scripts/release/publish-release.sh",
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "[[ -z ${AGENT_FLEET_STORE_PASSWORD:-}${AGENT_FLEET_KEY_PASSWORD:-} ]] || exit 97\n"
            "if [[ ${1:-} == --rollback ]]; then\n"
            f"  ln -sfn 'releases/{self.OLD_VERSION}' \"$TEST_PUBLISHER/fleet/latest\"\n"
            "  printf 'rolled_back\\n' >\"$2\"\n"
            "  printf 'rollback\\n' >>\"$TEST_LOG\"\n"
            "  exit 0\n"
            "fi\n"
            f"mkdir -p \"$TEST_PUBLISHER/fleet/releases/{self.NEW_VERSION}\" \"$TEST_PUBLISHER/fleet/activations\"\n"
            f"ln -sfn '../releases/{self.NEW_VERSION}' \"$TEST_PUBLISHER/fleet/activations/{'1' * 32}\"\n"
            f"ln -sfn 'activations/{'1' * 32}' \"$TEST_PUBLISHER/fleet/latest\"\n"
            "printf 'switched\\n' >\"$2\"\n"
            "printf 'publication\\n' >>\"$TEST_LOG\"\n",
        )
        write_executable(
            repo / "scripts/release/verify-served-release.py",
            "#!/usr/bin/env bash\n"
            "if [[ ${1:-} == --restored-from-receipt ]]; then\n"
            "  printf 'restored-verification\\n' >>\"$TEST_LOG\"\n"
            "  if [[ ${TEST_RESTORE_FAILURE:-0} == 1 ]]; then exit 24; fi\n"
            "  exit 0\n"
            "fi\n"
            "printf 'served-verification\\n' >>\"$TEST_LOG\"\n"
            "if [[ ${TEST_SERVED_FAILURE:-0} == 1 ]]; then exit 23; fi\n",
        )
        old = publisher / "fleet" / "releases" / self.OLD_VERSION
        old.mkdir(parents=True)
        latest = publisher / "fleet" / "latest"
        latest.symlink_to(f"releases/{self.OLD_VERSION}")
        subprocess.run(["git", "init", str(repo)], check=True, capture_output=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.com"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.name", "Test"], check=True)
        subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
        subprocess.run(["git", "-C", str(repo), "commit", "-m", "fixture"], check=True, capture_output=True)
        return temporary, repo, publisher, latest

    def run_fixture(self, **environment):
        temporary, repo, publisher, latest = self.create_fixture()
        log = pathlib.Path(temporary.name) / "pipeline.log"
        receipt = pathlib.Path(temporary.name) / "publication-receipt.json"
        result = run(
            ["bash", "scripts/release/app-release.sh"],
            cwd=repo,
            env={
                "TEST_LOG": str(log),
                "TEST_RECEIPT": str(receipt),
                "TEST_PUBLISHER": str(publisher),
                "AGENT_FLEET_PUBLICATION_RECEIPT": str(receipt),
                "AGENT_FLEET_STORE_PASSWORD": "must-not-leak",
                "AGENT_FLEET_KEY_PASSWORD": "must-not-leak",
                **environment,
            },
        )
        reports = list((repo / "build/reports/agent-fleet/release").glob("*/stages.tsv"))
        self.assertEqual(1, len(reports))
        return temporary, repo, publisher, latest, log, reports[0], receipt, result

    def test_late_sequence_advance_stops_before_publication(self):
        temporary, repo, publisher, latest, log, stages, receipt, result = self.run_fixture(
            TEST_LATE_SEQUENCE_ADVANCE="1"
        )
        try:
            self.assertEqual(42, result.returncode, result.stderr)
            self.assertEqual("2", (repo / "sequence-count").read_text(encoding="utf-8").strip())
            self.assertEqual(f"releases/{self.OLD_VERSION}", os.readlink(latest))
            self.assertFalse((publisher / "fleet/releases" / self.NEW_VERSION).exists())
            stage_text = stages.read_text(encoding="utf-8")
            self.assertIn("sequence-recheck\t", stage_text)
            self.assertIn("\tfailed\n", stage_text)
            self.assertNotIn("publication\t", stage_text)
        finally:
            temporary.cleanup()

    def test_served_failure_restores_previous_latest_target(self):
        temporary, repo, publisher, latest, log, stages, receipt, result = self.run_fixture(
            TEST_SERVED_FAILURE="1"
        )
        try:
            self.assertEqual(23, result.returncode, result.stderr)
            self.assertEqual(f"releases/{self.OLD_VERSION}", os.readlink(latest))
            self.assertTrue((publisher / "fleet/releases" / self.NEW_VERSION).is_dir())
            self.assertEqual("rolled_back", receipt.read_text(encoding="utf-8").strip())
            stage_text = stages.read_text(encoding="utf-8")
            self.assertRegex(stage_text, r"publication\t\d+\tpassed")
            self.assertRegex(stage_text, r"served-verification\t\d+\tfailed")
            self.assertRegex(stage_text, r"publication-rollback\t\d+\tpassed")
            self.assertRegex(stage_text, r"restored-served-verification\t\d+\tpassed")
        finally:
            temporary.cleanup()

    def test_successful_orchestrator_publication_keeps_new_target_active(self):
        temporary, repo, publisher, latest, log, stages, receipt, result = self.run_fixture()
        try:
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("2", (repo / "sequence-count").read_text(encoding="utf-8").strip())
            self.assertEqual(f"activations/{'1' * 32}", os.readlink(latest))
            self.assertEqual("switched", receipt.read_text(encoding="utf-8").strip())
            stage_text = stages.read_text(encoding="utf-8")
            self.assertRegex(stage_text, r"sequence-recheck\t\d+\tpassed")
            self.assertRegex(stage_text, r"publication\t\d+\tpassed")
            self.assertRegex(stage_text, r"served-verification\t\d+\tpassed")
            self.assertNotIn("publication-rollback", stage_text)
        finally:
            temporary.cleanup()

    def test_restore_verification_failure_uses_reserved_status_75(self):
        temporary, repo, publisher, latest, log, stages, receipt, result = self.run_fixture(
            TEST_SERVED_FAILURE="1", TEST_RESTORE_FAILURE="1"
        )
        try:
            self.assertEqual(75, result.returncode, result.stderr)
            self.assertEqual(f"releases/{self.OLD_VERSION}", os.readlink(latest))
        finally:
            temporary.cleanup()


class ReleasePreflightTest(unittest.TestCase):
    def fake_jdk(self, directory: pathlib.Path):
        bin_dir = directory / "bin"
        bin_dir.mkdir(parents=True)
        (bin_dir / "java").write_text("#!/bin/sh\necho 'openjdk version \"17.0.1\"' >&2\n", encoding="utf-8")
        (bin_dir / "keytool").write_text(
            "#!/bin/sh\n"
            "[ \"$AGENT_FLEET_STORE_PASSWORD\" = correct-password ] || exit 1\n"
            "echo 'SHA256: C5:B2:53:9C:02:8A:E1:DC:53:9D:ED:31:13:CC:3C:73:5F:60:14:5B:BF:C5:E4:A8:25:40:56:45:2D:B5:E8:73'\n",
            encoding="utf-8",
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
            local_pin = signing / "certificate-sha256.txt"
            local_pin.write_text(PRODUCTION_CERTIFICATE + "\n", encoding="utf-8")
            local_pin.chmod(0o600)
            jdk = root / "jdk"
            self.fake_jdk(jdk)
            passed = self.shell_preflight(root, signing, jdk)
            self.assertEqual(0, passed.returncode, passed.stderr)
            password.chmod(0o644)
            rejected = self.shell_preflight(root, signing, jdk)
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("mode-0600", rejected.stderr)

    def test_local_backup_pin_and_both_apk_signers_must_match_the_protected_pin(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            signing = root / "signing"
            signing.mkdir()
            (signing / "agent-fleet-release.jks").write_bytes(b"fixture")
            password = signing / "agent-fleet-release.pass"
            password.write_text("correct-password", encoding="utf-8")
            password.chmod(0o600)
            local_pin = signing / "certificate-sha256.txt"
            local_pin.write_text("b" * 64 + "\n", encoding="utf-8")
            local_pin.chmod(0o600)
            jdk = root / "jdk"
            self.fake_jdk(jdk)
            rejected = self.shell_preflight(root, signing, jdk)
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("protected production fingerprint", rejected.stderr)

        builder = (ROOT / "scripts/release/build-signed-release.sh").read_text(encoding="utf-8")
        verifier = (ROOT / "scripts/release/verify-release.sh").read_text(encoding="utf-8")
        self.assertIn('universal_certificate_sha" == "$certificate_sha', builder)
        self.assertIn('certificate_sha" == "$AGENT_FLEET_EXPECTED_CERTIFICATE_SHA256', builder)
        self.assertIn('match.group(1).lower() != manifest["certificateSha256"]', verifier)
        self.assertIn('manifest["certificateSha256"] != expected_certificate', verifier)

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
        self.assertEqual(1096, module.required_floor({"versionCode": 1082}, {"sequence": 1081}))
        self.assertEqual(1097, module.required_floor({"versionCode": 1097}, {"sequence": 1096}))
        self.assertEqual(1098, module.required_floor({"versionCode": 1097}, {"sequence": 1098}))
        with self.assertRaises(ValueError):
            module.required_floor({"versionCode": True}, {"sequence": 1083})

    def test_release_pipeline_has_one_build_and_holds_before_publication(self):
        text = (ROOT / "scripts/release/app-release.sh").read_text(encoding="utf-8")
        ordered = [
            "run_stage preflight",
            "run_stage full-api36",
            "run_stage release-lint",
            "run_stage signed-build",
            "run_stage release-verification",
            "run_stage sequence-recheck",
            "run_stage publication",
            "run_stage_deferred served-verification",
        ]
        positions = [text.index(value) for value in ordered]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(1, text.count("run_stage signed-build"))
        self.assertLess(text.index('if [[ "$hold" == "1" ]]'), text.index("run_stage publication"))
        self.assertGreaterEqual(text.count("env -u AGENT_FLEET_STORE_PASSWORD"), 4)
        self.assertNotIn("export AGENT_FLEET_STORE_PASSWORD", text)
        self.assertIn("if signed_build_passed and manifest_path.is_file():", text)

    def test_local_publisher_uses_certificate_checked_loopback_https_fallback(self):
        pipeline = (ROOT / "scripts/release/app-release.sh").read_text(encoding="utf-8")
        helper = (ROOT / "scripts/release/private_https.py").read_text(encoding="utf-8")
        self.assertIn('loopback_args=(--loopback-fallback)', pipeline)
        self.assertIn('[[ "$AGENT_FLEET_PUBLISH_PRIMARY" == local:* ]]', pipeline)
        self.assertIn('"--resolve", f"{host}:443:127.0.0.1"', helper)
        command = load_private_https_module().loopback_curl_command(
            "https://release.example/manifest.json", 20
        )
        self.assertEqual("0", command[command.index("--max-redirs") + 1])
        self.assertNotIn("--insecure", helper)
        self.assertNotIn("-k", helper)

    def test_build_preflight_precedes_runtime_verification_and_gradle(self):
        text = (ROOT / "scripts/release/build-signed-release.sh").read_text(encoding="utf-8")
        credential = text.index("agent_fleet_release_check_keystore")
        runtime = text.index("verify-embedded-runtime.py")
        gradle = text.index("gradlew.bat app:assembleRelease")
        self.assertLess(credential, runtime)
        self.assertLess(runtime, gradle)


if __name__ == "__main__":
    unittest.main()
