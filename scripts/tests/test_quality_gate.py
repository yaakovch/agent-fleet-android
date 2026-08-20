import importlib.util
import hashlib
import json
import os
import shutil
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]


def load_module(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


policy = load_module("repository_policy", "scripts/quality/repository_policy.py")
apks = load_module("verify_debug_apks", "scripts/quality/verify_debug_apks.py")
runtime = load_module(
    "verify_embedded_runtime",
    "scripts/runtime/verify-embedded-runtime.py",
)


class EmbeddedRuntimeRegistryTest(unittest.TestCase):
    def test_packaged_runtime_cannot_regress_tmux_terminal_reply_safety(self):
        safe_files = {
            "lib/tmux_safety.py",
            "lib/tmux_state.sh",
            "scripts/wtmux-tmux-safety",
        }
        with self.assertRaisesRegex(ValueError, "predates managed terminal-reply safety"):
            runtime.validate_terminal_reply_safe_runtime(
                {
                    "clientRuntime": {"sequence": 57},
                    "hostRuntime": {"sequence": 51},
                    "providerAdapters": {"sequence": 24},
                },
                safe_files,
            )
        with self.assertRaisesRegex(ValueError, "predates managed terminal-reply safety"):
            runtime.validate_terminal_reply_safe_runtime(
                {
                    "clientRuntime": {"sequence": 61},
                    "hostRuntime": {"sequence": 55},
                    "providerAdapters": {"sequence": 28},
                },
                safe_files,
            )
        with self.assertRaisesRegex(ValueError, "omits managed terminal-reply safety"):
            runtime.validate_terminal_reply_safe_runtime(
                {
                    "clientRuntime": {"sequence": 66},
                    "hostRuntime": {"sequence": 60},
                    "providerAdapters": {"sequence": 33},
                },
                {"lib/tmux_state.sh"},
            )
        with self.assertRaisesRegex(ValueError, "predates managed terminal-reply safety"):
            runtime.validate_terminal_reply_safe_runtime(
                {
                    "clientRuntime": {"sequence": 65},
                    "hostRuntime": {"sequence": 59},
                    "providerAdapters": {"sequence": 32},
                },
                safe_files,
            )
        self.assertEqual(
            66,
            runtime.validate_terminal_reply_safe_runtime(
                {
                    "clientRuntime": {"sequence": 66},
                    "hostRuntime": {"sequence": 60},
                    "providerAdapters": {"sequence": 33},
                },
                safe_files,
            )["clientRuntime"]["sequence"],
        )

    def test_packaged_hosts_require_identity_v2_and_verified_transport(self):
        with self.assertRaisesRegex(ValueError, "identity schema v2"):
            runtime.validate_connectable_registry_record(
                {
                    "schemaVersion": 1,
                    "id": "legacy-host",
                    "roles": ["host"],
                    "transport": "tailscale",
                }
            )
        with self.assertRaisesRegex(ValueError, "no verified transport"):
            runtime.validate_connectable_registry_record(
                {
                    "schemaVersion": 2,
                    "id": "unverified-host",
                    "roles": ["host"],
                    "transport": "tailscale",
                    "endpoints": [
                        {
                            "network": "tailnet",
                            "sshEngine": "openssh",
                            "identityState": "unverified",
                            "sshHostKeySha256": "",
                            "tailscaleNodeId": "",
                        }
                    ],
                }
            )
        self.assertEqual(
            "verified-host",
            runtime.validate_connectable_registry_record(
                {
                    "schemaVersion": 2,
                    "id": "verified-host",
                    "roles": ["host"],
                    "transport": "tailscale",
                    "endpoints": [
                        {
                            "network": "tailnet",
                            "sshEngine": "openssh",
                            "identityState": "verified",
                            "sshHostKeySha256": "SHA256:example",
                            "tailscaleNodeId": "node-example",
                        }
                    ],
                }
            )["id"],
        )
        with self.assertRaisesRegex(ValueError, "no verified transport"):
            runtime.validate_connectable_registry_record(
                {
                    "schemaVersion": 2,
                    "id": "invalid-direct-tailscale-cli",
                    "roles": ["host"],
                    "transport": "ssh",
                    "endpoints": [
                        {
                            "network": "direct",
                            "sshEngine": "tailscale-cli",
                            "identityState": "verified",
                            "sshHostKeySha256": "",
                            "tailscaleNodeId": "",
                        }
                    ],
                }
            )
        self.assertEqual(
            "client-only",
            runtime.validate_connectable_registry_record(
                {
                    "schemaVersion": 2,
                    "id": "client-only",
                    "roles": ["client"],
                    "transport": "ssh",
                    "endpoints": [],
                }
            )["id"],
        )


class RepositoryPolicyTest(unittest.TestCase):
    def test_action_references_require_immutable_commits(self):
        self.assertIsNone(policy.action_reference_error("actions/checkout@" + "a" * 40))
        self.assertIsNone(policy.action_reference_error("./.github/actions/local"))
        self.assertIsNone(
            policy.action_reference_error("docker://example.invalid/tool@sha256:" + "b" * 64)
        )
        self.assertIn("40-character", policy.action_reference_error("actions/checkout@v4"))
        self.assertIn("sha256", policy.action_reference_error("docker://alpine:3.22"))

    def test_workflow_scanner_covers_named_steps_and_container_images(self):
        workflow = """
        steps:
          - name: Checkout
            uses: actions/checkout@v4
          - uses: docker://alpine@sha256:{digest}
        container:
          image: ghcr.io/example/build@sha256:{digest}
        """.format(digest="a" * 64)
        self.assertEqual(
            [
                (4, "actions/checkout@v4"),
                (5, "docker://alpine@sha256:" + "a" * 64),
            ],
            policy.workflow_action_references(workflow),
        )
        self.assertEqual(
            [(7, "ghcr.io/example/build@sha256:" + "a" * 64)],
            policy.workflow_container_references(workflow),
        )
        self.assertIn(
            "sha256",
            policy.container_reference_error("ghcr.io/example/build:latest"),
        )

    def test_release_verifier_embedded_python_is_valid(self):
        text = (ROOT / "scripts" / "release" / "verify-release.sh").read_text(encoding="utf-8")
        embedded = text.split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
        compile(embedded, "verify-release.sh heredoc", "exec")

    def test_debug_release_failure_is_non_destructive_and_version_bound(self):
        text = (
            ROOT / ".github" / "workflows" / "attach_debug_apks_to_release.yml"
        ).read_text(encoding="utf-8")
        self.assertNotIn("release delete", text)
        self.assertNotIn("push --delete", text)
        self.assertIn(
            '[[ "$RELEASE_VERSION_NAME" != "v$TRACKED_VERSION_NAME" ]]',
            text,
        )
        self.assertIn(
            'export TERMUX_APP_VERSION_NAME="$TRACKED_VERSION_NAME"',
            text,
        )

    def test_dependency_lock_parser_rejects_dynamic_or_unsorted_state(self):
        header = (
            "# This is a Gradle generated file for dependency locking.\n"
            "# Manual edits can break the build and are not advised.\n"
            "# This file is expected to be part of source control.\n"
        )
        with tempfile.TemporaryDirectory() as temporary:
            lock = Path(temporary) / "gradle.lockfile"
            lock.write_text(
                header + "example:z:1.0=runtime\nexample:a:1.0=runtime\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(policy.PolicyError, "deterministically sorted"):
                policy.parse_dependency_lock(lock)
            lock.write_text(
                header + "example:a:1.+=runtime\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(policy.PolicyError, "invalid Gradle dependency lock row"):
                policy.parse_dependency_lock(lock)

    def test_release_platform_tools_require_exact_reviewed_checksums(self):
        reviewed = {
            identity: {checksum}
            for identity, checksum in policy.REQUIRED_PLATFORM_ARTIFACTS.items()
        }
        policy.validate_required_platform_artifacts(reviewed)

        windows = next(identity for identity in reviewed if identity[-1].endswith("-windows.jar"))
        missing = dict(reviewed)
        del missing[windows]
        with self.assertRaisesRegex(policy.PolicyError, "not checksum-pinned"):
            policy.validate_required_platform_artifacts(missing)

        changed = dict(reviewed)
        changed[windows] = {"0" * 64}
        with self.assertRaisesRegex(policy.PolicyError, "not checksum-pinned"):
            policy.validate_required_platform_artifacts(changed)


class RuntimeClosureCompatibilityTest(unittest.TestCase):
    def test_metadata_extension_is_all_or_none(self):
        base = {"legacy": 1}
        extension = {"first", "second"}
        self.assertFalse(runtime.has_complete_extension(base, {"legacy"}, extension, "fixture"))
        self.assertTrue(
            runtime.has_complete_extension(
                {**base, "first": 1, "second": 2},
                {"legacy"},
                extension,
                "fixture",
            )
        )
        with self.assertRaisesRegex(ValueError, "incomplete"):
            runtime.has_complete_extension(
                {**base, "first": 1},
                {"legacy"},
                extension,
                "fixture",
            )

    def test_dependency_attestation_resolves_exact_virtual_provider(self):
        packages = [
            {
                "name": "app",
                "version": "1.0",
                "essential": False,
                "preDepends": [],
                "depends": [
                    ["lib+variant:any (>= 1.2) [aarch64]", "fallback"],
                    ["virtual-lib (>= 2.0)"],
                ],
                "provides": [],
                "resolvedDependencies": ["lib+variant"],
            },
            {
                "name": "lib+variant",
                "version": "2.1",
                "essential": False,
                "multiArch": "allowed",
                "preDepends": [],
                "depends": [],
                "provides": ["virtual-lib (= 2.1)"],
                "resolvedDependencies": [],
            },
        ]
        runtime.validate_dependency_closure(packages, ["app"], "aarch64")
        self.assertEqual(
            ("virtual-lib", True, False, "=", "2.1"),
            runtime.parsed_debian_dependency(
                "virtual-lib (= 2.1)", "aarch64", provided=True
            ),
        )
        with self.assertRaisesRegex(ValueError, "expression"):
            runtime.parsed_debian_dependency(
                "lib+variant (=> 1.2)", "aarch64"
            )
        with self.assertRaisesRegex(ValueError, "native"):
            runtime.parsed_debian_dependency(
                "lib+variant:native", "aarch64"
            )
        packages[1]["version"] = "1.0"
        with self.assertRaisesRegex(ValueError, "no resolved edge"):
            runtime.validate_dependency_closure(packages, ["app"], "aarch64")
        packages[1]["version"] = "2.1"
        packages[1]["provides"] = ["virtual-lib (= 1.0)"]
        with self.assertRaisesRegex(ValueError, "no resolved edge"):
            runtime.validate_dependency_closure(packages, ["app"], "aarch64")
        self.assertNotEqual(
            runtime.spdx_package_id("lib+variant"),
            runtime.spdx_package_id("lib-variant"),
        )

    def test_debian_version_order_matches_epoch_tilde_revision_and_numeric_rules(self):
        ordered = (
            ("1.0~rc1-1", "1.0-1"),
            ("1.0-1", "1.0-2"),
            ("1.0-2", "1.0-10"),
            ("2.0-99", "1:1.0-1"),
        )
        for lower, higher in ordered:
            self.assertLess(runtime.compare_debian_versions(lower, higher), 0)
            self.assertGreater(runtime.compare_debian_versions(higher, lower), 0)
        self.assertEqual(
            0,
            runtime.compare_debian_versions("1:01.002-03", "1:1.2-3"),
        )

    def test_full_source_verifier_accepts_complete_additive_closure(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture_root = Path(temporary) / "app" / "src" / "main" / "agent-fleet"
            fixture_pins = Path(temporary) / "app" / "runtime-pins"
            shutil.copytree(ROOT / "app" / "src" / "main" / "agent-fleet", fixture_root)
            shutil.copytree(ROOT / "app" / "runtime-pins", fixture_pins)

            lock_path = fixture_root / "termux-packages-aarch64.json"
            lock = json.loads(lock_path.read_text(encoding="utf-8"))
            lock["runtimeRootsSha256"] = "ab" * 32
            lock["closureManifestFile"] = "agent-fleet-runtime-closure-v1.json"
            for item in lock["packages"]:
                item.update(
                    {
                        "essential": True,
                        "preDepends": [],
                        "depends": [],
                        "provides": [],
                        "resolvedDependencies": [],
                    }
                )
            lock["packages"].sort(key=lambda item: item["name"])
            closure = {
                "schemaVersion": 1,
                "architecture": "aarch64",
                "runtimeRootsSha256": lock["runtimeRootsSha256"],
                "rootPackages": lock["rootPackages"],
                "selectedPackages": [
                    {
                        "name": item["name"],
                        "file": item["file"],
                        "sha256": item["sha256"],
                        "size": item["size"],
                        "resolvedDependencies": [],
                    }
                    for item in lock["packages"]
                ],
                "selectedPayloadSize": lock["totalSize"],
                "excludedPackages": [],
                "ignoredFiles": [],
            }
            closure_payload = runtime.canonical_json(closure)
            closure_path = fixture_root / lock["closureManifestFile"]
            closure_path.write_bytes(closure_payload)
            lock["closureManifestSha256"] = hashlib.sha256(closure_payload).hexdigest()
            lock["closureManifestSize"] = len(closure_payload)
            lock_payload = runtime.canonical_json(lock)
            lock_path.write_bytes(lock_payload)

            sbom_path = fixture_root / "termux-packages-aarch64.spdx.json"
            sbom_payload = runtime.canonical_json(runtime.expected_spdx(lock))
            sbom_path.write_bytes(sbom_payload)

            descriptor_path = fixture_root / "embedded-runtime-v1.json"
            descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
            descriptor["packageLock"].update(
                {
                    "sha256": hashlib.sha256(lock_payload).hexdigest(),
                    "size": len(lock_payload),
                }
            )
            descriptor["sbom"].update(
                {
                    "sha256": hashlib.sha256(sbom_payload).hexdigest(),
                    "size": len(sbom_payload),
                }
            )
            descriptor_path.write_bytes(runtime.canonical_json(descriptor))

            for pin_path in fixture_pins.glob("agent-fleet-runtime-*.json"):
                pin = json.loads(pin_path.read_text(encoding="utf-8"))
                pin.update(
                    {
                        "runtimeRootsSha256": lock["runtimeRootsSha256"],
                        "closureManifestFile": lock["closureManifestFile"],
                        "closureManifestSha256": lock["closureManifestSha256"],
                        "closureManifestSize": lock["closureManifestSize"],
                        "excludedPackageCount": 0,
                    }
                )
                if pin["architecture"] == "aarch64":
                    pin.update(
                        {
                            "packageLockSha256": descriptor["packageLock"]["sha256"],
                            "packageLockSize": descriptor["packageLock"]["size"],
                            "sbomSha256": descriptor["sbom"]["sha256"],
                            "sbomSize": descriptor["sbom"]["size"],
                        }
                    )
                pin_path.write_bytes(runtime.canonical_json(pin))

            result = runtime.verify(fixture_root)
            self.assertEqual(84, result["packages"])


class DebugApkVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        (self.root / "app" / "build" / "outputs" / "apk" / "debug").mkdir(parents=True)
        (self.root / "app" / "version.properties").write_text(
            "VERSION_NAME=0.118.4-agentfleet.82\nVERSION_CODE=1096\n",
            encoding="utf-8",
        )
        self.output = self.root / "app" / "build" / "outputs" / "apk" / "debug"
        self.metadata = {
            "version": 3,
            "applicationId": "com.yaakovch.fleet",
            "variantName": "debug",
            "elements": [
                self.element("universal", ()),
                self.element("arm64-v8a", (("ABI", "arm64-v8a"),)),
                self.element("x86_64", (("ABI", "x86_64"),)),
            ],
        }
        for abi in ("universal", "arm64-v8a", "x86_64"):
            self.write_apk(abi)
        self.write_metadata()

    def tearDown(self):
        self.temporary.cleanup()

    @staticmethod
    def element(abi, filters):
        return {
            "outputFile": f"agent-fleet_debug_{abi}.apk",
            "versionName": "0.118.4-agentfleet.82",
            "versionCode": 1096,
            "filters": [
                {"filterType": filter_type, "value": value}
                for filter_type, value in filters
            ],
        }

    def write_apk(self, abi, duplicate=False):
        path = self.output / f"agent-fleet_debug_{abi}.apk"
        packaged_abis = ("arm64-v8a", "x86_64") if abi == "universal" else (abi,)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
                archive.writestr("classes.dex", b"dex")
                archive.writestr("assets/agent-fleet/embedded-runtime-v1.json", b"{}")
                for packaged_abi in packaged_abis:
                    archive.writestr(f"lib/{packaged_abi}/libtermux.so", b"native")
                if duplicate:
                    archive.writestr("classes.dex", b"other")

    def write_metadata(self):
        (self.output / "output-metadata.json").write_text(
            json.dumps(self.metadata),
            encoding="utf-8",
        )

    def test_accepts_complete_matching_apk_set(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            result = apks.verify(self.root, sdk_tools=False)
        self.assertEqual("com.yaakovch.fleet", result["applicationId"])
        self.assertEqual({"universal", "arm64-v8a", "x86_64"}, set(result["artifacts"]))

    def test_rejects_duplicate_archive_entries(self):
        self.write_apk("arm64-v8a", duplicate=True)
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(apks.ApkVerificationError, "duplicate entries"):
                apks.verify(self.root, sdk_tools=False)

    def test_rejects_metadata_that_mislabels_an_abi(self):
        self.metadata["elements"][1]["filters"][0]["value"] = "x86_64"
        self.write_metadata()
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(apks.ApkVerificationError, "filename mismatch"):
                apks.verify(self.root, sdk_tools=False)

    def test_rejects_apk_manifest_version_that_differs_from_release_binding(self):
        signature = mock.Mock(returncode=0, stderr="")
        badging = mock.Mock(
            returncode=0,
            stdout=(
                "package: name='com.yaakovch.fleet' versionCode='1096' "
                "versionName='0.118.4-agentfleet.81' compileSdkVersion='36'\n"
            ),
            stderr="",
        )
        with mock.patch.object(
            apks, "locate_build_tools", return_value=(Path("apksigner"), Path("aapt2"))
        ), mock.patch.object(apks.subprocess, "run", side_effect=(signature, badging)):
            with self.assertRaisesRegex(apks.ApkVerificationError, "manifest identity/version mismatch"):
                apks.verify_android_identity(
                    {"universal": Path("agent-fleet.apk")},
                    "0.118.4-agentfleet.82",
                    1096,
                    Path("sdk"),
                )


if __name__ == "__main__":
    unittest.main()
