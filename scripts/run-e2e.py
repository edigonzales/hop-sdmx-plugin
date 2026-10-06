#!/usr/bin/env python3
"""Install and execute the canonical SDMX plugin ZIP in a disposable Hop distribution."""
import argparse
import csv
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PLUGIN_ROOT = 'plugins/transforms/hop-sdmx'
PIPELINE = ROOT / 'examples/bfs-health-financing/bfs-health-financing.hpl'


def environment(work):
    config = work / 'config'
    target = config / 'metadata/pipeline-run-configuration/local.json'
    target.parent.mkdir(parents=True)
    target.write_text(json.dumps({'name': 'local', 'engineRunConfiguration': {'Local': {'rowset_size': '2', 'safe_mode': True}}, 'configurationVariables': []}))
    env = os.environ.copy()
    env['HOP_CONFIG_FOLDER'] = str(config)
    env['HOP_AUDIT_FOLDER'] = str(work / 'audit')
    if env.get('JAVA_HOME'):
        env['HOP_JAVA_HOME'] = env['JAVA_HOME']
    return env


def run(hop, env, output, endpoint, year='2023', success=True):
    command = ['bash', str(hop / 'hop-run.sh'), '-r', 'local', '-f', str(PIPELINE)]
    for k, v in {'SDMX_ENDPOINT': endpoint, 'START_PERIOD': year, 'END_PERIOD': year, 'OUTPUT_FILE': str(output)}.items():
        command += ['-p', f'{k}={v}']
    result = subprocess.run(command, cwd=hop, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    output.with_suffix('.log').write_text(result.stdout)
    if (result.returncode == 0) != success:
        raise AssertionError(f'Unexpected Hop result {result.returncode}:\n{result.stdout}')
    return result.stdout


class FixtureHandler(BaseHTTPRequestHandler):
    mode = 'normal'
    requests = []

    def do_GET(self):
        type(self).requests.append(self.path)
        assert 'application/vnd.sdmx.' in self.headers.get('Accept', '')
        if '/dataflow/' in self.path:
            body = (ROOT / 'e2e/fixtures/structure.json').read_bytes()
            if self.mode == 'schema-change':
                body = body.replace(b'Float', b'Decimal')
            content = 'application/vnd.sdmx.structure+json;version=1.0'
        elif '/data/' in self.path:
            query = parse_qs(urlsplit(self.path).query)
            year = query['startPeriod'][0]
            assert query['endPeriod'] == [year]
            assert '/Q_1.F_1.CHF.A?' in self.path
            assert 'labels=id' in self.headers['Accept']
            lines = (ROOT / 'e2e/fixtures/observations.csv').read_text().splitlines()
            body = ('\n'.join([lines[0]] + [line for line in lines[1:] if f',{year},' in line]) + '\n').encode()
            if self.mode == 'delete':
                body = body.replace(b',I,', b',D,')
            content = 'application/vnd.sdmx.data+csv;version=2'
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', content)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--hop-home', type=Path, required=True)
    parser.add_argument('--plugin-zip', type=Path, required=True)
    args = parser.parse_args()
    hop = args.hop_home.resolve()
    assert (hop / 'lib/core/hop-core-2.19.0.jar').is_file(), 'Expected Hop 2.19.0'
    assert hop != Path('/Users/stefan/Downloads/hop').resolve(), 'Tests require a disposable Hop installation'
    assert not (hop / PLUGIN_ROOT).exists(), 'SDMX already installed; use a fresh test installation'
    spec = importlib.util.spec_from_file_location('verify_package', ROOT / 'scripts/verify-package.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.verify(args.plugin_zip)
    with zipfile.ZipFile(args.plugin_zip) as archive:
        archive.extractall(hop)
    server = ThreadingHTTPServer(('127.0.0.1', 0), FixtureHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix='sdmx-installed-tests-') as tmp:
            work = Path(tmp)
            env = environment(work)
            endpoint = f'http://127.0.0.1:{server.server_port}/rest'
            for year in ('2023', '2024'):
                prefix = work / ('observations-' + year)
                run(hop, env, prefix, endpoint, year)
                with prefix.with_suffix('.csv').open(newline='') as file:
                    rows = list(csv.DictReader(file, delimiter=';'))
                assert len(rows) == 1, rows
                row = rows[0]
                assert row['TIME_PERIOD'] == year and row['Q'] == 'Q_1' and row['Q_LABEL'] == 'Bund', row
                assert row['MULT'] == '6' and row['OBS_STATUS'] == 'A', row
                if year == '2023':
                    assert float(row['OBS_VALUE']) == 397.708, row
                else:
                    assert row['OBS_VALUE'] == '', row
                print('Installed parameter/CSV/label test passed:', year)
            FixtureHandler.mode = 'schema-change'
            FixtureHandler.requests.clear()
            log = run(hop, env, work / 'changed', endpoint, success=False)
            assert 'schema changed' in log, log
            assert not any('/data/' in p for p in FixtureHandler.requests)
            FixtureHandler.mode = 'delete'
            log = run(hop, env, work / 'delete', endpoint, success=False)
            assert 'Unsupported SDMX action D' in log, log
            print('Installed schema and deletion rejection passed')
            # Compile only a test harness, never rebuild the candidate plugin.
            classpath = os.pathsep.join(str(p) for p in sorted((hop / 'lib').rglob('*.jar')))
            javac = str(Path(env['JAVA_HOME']) / 'bin/javac') if env.get('JAVA_HOME') else 'javac'
            java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else 'java'
            subprocess.run([javac, '-proc:none', '-cp', classpath, '-d', str(work), str(ROOT / 'e2e/InstalledCancellation.java')], check=True)
            result = subprocess.run([java, '-cp', str(work) + os.pathsep + classpath, 'InstalledCancellation', str(ROOT), str(work)], cwd=hop, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)
            assert result.returncode == 0, result.stdout
            print('Installed cancellation harness passed')
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
    print('Installed SDMX E2E passed')


if __name__ == '__main__':
    main()
