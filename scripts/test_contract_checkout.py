"""Exercise Git's real clean/checkout behavior for byte-pinned release artifacts.

Only disposable Git indexes are used; the application's index is never changed.
"""
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = Path('src/main/resources/contracts')


class ContractCheckoutTest(unittest.TestCase):
    def test_approved_bytes_survive_git_normalization_and_checkout(self):
        manifest = json.loads((ROOT / CONTRACTS / 'COLLECTION_RELEASE.json').read_text())
        temporary_root = ROOT / 'target'
        temporary_root.mkdir(exist_ok=True)
        for autocrlf in ('false', 'true'):
            with self.subTest(autocrlf=autocrlf), tempfile.TemporaryDirectory(dir=temporary_root) as directory:
                scratch = Path(directory)
                self.assertTrue(scratch.resolve().is_relative_to(temporary_root.resolve()))
                repository = scratch / 'repository'
                checkout = scratch / 'checkout'
                repository.mkdir()
                checkout.mkdir()

                def git(*args):
                    return subprocess.check_output(
                        ['git', '-C', str(repository), '-c', 'core.autocrlf=' + autocrlf, *args],
                        stderr=subprocess.PIPE,
                    )

                git('init', '--quiet')
                (repository / '.gitattributes').write_bytes((ROOT / '.gitattributes').read_bytes())
                for name in manifest['files']:
                    target = repository / CONTRACTS / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes((ROOT / CONTRACTS / name).read_bytes())
                git('add', '--', '.gitattributes', CONTRACTS.as_posix())
                git('checkout-index', '--all', '--prefix=' + checkout.as_posix() + '/')
                for name, expected in manifest['files'].items():
                    relative = (CONTRACTS / name).as_posix()
                    self.assertEqual(expected, hashlib.sha256(git('show', ':' + relative)).hexdigest(), relative)
                    self.assertEqual(expected, hashlib.sha256((checkout / relative).read_bytes()).hexdigest(), relative)


if __name__ == '__main__':
    unittest.main()
