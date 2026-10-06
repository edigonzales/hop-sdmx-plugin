# Repository instructions

Read the [shared CI contract](https://github.com/edigonzales/hop-plugin-ci/blob/main/docs/ci-contract.md)
and [repository contract](https://github.com/edigonzales/hop-plugin-ci/blob/main/docs/plugin-repository-contract.md).
Profile: `standard-plugin`. Workflows and helpers track `main`.

## Build and tests

Use Maven, Python 3, JDK 21; compatibility tests use JDK 25 on Linux/macOS/Windows.
Set `HOP_CI_DIR` to the sibling hop-plugin-ci checkout and generate Maven settings:
`python3 "$HOP_CI_DIR/scripts/write_maven_settings.py" --output "$MAVEN_SETTINGS"`.
Run from the repository root:

- Canonical: `mvn -s "$MAVEN_SETTINGS" -B -ntp clean verify`.
- Compatibility: `mvn -s "$MAVEN_SETTINGS" -B -ntp clean test`.
- Package: `python3 scripts/verify-package.py`.
- Contract: `python3 "$HOP_CI_DIR/scripts/check-plugin-repository.py" --profile standard-plugin`.
- Installed E2E: `python3 scripts/run-e2e.py --hop-home /absolute/disposable/hop --plugin-zip assemblies/assemblies-hop-sdmx/target/hop-sdmx-plugin-0.1.0-SNAPSHOT.zip`.
- Installer: `python3 scripts/test-build-and-install.py`; real installer tests use disposable targets.
- Local install on explicit request: `./scripts/build-and-install.sh [hop-directory]` (default `/Users/stefan/Downloads/hop`).
- Documentation: `python3 scripts/build-docs-site.py`.
- Optional live test: `python3 scripts/live-smoke.py --hop-home /absolute/disposable/hop`.

Installed tests require isolated Hop 2.19.0, never the user's installation. CI consumes the
canonical ZIP without rebuilding. Unit and installed tests use local HTTP fixtures; BFS is not
required by CI. Publication depends on verification and installed E2E. Do not change the launcher.
