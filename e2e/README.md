# Installed tests

Use an isolated Hop 2.19.0 installation. `scripts/run-e2e.py` refuses an already installed SDMX plugin.
It validates and installs the candidate ZIP, starts a local HTTP fixture server, and executes the
actual example pipeline with two period parameter overrides. It also checks incompatible schemas and
delete actions fail rather than producing valid observations. A host-compiled test harness also loads the SDMX transform exclusively from the installed ZIP,
activates parameters through the same engine factory as the launcher and stops a streaming pipeline.
No external network service is required.

```bash
python3 scripts/run-e2e.py --hop-home /tmp/isolated-hop/hop --plugin-zip assemblies/assemblies-hop-sdmx/target/hop-sdmx-plugin-0.1.0-SNAPSHOT.zip
```

Fixtures are minimal, authored examples of the documented BFS structure format. They intentionally
contain shuffled dimensions, unavailable codes, language fallback and a missing observation value.
