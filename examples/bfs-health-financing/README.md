# BFS health financing

Open `bfs-health-financing.hpl`. It reads `CH1.COU:DF_COU_HEALTH_FINANCING(1.0.0)` and writes CSV.
The saved schema contains Q, F, UNIT, FREQ, TIME_PERIOD, OBS_VALUE, MULT, OBS_STATUS, DECIMALS and
separate labels for coded components. Structure labels may change; field identifiers remain fixed.

Set pipeline parameters:

| Parameter | Default | Meaning |
| --- | --- | --- |
| `SDMX_ENDPOINT` | BFS REST endpoint | Normally unchanged |
| `START_PERIOD` | `2023` | Inclusive lower period |
| `END_PERIOD` | `2023` | Inclusive upper period |
| `FINANCING_SOURCES` | `Q_1` | Comma-separated source codes |
| `OUTPUT_FILE` | temporary-directory prefix | Absolute output prefix, without `.csv` |

The filter fixes scheme `F_1`, unit `CHF` and frequency `A`. Change these in the transform if needed.
Do not interpret OBS_VALUE without MULT and UNIT; values are not automatically scaled.

For Application Launcher, place the pipeline in the managed application's repository and expose
`START_PERIOD` and `END_PERIOD` as string fields using that repository's existing sidecar format.
No dynamic launcher choices or changes to the launcher plugin are needed. Its parameter values are
activated before execution. Keep the pipeline and downstream schema fixed.

Installed tests run this exact example against a local fixture server. Live values are not test goldens.
