#!/usr/bin/env python3
"""Test installer transaction boundaries without compiling again or touching a real Hop directory."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='sdmx-installer-test-')
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.repo = self.work / 'repository with spaces'
        (self.repo / 'scripts').mkdir(parents=True)
        for script in ['build-and-install.sh', 'verify-package.py']:
            shutil.copy2(ROOT / 'scripts' / script, self.repo / 'scripts' / script)
        archive = next((ROOT / 'assemblies/assemblies-hop-sdmx/target').glob('hop-sdmx-plugin-*.zip'))
        dest = self.repo / 'assemblies/assemblies-hop-sdmx/target'
        dest.mkdir(parents=True)
        shutil.copy2(archive, dest / archive.name)
        self.hop = self.work / 'test hop'
        (self.hop / 'lib/core').mkdir(parents=True)
        (self.hop / 'lib/core/hop-core-2.19.0.jar').touch()
        self.plugin = self.hop / 'plugins/transforms/hop-sdmx'
        self.plugin.mkdir(parents=True)
        (self.plugin / 'previous.txt').write_text('previous version')
        self.other = self.hop / 'plugins/transforms/another-plugin'
        self.other.mkdir()
        (self.other / 'keep.txt').write_text('keep')
        tools = self.work / 'tools'
        tools.mkdir()
        for name, script in {
            'mvn': '#!/bin/bash\nexit "${FAKE_BUILD_EXIT:-0}"\n',
            'mv': '#!/bin/bash\nif [[ ${FAIL_RENAME:-0} == 1 && "${2:-}" == *".hop-sdmx-install."*"/plugins/transforms/hop-sdmx" ]]; then exit 1; fi\nexec /bin/mv "$@"\n',
        }.items():
            (tools / name).write_text(script)
            (tools / name).chmod(0o755)
        jdk = self.work / 'jdk/bin'
        jdk.mkdir(parents=True)
        for name in ('java', 'javac'):
            (jdk / name).write_text('#!/bin/bash\nprintf \'openjdk version "21.0.1"\\n\' >&2\n')
            (jdk / name).chmod(0o755)
        ci = self.work / 'ci/scripts'
        ci.mkdir(parents=True)
        (ci / 'write_maven_settings.py').write_text('import pathlib,sys\npathlib.Path(sys.argv[2]).write_text("<settings/>")\n')
        self.env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ['PATH'], JAVA_HOME=str(jdk.parent), HOP_CI_DIR=str(ci.parent))

    def install(self, **env):
        return subprocess.run(['bash', str(self.repo / 'scripts/build-and-install.sh'), str(self.hop)], env=dict(self.env, **env), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def test_success_replaces_only_sdmx(self):
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertFalse((self.plugin / 'previous.txt').exists())
        self.assertTrue(list(self.plugin.glob('hop-transform-sdmx-*.jar')))
        self.assertEqual((self.other / 'keep.txt').read_text(), 'keep')

    def test_build_failure_leaves_previous_plugin(self):
        result = self.install(FAKE_BUILD_EXIT='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.plugin / 'previous.txt').read_text(), 'previous version')

    def test_failed_rename_rolls_back(self):
        result = self.install(FAIL_RENAME='1')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertEqual((self.plugin / 'previous.txt').read_text(), 'previous version')

    def test_rejects_symlink_target(self):
        shutil.rmtree(self.plugin)
        self.plugin.symlink_to(self.other, target_is_directory=True)
        result = self.install()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.other / 'keep.txt').read_text(), 'keep')


if __name__ == '__main__':
    unittest.main()
