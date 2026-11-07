"""Offline archive/cache contracts; real SDK recognition is checked during CI setup."""
from dataclasses import replace
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('emulator_sdk', ROOT / '.github/emulator-sdk.py')
sdk = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = sdk
SPEC.loader.exec_module(sdk)


def archive_bytes(revision='37.2.13', extra=None):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        binary = zipfile.ZipInfo('emulator/emulator')
        binary.external_attr = 0o100755 << 16
        archive.writestr(binary, b'fake emulator executable')
        archive.writestr('emulator/source.properties', 'Pkg.Revision=' + revision + '\n')
        if extra:
            archive.writestr(*extra)
    return output.getvalue()


def package_for(data, revision='37.2.13'):
    return sdk.Package(revision, sdk.REPOSITORY + 'emulator-linux_x64-123.zip',
                       hashlib.sha1(data).hexdigest(), len(data))


def catalog_entry(revision, channel='channel-0', host='linux', checksum='a' * 40):
    major, minor, micro = revision.split('.')
    return f'''<remotePackage path="emulator"><revision><major>{major}</major>
      <minor>{minor}</minor><micro>{micro}</micro></revision><channelRef ref="{channel}"/>
      <archives><archive><host-os>{host}</host-os><host-bits>64</host-bits><complete>
      <size>123</size><checksum>{checksum}</checksum><url>emulator-linux_x64-123.zip</url>
      </complete></archive></archives></remotePackage>'''


class EmulatorSdkTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = self.root / 'cache/emulator.zip'
        self.data = archive_bytes()
        self.package = package_for(self.data)

    def fetcher(self, *responses):
        queue = iter(responses)
        calls = []

        def fetch(url, destination, limit, fresh):
            self.assertEqual(self.package.url, url)
            self.assertEqual(self.package.size, limit)
            self.assertFalse(destination.exists(), 'A failed partial download must be removed')
            calls.append(fresh)
            response = next(queue)
            if isinstance(response, Exception):
                destination.write_bytes(b'partial')
                raise response
            destination.write_bytes(response)
        return fetch, calls

    def test_catalog_chooses_latest_stable_linux_archive(self):
        xml = '<repository>' + ''.join((catalog_entry('38.0.0', 'channel-2'),
            catalog_entry('37.2.12'), catalog_entry('37.2.13'),
            catalog_entry('39.0.0', host='macosx'))) + '</repository>'
        chosen = sdk.available_package(xml)
        self.assertEqual('37.2.13', chosen.revision)
        self.assertEqual(sdk.REPOSITORY + 'emulator-linux_x64-123.zip', chosen.url)
        self.assertEqual(123, chosen.size)

    def test_catalog_requires_checksum_and_a_repository_archive_name(self):
        for invalid in (catalog_entry('37.2.13', checksum=''),
                        catalog_entry('37.2.13').replace('emulator-linux_x64-123.zip', '../outside.zip')):
            with self.subTest(invalid=invalid), self.assertRaises(sdk.ArchiveError):
                sdk.available_package('<repository>' + invalid + '</repository>')

    def test_local_sdk_metadata_preserves_the_official_license_and_revision(self):
        entry = catalog_entry('37.2.13').replace('<archives>', '<uses-license ref="sdk-license"/><archives>')
        package = sdk.available_package('<repository><license id="sdk-license" type="text">SDK terms</license>'
                                        + entry + '</repository>')
        root = sdk.ET.fromstring(package.metadata)
        self.assertEqual('SDK terms', sdk.child(root, 'license').text)
        local = sdk.child(root, 'localPackage')
        self.assertEqual('emulator', local.get('path'))
        self.assertEqual('sdk-license', sdk.child(local, 'uses-license').get('ref'))
        self.assertEqual('13', sdk.value(sdk.child(local, 'revision'), 'micro'))

    def test_cache_identity_changes_with_revision_and_checksum(self):
        self.assertNotEqual(self.package.key, replace(self.package, revision='37.2.14').key)
        self.assertNotEqual(self.package.key, replace(self.package, sha1='b' * 40).key)

    def test_catalog_retry_emits_the_resolved_identity_and_refreshes_its_request(self):
        xml = ('<repository>' + catalog_entry('37.2.13') + '</repository>').encode()
        with patch.dict(os.environ, {'RUNNER_TEMP': str(self.root), 'GITHUB_OUTPUT': str(self.root / 'outputs')}), \
                patch.object(sys, 'argv', ['emulator-sdk.py', 'resolve']), \
                patch.object(sdk, 'urlopen', side_effect=[io.BytesIO(b'broken XML'), io.BytesIO(xml)]) as fetch, \
                patch.object(sdk.time, 'sleep'), patch.object(sys, 'stdout', io.StringIO()):
            sdk.main()
        self.assertEqual(2, fetch.call_count)
        self.assertEqual('no-cache', fetch.call_args_list[1].args[0].get_header('Cache-control'))
        self.assertIn('revision=37.2.13\n', (self.root / 'outputs').read_text())
        self.assertTrue((self.root / 'localica-emulator-sdk/state.json').is_file())

    def test_verified_cache_hit_never_downloads(self):
        self.path.parent.mkdir()
        self.path.write_bytes(self.data)
        fetch = Mock(side_effect=AssertionError('Unexpected network download'))
        self.assertEqual({'cache_valid': True, 'download_attempts': 0},
                         sdk.ensure_archive(self.package, self.path, fetch))
        fetch.assert_not_called()

    def test_corrupt_cached_archive_is_discarded_and_replaced(self):
        self.path.parent.mkdir()
        self.path.write_bytes(b'corrupt cache')
        fetch, calls = self.fetcher(self.data)
        result = sdk.ensure_archive(self.package, self.path, fetch)
        self.assertEqual({'cache_valid': False, 'download_attempts': 1}, result)
        self.assertEqual([False], calls)
        self.assertEqual(self.data, self.path.read_bytes())

    def test_corrupt_download_is_retried_once_and_only_verified_bytes_are_published(self):
        fetch, calls = self.fetcher(b'bad ZIP', self.data)
        self.assertEqual(2, sdk.ensure_archive(self.package, self.path, fetch)['download_attempts'])
        self.assertEqual([False, True], calls)
        self.assertEqual(self.data, self.path.read_bytes())
        self.assertFalse(self.path.with_suffix('.partial').exists())

    def test_interrupted_download_removes_partial_bytes_before_retry(self):
        fetch, calls = self.fetcher(OSError('connection interrupted'), self.data)
        sdk.ensure_archive(self.package, self.path, fetch)
        self.assertEqual([False, True], calls)

    def test_two_bad_downloads_fail_without_publishing_an_archive(self):
        fetch, calls = self.fetcher(b'bad ZIP', b'bad ZIP')
        with self.assertRaisesRegex(sdk.ArchiveError, 'two download attempts'):
            sdk.ensure_archive(self.package, self.path, fetch)
        self.assertEqual(2, len(calls))
        self.assertFalse(self.path.exists())
        self.assertFalse(self.path.with_suffix('.partial').exists())

    def test_matching_checksum_does_not_make_non_zip_bytes_installable(self):
        data = b'not an archive'
        package = package_for(data)
        self.path.parent.mkdir()
        self.path.write_bytes(data)
        with self.assertRaises(zipfile.BadZipFile):
            sdk.validate_archive(self.path, package)

    def test_wrong_package_revision_and_traversal_cannot_replace_existing_sdk(self):
        target = self.root / 'sdk/emulator'
        target.mkdir(parents=True)
        (target / 'retained').write_text('previous installation')
        for data in (archive_bytes('37.2.12'), archive_bytes(extra=('../escaped', b'unsafe'))):
            self.path.parent.mkdir(exist_ok=True)
            self.path.write_bytes(data)
            with self.subTest(data=data), self.assertRaises(sdk.ArchiveError):
                sdk.install_archive(package_for(data), self.path, self.root / 'sdk')
            self.assertEqual('previous installation', (target / 'retained').read_text())
            self.assertFalse((self.root / 'escaped').exists())

    def test_installation_preserves_binary_execution_and_checks_sdk_recognition(self):
        self.path.parent.mkdir()
        self.path.write_bytes(self.data)
        check = Mock(return_value=self.package.revision)
        sdk_root = self.root / 'sdk'
        self.assertEqual(self.package.revision,
                         sdk.install_archive(self.package, self.path, sdk_root, check))
        check.assert_called_once_with(sdk_root)
        self.assertTrue(os.access(sdk_root / 'emulator/emulator', os.X_OK))
        with self.assertRaisesRegex(sdk.ArchiveError, 'sdkmanager revision'):
            sdk.install_archive(self.package, self.path, sdk_root, lambda root: '37.2.12')

    def test_installed_revision_comes_from_sdkmanager(self):
        with patch.object(subprocess, 'check_output', return_value='Path | Version | Description\nemulator | 37.2.13 | Android Emulator\n'):
            self.assertEqual('37.2.13', sdk.installed_revision(self.root))

    def test_every_hosted_emulator_job_prepares_the_verified_sdk(self):
        count = 0
        for file in (ROOT / '.github/workflows').glob('*.yml'):
            workflow = yaml.safe_load(file.read_text())
            for name, job in workflow.get('jobs', {}).items():
                steps = job.get('steps', [])
                for index, step in enumerate(steps):
                    if step.get('uses', '').startswith('reactivecircus/android-emulator-runner@'):
                        with self.subTest(workflow=file.name, job=name):
                            self.assertTrue(any(s.get('uses') == './.github/actions/emulator-sdk-cache'
                                                for s in steps[:index]))
                        count += 1
        self.assertGreater(count, 0)


if __name__ == '__main__':
    unittest.main()
