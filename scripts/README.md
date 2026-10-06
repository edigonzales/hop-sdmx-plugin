# Scripts

- `build-and-install.sh [hop-directory]`: JDK 21 build and verified installation; defaults to `/Users/stefan/Downloads/hop`.
- `verify-package.py [--zip candidate.zip]`: inspect actual ZIP layout, classes, index and runtime dependencies.
- `run-e2e.py --hop-home DIR --plugin-zip ZIP`: deterministic installed tests using a disposable Hop distribution.
- `live-smoke.py --hop-home DIR`: optional BFS network check with the plugin already installed in a disposable distribution.
- `build-docs-site.py [--serve]`: build the current checkout with Biblios, validate it, optionally serve it.
- `check-docs-site.py DIR`: validate generated links, anchors, downloads, GUI CSS and search entries.
- `test-build-and-install.py`: exercise installer success and failure with fake build tools and disposable targets.

The installer uses `HOP_CI_DIR` (default sibling `hop-plugin-ci`) and prefers an existing JDK 21 in
`JAVA_HOME`. Otherwise it searches macOS Java homes and SDKMAN installations. It stages the verified
ZIP on the target filesystem and restores the old plugin if its final rename fails.
