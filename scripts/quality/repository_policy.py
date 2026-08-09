#!/usr/bin/env python3
"""Validate repository-local supply-chain and CI policy without network access."""

from __future__ import annotations

import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SHA256 = re.compile(r"[0-9a-f]{64}")
GIT_OBJECT = re.compile(r"[0-9a-f]{40}")
GRADLE_DISTRIBUTION_SHA256 = "89d4e70e4e84e2d2dfbb63e4daa53e21b25017cc70c37e4eea31ee51fb15098a"
GRADLE_WRAPPER_JAR_SHA256 = "e996d452d2645e70c01c11143ca2d3742734a28da2bf61f25c82bdc288c9e637"
DEPENDENCY_LOCKS = (
    "buildscript-gradle.lockfile",
    "app/gradle.lockfile",
    "terminal-emulator/gradle.lockfile",
    "terminal-view/gradle.lockfile",
    "termux-shared/gradle.lockfile",
)
LOCKED_COMPONENT = re.compile(
    r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([^\s=,+]+)=([A-Za-z0-9_.,-]+)"
)


class PolicyError(ValueError):
    """Raised when repository policy is incomplete or unsafe."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PolicyError(message)


def action_reference_error(reference: str) -> str | None:
    """Return a policy error for a mutable action/container reference."""
    if reference.startswith("./"):
        return None
    if reference.startswith("docker://"):
        image = reference.removeprefix("docker://")
        if not re.fullmatch(r"[^@\s]+@sha256:[0-9a-f]{64}", image):
            return f"container action is not pinned by sha256 digest: {reference}"
        return None
    if "@" not in reference:
        return f"action has no immutable revision: {reference}"
    revision = reference.rsplit("@", 1)[1]
    if not GIT_OBJECT.fullmatch(revision):
        return f"action is not pinned to a 40-character commit: {reference}"
    return None


def workflow_action_references(text: str) -> list[tuple[int, str]]:
    """Return every step action reference, including steps that also have a name."""
    references: list[tuple[int, str]] = []
    for line_number, line in enumerate(text.splitlines(), 1):
        match = re.match(r"\s*(?:-\s+)?uses:\s*([^\s#]+)", line)
        if match is not None:
            references.append((line_number, match.group(1)))
    return references


def workflow_container_references(text: str) -> list[tuple[int, str]]:
    """Return scalar job containers and container/service image fields."""
    references: list[tuple[int, str]] = []
    for line_number, line in enumerate(text.splitlines(), 1):
        match = re.match(r"\s*(?:-\s+)?(?:container|image):\s*([^\s#]+)", line)
        if match is not None:
            references.append((line_number, match.group(1)))
    return references


def container_reference_error(reference: str) -> str | None:
    if not re.fullmatch(r"[^@\s]+@sha256:[0-9a-f]{64}", reference):
        return f"container image is not pinned by sha256 digest: {reference}"
    return None


def validate_workflows(root: Path) -> None:
    workflow_dir = root / ".github" / "workflows"
    workflows = sorted((*workflow_dir.glob("*.yml"), *workflow_dir.glob("*.yaml")))
    require(bool(workflows), "repository has no GitHub Actions workflows")
    for path in workflows:
        text = path.read_text(encoding="utf-8")
        relative = path.relative_to(root)
        require(re.search(r"(?m)^permissions:\s*$", text) is not None, f"{relative} has no top-level permissions")
        require("pull_request_target:" not in text, f"{relative} uses pull_request_target")
        require(
            re.search(r"(?m)^\s*runs-on:\s+ubuntu-latest\s*$", text) is None,
            f"{relative} uses the mutable ubuntu-latest runner label",
        )
        require(
            re.search(r"(?m)(?:^|[^\w./-])(?:\./)?gradlew(?:\s|$)", text) is None,
            f"{relative} bypasses scripts/debug/android-gradle.sh",
        )
        for line_number, reference in workflow_action_references(text):
            error = action_reference_error(reference)
            require(error is None, f"{relative}:{line_number}: {error}")
        for line_number, reference in workflow_container_references(text):
            error = container_reference_error(reference)
            require(error is None, f"{relative}:{line_number}: {error}")

    unit_workflow = (workflow_dir / "run_tests.yml").read_text(encoding="utf-8")
    require(
        re.search(r"(?m)^\s*run:\s+bash scripts/quality-gate\.sh local\s*$", unit_workflow) is not None,
        "run_tests.yml must invoke the canonical local gate exactly",
    )
    emulator_workflow = (workflow_dir / "android_emulator.yml").read_text(encoding="utf-8")
    require(
        re.search(r"(?m)^\s*run:\s+bash scripts/quality-gate\.sh full\s*$", emulator_workflow) is not None,
        "android_emulator.yml must invoke the canonical full gate exactly",
    )
    require(
        re.search(r"(?m)^\s*AGENT_FLEET_EMULATOR_BACKEND:\s*managed\s*$", emulator_workflow) is not None,
        "the CI full gate must select the protected managed API 36 emulator",
    )
    release_workflow = (workflow_dir / "attach_debug_apks_to_release.yml").read_text(encoding="utf-8")
    require(
        "release delete" not in release_workflow and "push --delete" not in release_workflow,
        "the debug release workflow must not delete a published release or tag after a build failure",
    )
    require(
        '[[ "$RELEASE_VERSION_NAME" != "v$TRACKED_VERSION_NAME" ]]' in release_workflow
        and 'export TERMUX_APP_VERSION_NAME="$TRACKED_VERSION_NAME"' in release_workflow,
        "the debug release workflow must bind the release tag to the APK manifest version",
    )


def parse_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        require(bool(separator and key and value), f"invalid property in {path}: {raw_line}")
        require(key not in values, f"duplicate property in {path}: {key}")
        values[key] = value
    return values


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_wrapper(root: Path) -> None:
    wrapper = root / "gradle" / "wrapper"
    properties = parse_properties(wrapper / "gradle-wrapper.properties")
    require(
        properties.get("distributionUrl")
        == r"https\://services.gradle.org/distributions/gradle-8.11.1-all.zip",
        "Gradle distribution URL must remain the reviewed HTTPS 8.11.1 archive",
    )
    require(
        properties.get("distributionSha256Sum") == GRADLE_DISTRIBUTION_SHA256,
        "Gradle distributionSha256Sum does not match the reviewed archive",
    )
    checksum_file = wrapper / "gradle-wrapper.jar.sha256"
    fields = checksum_file.read_text(encoding="utf-8").strip().split()
    require(
        fields == [GRADLE_WRAPPER_JAR_SHA256, "gradle/wrapper/gradle-wrapper.jar"]
        and sha256(wrapper / "gradle-wrapper.jar") == GRADLE_WRAPPER_JAR_SHA256,
        "Gradle wrapper JAR does not match its repository checksum",
    )


def validate_dependency_metadata(root: Path) -> None:
    path = root / "gradle" / "verification-metadata.xml"
    require(path.is_file(), "Gradle dependency verification metadata is missing")
    tree = ET.parse(path)
    namespace = {"v": "https://schema.gradle.org/dependency-verification"}
    configuration = tree.find("v:configuration", namespace)
    require(configuration is not None, "dependency verification configuration is missing")
    require(
        configuration.findtext("v:verify-metadata", namespaces=namespace) == "true",
        "Gradle metadata verification must remain enabled",
    )
    components = tree.findall("v:components/v:component", namespace)
    require(len(components) >= 100, "dependency verification metadata is unexpectedly incomplete")
    identities: set[tuple[str, str, str, str]] = set()
    for component in components:
        coordinate = tuple(component.attrib.get(key, "") for key in ("group", "name", "version"))
        require(all(coordinate), "dependency component has an incomplete coordinate")
        artifacts = component.findall("v:artifact", namespace)
        require(bool(artifacts), f"dependency component has no artifacts: {':'.join(coordinate)}")
        for artifact in artifacts:
            name = artifact.attrib.get("name", "")
            identity = (*coordinate, name)
            require(bool(name) and identity not in identities, f"duplicate dependency artifact: {identity}")
            identities.add(identity)
            checksums = artifact.findall("v:sha256", namespace)
            require(bool(checksums), f"dependency artifact has no sha256: {identity}")
            require(
                all(SHA256.fullmatch(item.attrib.get("value", "")) is not None for item in checksums),
                f"dependency artifact has an invalid sha256: {identity}",
            )
    component_coordinates = {
        tuple(component.attrib[key] for key in ("group", "name", "version"))
        for component in components
    }
    require(
        not any(
            element.tag.rsplit("}", 1)[-1] in {"ignored-components", "ignored-dependencies"}
            for element in tree.iter()
        ),
        "Gradle dependency verification must not ignore components",
    )

    locked_components: set[tuple[str, str, str]] = set()
    for relative in DEPENDENCY_LOCKS:
        locked_components.update(parse_dependency_lock(root / relative))
    require(len(locked_components) >= 100, "Gradle dependency locks are unexpectedly incomplete")
    unverified = sorted(locked_components - component_coordinates)
    require(
        not unverified,
        f"locked Gradle component has no verification metadata: {':'.join(unverified[0]) if unverified else ''}",
    )

    root_build = (root / "build.gradle").read_text(encoding="utf-8")
    require(
        "resolutionStrategy.activateDependencyLocking()" in root_build
        and "lockAllConfigurations()" in root_build
        and "lockMode = LockMode.STRICT" in root_build
        and 'tasks.register("resolveAndLockAll")' in root_build,
        "Gradle dependency locking must cover buildscript and every project in strict mode",
    )

    forbidden_version = re.compile(
        r"""["'][^"']*:(?:latest(?:\.[A-Za-z]+)?|[^:"']*\+|[^:"']*-SNAPSHOT)["']""",
        re.IGNORECASE,
    )
    build_files: list[Path] = []
    for directory, children, files in os.walk(root):
        children[:] = [
            child
            for child in children
            if child not in {".git", ".gradle", ".idea", "build"}
        ]
        build_files.extend(Path(directory, name) for name in files if name.endswith(".gradle"))
    for build_file in sorted(build_files):
        text = build_file.read_text(encoding="utf-8")
        require(
            forbidden_version.search(text) is None,
            f"dynamic dependency version in {build_file.relative_to(root)}",
        )
        require(
            re.search(r"""maven\s*\{[^}]*url\s+["']http://""", text, re.DOTALL) is None,
            f"insecure Maven repository in {build_file.relative_to(root)}",
        )

    verification_override = re.compile(
        r"--dependency-verification(?:=|\s+)(?:off|lenient)\b",
        re.IGNORECASE,
    )
    for directory in (root / "scripts", root / ".github" / "workflows"):
        for candidate in sorted(
            path
            for path in directory.rglob("*")
            if path.is_file() and path.suffix in {".gradle", ".py", ".sh", ".yaml", ".yml"}
        ):
            require(
                verification_override.search(candidate.read_text(encoding="utf-8")) is None,
                f"dependency verification is weakened in {candidate.relative_to(root)}",
            )


def parse_dependency_lock(path: Path) -> set[tuple[str, str, str]]:
    require(path.is_file(), f"Gradle dependency lock is missing: {path}")
    lines = path.read_text(encoding="utf-8").splitlines()
    require(
        lines[:3]
        == [
            "# This is a Gradle generated file for dependency locking.",
            "# Manual edits can break the build and are not advised.",
            "# This file is expected to be part of source control.",
        ],
        f"Gradle dependency lock header is invalid: {path}",
    )
    rows = [line for line in lines[3:] if line and not line.startswith("#")]
    component_rows = [line for line in rows if not line.startswith("empty=")]
    require(bool(component_rows), f"Gradle dependency lock has no components: {path}")
    require(
        component_rows == sorted(component_rows) and len(component_rows) == len(set(component_rows)),
        f"Gradle dependency lock is not deterministically sorted: {path}",
    )
    coordinates: set[tuple[str, str, str]] = set()
    for row in component_rows:
        match = LOCKED_COMPONENT.fullmatch(row)
        require(match is not None, f"invalid Gradle dependency lock row in {path}: {row}")
        group, name, version, configurations = match.groups()
        require(
            "+" not in version and "snapshot" not in version.lower(),
            f"dynamic Gradle dependency lock version in {path}: {group}:{name}:{version}",
        )
        locked_configurations = configurations.split(",")
        require(
            locked_configurations == sorted(set(locked_configurations)),
            f"Gradle dependency lock configurations are not deterministic in {path}: {row}",
        )
        coordinate = (group, name, version)
        require(coordinate not in coordinates, f"duplicate Gradle dependency lock component in {path}: {row}")
        coordinates.add(coordinate)
    require(
        all(
            row == "empty=" or re.fullmatch(r"empty=[A-Za-z0-9_.,-]+", row) is not None
            for row in rows
            if row.startswith("empty=")
        ),
        f"invalid empty-configuration state in Gradle dependency lock: {path}",
    )
    return coordinates


def validate_project_policy(root: Path) -> None:
    license_text = (root / "LICENSE.md").read_text(encoding="utf-8")
    require("GPLv3 only" in license_text, "GPLv3-only repository license declaration is missing")
    provenance = (root / "PROVENANCE.md").read_text(encoding="utf-8")
    require(
        re.search(r"commit `[0-9a-f]{40}`", provenance) is not None,
        "upstream provenance must identify an exact commit",
    )
    for relative in (
        "AGENTS.md",
        "PRIVACY.md",
        "SECURITY.md",
        "docs/ANDROID_DEBUGGING.md",
        "scripts/debug/android-check.sh",
        "scripts/debug/android-gradle.sh",
    ):
        require((root / relative).is_file(), f"required repository policy file is missing: {relative}")


def validate(root: Path = ROOT) -> None:
    validate_workflows(root)
    validate_wrapper(root)
    validate_dependency_metadata(root)
    validate_project_policy(root)


def main() -> int:
    validate()
    print("repository policy and supply-chain pins verified")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ET.ParseError, PolicyError) as error:
        raise SystemExit(f"repository-policy: {error}")
