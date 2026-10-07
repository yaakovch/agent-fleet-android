#!/usr/bin/env python3
"""Merge a successful, protected Fleet generator report into app profiles."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def merge(paths: list[Path]) -> str:
    rules: dict[str, set[str]] = {}
    for path in paths:
        for line in path.read_text().splitlines():
            if not line or line.startswith('#'):
                continue
            match = re.fullmatch(r'([HSP]*)(L.+)', line)
            if not match:
                raise ValueError(f'Invalid rule in {path.name}')
            flags, rule = match.groups()
            if 'Lcom/termux/app/fleet/profile/' in rule:
                continue
            rules.setdefault(rule, set()).update(flags)
    return ''.join(''.join(flag for flag in 'HSP' if flag in flags) + rule + '\n' for rule, flags in sorted(rules.items()))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    raw = (args.report / 'instrumentation.txt').read_text()
    if 'OK (3 tests)' not in raw or 'INSTRUMENTATION_CODE: -1' not in raw or 'FAILURES!!!' in raw:
        raise SystemExit('A complete successful three-journey generator report is required')
    output = args.report / 'profile-output'
    prefix = 'FleetBaselineProfileGenerator_'
    startup = [output / (prefix + name + '-startup-prof.txt') for name in ('launcher', 'notificationEntry')]
    baseline = startup + [output / (prefix + 'switchingScrollingAndPreview-baseline-prof.txt')]
    provenance = {'schemaVersion': 1, 'generationReport': args.report.name, 'networkContent': False,
        'generatorApkSha256': hashlib.sha256((ROOT / 'profilegenerator/build/outputs/apk/benchmark/profilegenerator-benchmark.apk').read_bytes()).hexdigest(),
        'appApkSha256': hashlib.sha256((ROOT / 'app/build/outputs/apk/benchmark/app-benchmark.apk').read_bytes()).hexdigest(),
        'inputs': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in baseline}, 'outputs': {}}
    for name, paths in [('baseline-prof.txt', baseline), ('startup-prof.txt', startup)]:
        text = merge(paths)
        destination = ROOT / 'app/src/fleetProfiles' / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)
        provenance['outputs'][name] = {'sha256': hashlib.sha256(text.encode()).hexdigest(), 'rules': len(text.splitlines())}
    destination = ROOT / 'docs/performance/fleet-profile-generation-v1.json'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps(provenance['outputs']))


if __name__ == '__main__':
    main()
