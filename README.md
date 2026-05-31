# solibri-loop

Drive Solibri's **native rule checking** in an `edit → execute → assess → refine` feedback
loop, over the live Solibri REST API (default `http://localhost:10876`).

**Two channels.** The toolkit talks to Solibri over two HTTP servers:
- **Skiplum plugin — port 10999** (preferred). An in-JVM server that starts on *every*
  Solibri launch and needs no special flags. Lists rulesets + full rule trees, reads
  checking results, queries components, opens projects in-JVM. Driven by `cli.py su …` and
  the lifecycle commands. See [SUPERUSER.md](docs/SUPERUSER.md).
- **Built-in REST — port 10876** (legacy). Only up when Solibri is launched with
  `--rest-api-server*` flags. Used by the bare `cli.py` commands below.

## Why this exists

Solibri's unique value over open-source IFC tooling is **geometry-aware checking**:

- **Clash / intersection control** (`GeometricIntersectionConstraint`, `GeneralInterferenceConstraint`)
- **Spatially-aware access / clearance rules** (`ComponentDistanceRule`, `FreeAreaInFrontOfComponentsRule`)
- **Geometric ITO** (information takeoff over geometry)

**Classification** is the enabling tool that targets these rules at the right elements.

Data/property checks (**IDS**) do **not** need Solibri — run those with
[`ifctester`](https://docs.ifcopenshell.org/) (IfcOpenShell) in pure Python. So this loop
deliberately targets the `.cset` native geometry rulesets, not IDS.

## Verified API capability map (Solibri 26.4.1)

| Stage | Mechanism | Status |
|---|---|---|
| **Execute** | `POST /solibri/v1/checking` | ✅ confirmed |
| **Assess** | `GET /solibri/v1/bcfxml/two_one` → `{content: base64(BCF zip)}` | ✅ confirmed live |
| **Read rulesets** | parse `.cset` / `.smc` serialized blob (no API endpoint exists) | ✅ confirmed |
| **Edit rulesets** | `.cset` is serialized Java — patch scalar params, or use SDK/UI | ⚠️ see below |
| **Load ruleset** | no REST endpoint; configure in UI, or reopen a `.smc` | ⚠️ open question |

What the REST API does **not** provide: listing/loading rulesets, or reading checking
results via `GET` (`GET /checking` is `405` — POST-only). Results come back in the
`POST /checking` response body (`CheckingResultList`) and/or via the BCF export.

## File formats

Both are `ZIP { Model<n>.smc (Java-serialized object graph, magic 0xACED0005), <version> }`:

- `.cset` — a saved ruleset
- `.smc` — a project (model + checking state + plugin data)

Ruleset names, `.cset` filenames, rule titles and rule classes are stored as Java strings
inside the serialized blob. `solibri_loop/cset.py` extracts them (TC_STRING scan +
length-prefixed class-name extraction). **Editing** rule parameters means changing values
inside this graph: feasible in-place for same-byte-length scalars (tolerances, severities),
but structural changes need the Solibri SDK (`C:\dev\solibri-bim-tools`, Java rule classes)
or the UI.

## Usage

Lifecycle + plugin (port 10999 — works on any launch):
```bash
python cli.py up                     # which servers are up + what's open
python cli.py launch [--api] [--open PATH]   # start Solibri from scratch, wait for plugin
python cli.py shutdown [--force]     # stop Solibri (graceful via 10876, else taskkill)
python cli.py su model               # open project: name, busy, submodels, ruleset ids
python cli.py su results [--json]    # checking results {count, skipped, results}
python cli.py su rulesets [--json]   # rulesets (--json = full rule tree)
python cli.py su open PATH           # .smc/.smcx = open project; .ifc = add to project [DISRUPTIVE]
```

Built-in REST (port 10876):
```bash
python cli.py status                 # health + loaded models           [read-only]
python cli.py assess                 # pull live issues (BCF) + summarise [read-only]
python cli.py assess --json          # + raw JSON summary
python cli.py bcf out.bcfzip         # save live BCF export to a file    [read-only]
python cli.py ruleset PATH.cset      # read ruleset names/rules from a .cset or .smc
python cli.py ruleset PATH.cset --titles
python cli.py check --yes            # RUN checking then assess          [DISRUPTIVE]
```

`check` mutates the live Solibri session and may take minutes; it refuses without `--yes`.

## Package layout

```
solibri_loop/
  superuser.py SuperuserClient — the in-JVM plugin (port 10999, preferred)
  launch.py    start/stop a Solibri instance; wait for the plugin to come up
  client.py    SolibriClient — built-in REST wrapper (port 10876, legacy)
  bcf.py       parse BCF 2.1 zip → BcfIssue (topic + affected component IFC GUIDs)
  assess.py    assess_issues() / format_assessment() / diff_assessments()
  cset.py      read_ruleset_info() — ruleset metadata from .cset/.smc
  loop.py      run_cycle() / pull_and_assess() — the feedback loop
cli.py         command-line entry
```

## The feedback loop (programmatic)

```python
from solibri_loop import SolibriClient
from solibri_loop.loop import pull_and_assess, run_cycle

c = SolibriClient()
before = pull_and_assess(c)                       # snapshot current issues

def tweak_ruleset():
    ...  # patch a .cset tolerance / swap ruleset (edit stage)

after = run_cycle(c, edit_fn=tweak_ruleset, confirm=True, previous=before)
print(after.diff_vs_previous)                     # the feedback signal
```

## Open question (gates full automation of the edit stage)

The REST API has **no "import/load ruleset" endpoint**. So after editing a `.cset` on disk,
applying it to the running session needs either (a) checking re-reading referenced files at
run time, or (b) reopening a `.smc` that references the ruleset, or (c) a UI action. This is
verifiable with one experiment once an editable ruleset is pointed at a known path —
see the loop's `edit_fn` hook as the integration point.

## Live G55 project (as discovered)

Active model `G55_SAMLETMODELL_2026` (11 sub-models) has **4 rulesets** loaded:
`BIM Validation - Architectural`, `General Space Check`,
`Intersections Between Architectural Components`, `Skiplum_TMK_17.10.2025`.
The 42 current BCF topics are manual coordination slides (`TMK12_Plan2_*`), dominated by
`G55_RIE × G55_RIV` clashes — not raw rule output.
```
