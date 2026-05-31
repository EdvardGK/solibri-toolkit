# Solibri superuser playbook

How to drive Solibri through Claude. The model + tooling let Claude **read state, run
checking, assess issues, drive selection/views, create coordination issues, and read
rulesets** — and reason over all of it. What's *not* possible over the API (ruleset
authoring) is called out with the real alternative each time.

Quick mental model of where each capability lives:

| Want to… | Use | Channel |
|---|---|---|
| **Start/stop a Solibri instance** | `cli.py launch` / `shutdown` / `up` | process / plugin |
| **Read model / results / rulesets / classifications** | `cli.py su model\|results\|rulesets\|classifications` | **plugin 10999** |
| **Open a project/model in-JVM** | `cli.py su open PATH` | plugin 10999 🔴 |
| See what's open / loaded | `cli.py status`, `models`, `about` | REST (read) |
| Execute the loaded ruleset | `cli.py check --yes` | REST POST /checking 🔴⏳ |
| Assess issues | `cli.py assess` | REST GET /bcfxml (read) |
| Read which rules are in a ruleset | `cli.py ruleset PATH.cset` | file (read) |
| Select / highlight components | `cli.py select GUID…` | REST POST /selectionBasket 🔴 |
| Get/set the 3D camera | `cli.py camera` / client.set_camera | REST 🔴 |
| Create a coordination issue | client.create_presentation + add_slide | REST 🔴 |
| Query elements by property | IfcOpenShell on the IFC | local Python |
| Author/edit a rule | Solibri UI, or SDK (`C:\dev\solibri-bim-tools`) | not REST |
| Check IDS / data quality | `ifctester` | no Solibri needed |

Run from `C:\workspace\toolkit\solibri`. Disruptive commands (🔴) mutate the live session;
restore state afterward (e.g. `select-clear`).

---

## 0. Start / stop an instance (lifecycle)

The Skiplum plugin (port **10999**) starts on **every** Solibri launch and, as of v6, needs
nothing from the built-in 10876 server — so a plain launch gives a fully working API.

```bash
python cli.py up                  # which servers are up + what's open
python cli.py launch              # start Solibri, wait for the plugin (10999) to answer
python cli.py launch --api        # ...and also bring up the legacy 10876 server
python cli.py launch --open C:\...\KSV_KvA.smc   # start, then open a project in-JVM
python cli.py shutdown            # graceful via 10876 if up, else taskkill
python cli.py shutdown --force    # taskkill /F
```

`launch` refuses if an instance is already up (a second one can't bind 10999).

**Plugin commands (10999) — work on any launch, no `.bat`:**
```bash
python cli.py su model            # open project: name, busy, submodels, ruleset ids
python cli.py su results --json   # checking results {count, skipped, results}
python cli.py su rulesets --json  # rulesets (--json = full rule tree via toJson)
python cli.py su components --pset G55_Prosjektinfo --prop MMI --op prefix --value 7
python cli.py su open PATH        # .smc/.smcx = open project; .ifc = add to open project 🔴
```

**Headless?** Not currently. The plugin is an SDK *View*, so it only loads when the Solibri
GUI starts; there is no supported headless mode that keeps the API alive. `launch` starts
the normal app (a window appears). True headless is an open question — would need a
non-View startup hook or a separate launcher Solibri doesn't document.

## 1. Run checking and assess (the core loop)

```bash
python cli.py check --yes      # runs ruleset, returns result rows, then summarises issues
```
Read-only assessment of the *current* issues without re-running:
```bash
python cli.py assess --json
```
Assessment groups by severity/type, **clash discipline-pair** (e.g. `G55_RIE × G55_RIV`),
title group, and most-affected components. Use the delta between two `check` runs as the
feedback signal when tuning a ruleset.

## 2. The clash-matrix recipe — ignore clashes between same-property-value elements

Native, no build, uses the **Clash Detection Matrix** rule (already in your sets). General
form: classify by the property's value, then **disable the matrix diagonal** (value × value).

Concrete (the MMI case — ignore existing-vs-existing, keep new-vs-existing):
1. Classification on `G55_Prosjektinfo.MMI`: class **Existing** = `7*`, class **New** = rest.
2. Clash Detection Matrix rule using those classes as rows/columns.
3. Matrix cells: New×New on, New×Existing on, **Existing×Existing off**.

Can't be set via the API (no ruleset endpoint) — it's a ~2-min UI setup. If you need it
codified/version-controlled instead, build a custom SDK rule (see §5).

## 3. Drive selection and views

```bash
python cli.py selection                 # what's selected now
python cli.py select 3POXqHu... 1dOw4J... # highlight these IFC GUIDs
python cli.py select-clear              # restore
python cli.py camera                    # current camera, for snapshots/scripting
```
Selection GUIDs can come from the BCF assess output (affected components per issue).

## 4. Create coordination issues programmatically

```python
from solibri_loop import SolibriClient
c = SolibriClient()
pid = c.create_presentation("Auto-koordinering 2026-05")
c.add_slide({
    "presentationId": pid,
    "title": "RIE ↔ RIV clash, Plan 2",
    "description": "Auto-flagged: cable tray through duct.",
    "components": ["3POXqHuM96DwZzM2c25HCV", "..."],   # IFC GUIDs
    "status": "Open", "stage": "Error",
})
```
🔴 adds to the model — save with `c.save_project()` when intended.

## 5. Author a rule (when native config isn't enough)

The API can't author rulesets. Two routes:
- **UI** — fastest for one-offs and matrix/classification config.
- **SDK** (`C:\dev\solibri-bim-tools`) — Java rules extending `OneByOneRule`/`ConcurrentRule`,
  built with `mvn package` (pom outputs to `Solibri/SOLIBRI/lib` with the `SMC-Rule`
  manifest), loaded on Solibri restart, added to a ruleset in the UI. Use for logic the
  built-ins can't express. Note: a custom *clash* rule must do its own spatial candidate
  search (`AABBIntersectionFilter`) — `Component.getIntersections(other)` only confirms a
  given pair — so prefer the native Clash Detection Matrix when it suffices.

## 6. Query elements by property — outside Solibri

The REST component filter doesn't round-trip cleanly, so query the IFC directly:
```python
import ifcopenshell
m = ifcopenshell.open("model.ifc")
existing = [e for e in m.by_type("IfcElement")
            if str(get_pset_value(e, "G55_Prosjektinfo", "MMI") or "").startswith("7")]
```
Reuse the helpers in `C:\dev\solibri-bim-tools\python` / `ifc-workbench`.

## 7. IDS / data-quality checks — no Solibri

IDS is an open standard; run it with `ifctester` (IfcOpenShell). Reserve Solibri for the
geometry-aware work it alone does: clash, spatial access/clearance, geometric ITO.

---

## Guardrails

- Anything 🔴 mutates the live session. Prefer read-only; when you mutate selection/camera,
  restore it. `check`/`shutdown`/`project/*` are high-impact — confirm intent first.
- `check` can take minutes on large federated models and blocks the session while busy.
- The `.smc` Solibri reports in `/status` may be a working path; the real saved file for
  G55 is under ACCDocs `…/10027 - Grønland 55/2. Solibri/`.
