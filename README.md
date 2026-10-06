# hop-sdmx-plugin

Apache Hop SDMX input with dynamic dataset discovery, parameterized filters and a saved output schema.

## Features

- Discover dataflows, dimensions, codelists and constraints from SDMX metadata.
- Searchable code selection, separate label fields and a 50-row preview.
- Hop variables for sources, filters, language and periods; compatible with Application Launcher.
- Streaming SDMX-CSV v2 observations, schema checks and cancellable requests.
- BFS Swiss Stats Explorer is the first verified provider.

## Requirements

Apache Hop **2.19.0**, Java **21** or **25**. Hop GUI requires SWT. Local pipeline engine only.
Uses SDMX REST v1, structure JSON 1.0 and data CSV 2; these are independent versions.

## Install

Unzip `hop-sdmx-plugin-0.1.0-SNAPSHOT.zip` into Hop and restart it.
The plugin installs under `plugins/transforms/hop-sdmx`; find **SDMX Input** in **Input**.

To build, verify and install locally:

```bash
./scripts/build-and-install.sh                     # /Users/stefan/Downloads/hop
./scripts/build-and-install.sh /another/hop-folder
```

The script selects JDK 21, generates Maven settings using the sibling `hop-plugin-ci` checkout,
verifies the ZIP and replaces only this plugin directory. It restores the previous directory if
installation fails. Restart Hop afterwards. Set `HOP_CI_DIR` for another shared-CI checkout.

## Documentation

- [Rendered handbook](https://edigonzales.github.io/hop-sdmx-plugin/)
- [Handbook sources](docs/index.adoc), [complete manual](docs/master.adoc)
- [Runnable example](examples/README.md), [installed tests](e2e/README.md)
- Local documentation: `python3 scripts/build-docs-site.py --serve`

## Build and development

See [AGENTS.md](AGENTS.md) for Maven settings and prerequisites.

```bash
mvn -s "$MAVEN_SETTINGS" -B -ntp clean verify
python3 scripts/verify-package.py
python3 "$HOP_CI_DIR/scripts/check-plugin-repository.py" --profile standard-plugin
```

Unit tests and installed E2E use local fixtures. [Scripts](scripts/README.md) include the optional live smoke test.

## Modules and artifacts

| Module | Purpose |
| --- | --- |
| `hop-sdmx-core` | HTTP, query construction, metadata and streaming observations |
| `hop-transform-sdmx` | Hop transform, saved metadata and SWT dialog |
| `assemblies/assemblies-hop-sdmx` | `ch.so.agi:hop-sdmx-plugin:0.1.0-SNAPSHOT:zip` |

## CI and publication

Uses `edigonzales/hop-plugin-ci` at `main`, profile `standard-plugin`, and the shared Maven parent.
Java 21/25 are tested on Linux, macOS and Windows. Ubuntu/Java 21 produces the canonical ZIP;
installed E2E consumes that exact ZIP. Snapshot publication from `main` requires verification and
E2E. Pull requests never publish. Biblios builds the exact checkout and publishes Pages from `main`.

## License

See [LICENSE](LICENSE).
