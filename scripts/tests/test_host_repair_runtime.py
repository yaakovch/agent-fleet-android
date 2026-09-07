import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('host_repair_verifier', ROOT / 'scripts/runtime/verify-embedded-runtime.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class HostRepairRuntimeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assets = ROOT / 'app/src/main/agent-fleet'
        cls.descriptor = json.loads((assets / 'embedded-runtime-v1.json').read_text())
        cls.repair = json.loads((assets / 'host-repair-runtime-v1.json').read_text())
        cls.payload = (assets / cls.repair['file']).read_bytes()
        with tarfile.open(assets / cls.descriptor['runtime']['file']) as archive:
            cls.manifest = json.load(archive.extractfile('runtime-manifest.json'))

    def test_portable_archive_matches_the_android_runtime_source(self):
        verifier.verify_host_repair_payload(self.repair, self.payload, self.descriptor, self.manifest)

    def test_tampered_archive_and_unexpected_filename_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'artifact failed'):
            verifier.verify_host_repair_payload(self.repair, self.payload + b'changed', self.descriptor, self.manifest)
        with self.assertRaisesRegex(ValueError, 'descriptor is invalid'):
            verifier.host_repair_filename({**self.repair, 'file': '../unexpected.tar'}, self.descriptor)

    def test_other_source_components_and_nonportable_targets_are_rejected(self):
        for field, replacement in (
            ('source', {**self.manifest['source'], 'commit': '0' * 40}),
            ('components', {**self.descriptor['components'], 'hostRuntime': {'sequence': 9999, 'version': 'git-fffffff'}}),
            ('target', {'platform': 'linux', 'architecture': 'universal', 'prefix': '/home/another-user/.local'}),
        ):
            with self.subTest(field=field):
                result = io.BytesIO()
                with tarfile.open(fileobj=io.BytesIO(self.payload)) as source, tarfile.open(fileobj=result, mode='w') as target:
                    for item in source.getmembers():
                        payload = source.extractfile(item).read()
                        if item.name == 'runtime-manifest.json':
                            manifest = json.loads(payload)
                            manifest[field] = replacement
                            payload = json.dumps(manifest).encode()
                        info = copy.copy(item)
                        info.size = len(payload)
                        target.addfile(info, io.BytesIO(payload))
                payload = result.getvalue()
                metadata = {**self.repair, 'size': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()}
                with self.assertRaisesRegex(ValueError, 'does not match'):
                    verifier.verify_host_repair_payload(metadata, payload, self.descriptor, self.manifest)


if __name__ == '__main__':
    unittest.main()
