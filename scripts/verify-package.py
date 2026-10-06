#!/usr/bin/env python3
"""Verify the installable candidate, including plugin discovery and its runtime libraries."""
import argparse
import io
from pathlib import Path, PurePosixPath
import stat
import zipfile


def verify(path):
    root = 'plugins/transforms/hop-sdmx/'
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, 'Corrupt ZIP'
        names = archive.namelist()
        for item in archive.infolist():
            name = item.filename
            assert not PurePosixPath(name).is_absolute() and '..' not in PurePosixPath(name).parts
            assert not stat.S_ISLNK(item.external_attr >> 16), 'Symlink in ZIP'
            assert name.startswith(root) or name.rstrip('/') in ('plugins', 'plugins/transforms'), name
        jars = [n for n in names if n.endswith('.jar')]
        for required in ['hop-transform-sdmx', 'hop-sdmx-core', 'jackson-databind', 'jackson-core', 'jackson-annotations', 'commons-csv', 'commons-io', 'commons-codec']:
            assert len([n for n in jars if Path(n).stem == required or Path(n).name.startswith(required + '-')]) == 1, required
        assert not any(Path(n).name.startswith(('hop-core-', 'hop-engine-', 'hop-ui-', 'org.eclipse.swt')) for n in jars), jars
        plugin = next(n for n in jars if Path(n).name.startswith('hop-transform-sdmx-'))
        with zipfile.ZipFile(io.BytesIO(archive.read(plugin))) as jar:
            for required in ['META-INF/jandex.idx', 'ch/so/agi/hop/sdmx/SdmxInputMeta.class', 'ch/so/agi/hop/sdmx/SdmxInputDialog.class', 'ch/so/agi/hop/sdmx/sdmx.svg']:
                assert required in jar.namelist(), required
        assert root + 'LICENSE' in names
    print('Verified plugin ZIP:', path)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--zip', type=Path)
    args = parser.parse_args()
    files = [args.zip] if args.zip else list(Path('assemblies/assemblies-hop-sdmx/target').glob('hop-sdmx-plugin-*.zip'))
    assert len(files) == 1, f'Expected one ZIP, got {files}'
    verify(files[0])
