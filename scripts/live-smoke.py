#!/usr/bin/env python3
"""Optional small BFS live check; regular CI never invokes this script."""
import argparse
import csv
import importlib.util
from pathlib import Path
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--hop-home', type=Path, required=True)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('e2e', Path(__file__).with_name('run-e2e.py'))
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)
with tempfile.TemporaryDirectory(prefix='sdmx-live-') as temp:
    work = Path(temp)
    output = work / 'bfs'
    e2e.run(args.hop_home.resolve(), e2e.environment(work), output, 'https://disseminate.stats.swiss/rest')
    with output.with_suffix('.csv').open(newline='') as file:
        rows = list(csv.DictReader(file, delimiter=';'))
    assert rows and rows[0]['Q'] == 'Q_1' and rows[0]['TIME_PERIOD'] == '2023', rows
    assert rows[0]['MULT'] and rows[0]['Q_LABEL'], rows
    print('BFS live smoke passed:', len(rows), 'observations; fields:', ', '.join(rows[0]))
