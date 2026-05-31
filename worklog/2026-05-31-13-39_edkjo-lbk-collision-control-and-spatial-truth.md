---
date: 2026-05-31
project: toolkit/solibri + solibri-bim-tools (LBK_Landbrukskvartalet)
duration_hours: ~7
co_author: claude-opus-4-7
---

# LBK collision-control session: reverse-engineering decisions + ARK as spatial truth

## What

Used a live LBK collision-control workflow as the forcing function to grow the toolkit
into actual coordination capability. From "open LBK from ACC and have a look" to: a
captured 8-item wishlist for what Skiplum collision-control should be, programmatic
ruleset authoring proven end-to-end (POST /rulesets/install), full federation property
catalog, ARK-as-spatial-truth assignment for 132k components, and live working slides in
Solibri with correct metadata + per-slide camera-fit snapshots.

## Arc

### 1. Open + survey LBK
- Found `LBK_C_SAMLETMODELL_Ed.smc` on ACC (`10004 - Landbrukskvartalet/2. Solibri/`, 707 MB).
  Opened via toolkit (`cli.py launch --open …`) in ~3m41s first time, ~1m15s warm.
- Snapshot via plugin: 13 submodels, 3 rulesets (active = Skiplum_TMK), 4 classifications
  (incl. project-local `LBK_Bygg C_Etasjer`), 22 presentations / 537 slides, 3 ITO
  definitions, **115,892 saved checking results** across **78 rules**.
- Update-check on linked models: all 13 source IFCs older than the project save
  (2026-05-29 15:30) - no updates available.

### 2. Reverse-engineering edkjo's flagging logic from real data
- Slide.getComponents() exposed only the active TMK12_PlanU1 round (171 GUIDs). Switched
  to `/bcfxml/export` + BCF zip parse for the full curation: **3,349 flagged GUIDs across
  64 BCF-typed topics** (mostly C_TMK10_Plan6 / current round).
- Flag rate inside Plan 6 scope: 7.44% (3,486 of 46,871). Severity **inverted**:
  MODERATE/LOW flag rate > CRITICAL by ~3x.
- High-precision rule families (≥85% flag): `RIVv-RIVr`, `3.14 ARK-RIVr objekter foran
  dører/vinduer`. Mid (20-40%): `Kollisjoner VVS-komponenter`, `Kollisjoner isolasjon mot
  VVS`, `RIVr`, `3.9 ARK Vegger - RIVr`. Effectively-zero on Plan 6: ARK-RIB structural
  intersections (1.x), anything-vs-LARK, RIB-RIVv `7.1 Bjelker`.

### 3. Heuristics edkjo articulated, captured to memory
Saved [[skiplum-vs-vedal-rulesets]] with edkjo's primary check matrix:
- **Vedal is the default**, Skiplum RIx-* only when split HVAC (LBK has just RIVv+RIVr -> Vedal exclusive).
- Primary checks: MEP vs RIB **Bjelker/Søyler** (not Dekker) + ARK **Vegger/Himling**.
- ARK↔RIB slabs and anything ↔ LARK are by-design noise.
- **Clean through-and-throughs are not collisions; parallel-run intersections are**
  (sustained overlap of MEP alongside structure = real signal). Solibri's clash
  description encodes the intersection geometry (depth/width/height/volume) - the
  toolkit parses it into perpendicular_clean vs parallel_run_strong/likely.
- **PFV (ProvisionForVoid) mediation**: MEP-vs-structure clash is not real if a PFV at
  the same location authorises the passage AND the object is cleanly within the PFV.
  **Partial-overlap with a PFV IS signal** - either the PFV or the object is misplaced
  / wrong-sized. Built description-parser + Utsparinger-IFC bbox extractor; identified
  26 partial-overlap PFV suspects in `7.5. RIB Div - RIVv` (all unflagged).
- **Small-through-density rule**: small objects through structure are not collisions
  EXCEPT host material (steel/glass) AND density (N intersections within R area).

### 4. Ruleset authoring via API - reversed an earlier "not possible"
Earlier capability map claimed ruleset writing was impossible. **Wrong.** Found the path:
```
JSON -> RulesImporter(SModel, RSMBasedRuleProvider).importRuleSet(json)
     -> ConstraintModel -> CheckingPlugin.getInstance().addModel(cm) -> installed
```
Built `POST /rulesets/install` (handler `handleRulesetInstall`). SModel extracted via
`SMC.getModel() -> CoreModelWrapper.getCoreModel()` downcast (SModel extends
`sae.redox.Model`). PROVEN: cloned Skiplum_TMK (228 KB JSON, regenerated all 36-char
UUIDs) → installed as `Skiplum_TMK_Claude_v1`, `rulesetsBefore:3 -> after:4`. Wired
Python `SuperuserClient.install_ruleset(json_or_dict)` + CLI `su ruleset-install`.

First attempt routed JSON through `OpenRuleSetAction.openModel(File)` writing to a
`.cset` tmp file - that fails with `Read error / build number: ?` because `.cset`
expects Java-serialized binary, not JSON. Routing through `RulesImporter` directly is
the right architecture.

### 5. Save-as local copy for free experimentation
`cli.py su project-save-as` to `C:\tmp\lbk_claude\LBK_C_SAMLETMODELL_Ed_claude.smc`
(624 MB) - all subsequent experiments persist there; original on ACC untouched. Memory
[[feedback-move-fast-when-reversible]] extended: "don't be shy about restarting" -
edkjo's directive, no asking required.

### 6. Wishlist (8 items, captured to memory [[edkjo-collision-control-wishlist]])
1. **System × system precision** (not just model × model). Cross-model = cross-stakeholder;
   within-model = internal system coordination.
2. **Parallel-run rule** (wall vs MEP) as first-class.
3. **Discipline-tuned rules** - each leaf in ruleset = `(disciplineA, disciplineB, scope, tol)`.
4. **Classification-first targeting** for filtering.
5. **Ownership filter `LBK_Eier=False` / `LBK_Eies=False` = reference object, ignore**.
6. **Space-driven reports** via IfcSpace.
7. **Auto-assign issue ownership** based on involved disciplines.
8. **Full BCF/CDE sync**: import user groups, see all issues + comments + statuses
   committed to the CDE. Toolkit must distinguish "drafts in Solibri" from "committed."

### 7. Full IFC federation catalog (new `solibri_loop/ifc_catalog.py`)
13 models walked; per-model schema (IFC2X3/IFC4/IFC4X3 mix), IfcElement counts, pset
inventory with sample values. Output 13.8 MB JSON at
`C:\tmp\lbk_claude\lbk_federation_catalog.json`. Pinned property targets:
- **Ownership**: `LBK_ARK_C: LBK_Prosjektinfo.LBK_Eier` (22,924 True/False) +
  `LBK_Eies.LBK_Eies` (5,901). `LBK_C_BIMK_BrannisoleringHSQ`: all 254 components
  `Eies=False` (whole submodel is reference).
- **RIVr systems (GOLD)**: `MagiCAD Pset_Pipe.SystemType` on **27,159 pipes** with clean
  NS3451-coded systems (`310.x KV`, `311.x Forbruksvann`, `312.x VV`, `320.x VT Rad`,
  `321.x retur`...). `SystemClass` gives engineering category (Hydronic Supply / Return,
  Sanitary, Fire Protection Wet).
- **Wall existing/new**: `Pset_WallCommon.Status` = `[EXISTING]` / `[NEW]` on 2,545 walls.
- **PFV diameters**: `Pset_ProvisionForVoid.Diameter` on 722 PFVs (100/120/180/400 mm).
- Don't confuse with `Pset_*.Reference` (30k+ hits) - that's IFC standard "type
  designator" string (`Limtre 140 x 450`, `Søyle 550x550`), NOT ownership.

### 8. Eies/Eier ownership filter validated
- Lenient (ANY component reference): drops 45,058 (39%) but **loses 224 flagged**.
- **Strict (ALL components reference)**: drops **790 pure-reference clashes**, **0 flag loss**.
  The 224 mixed-clash flagged cases are real - user flags clashes that include reference
  components when there's still owned-side signal. Strict is the safe wire-it-in default.

### 9. ARK as spatial truth (the bigger insight)
edkjo: "ARK owns the storeys and spaces. RIV object X sits in ARK space Y." Built the
spatial-assignment service:
- Parse `LBK_ARK_C.ifc` + `LBK_IARK_C.ifc` → 506 IfcSpace + 10 storeys with elevations.
  IfcSpace bbox uses full-building Z (22.84m every time), so test is XY-footprint +
  storey Z-range from `IfcBuildingStorey.Elevation`.
- For each component in every submodel: `ifcopenshell.util.placement.get_local_placement`
  → world XYZ in model units → scale to mm via
  `ifcopenshell.util.unit.calculate_unit_scale * 1000`. (First attempt had the formula
  inverted - dropped EVERY element 1000x out of range. Spot-check + fix saved the day.)
- Z-range match → storey; XY bbox match within that storey → space.
- Output: 132,761 assigned (84% of 157,256), 15,593 outside storey range (mostly
  roof/site), 8,931 in storey but outside any IfcSpace (corridors/voids). Saved to
  `C:\tmp\lbk_claude\lbk_ark_space_assignment.json` (13.8 MB).
- Plan 1 union of classification (location text `C - Plan 1`) ∪ self-spatial storey:
  classification = 57,703 elements, self-spatial = 18,116, union = 59,254. The huge
  classification-only set (41,138) means many elements are at Plan 1 elevation but
  assigned to a different storey in their own submodel - the spatial misalignment edkjo
  warned about. ARK-truth lookup will rerun this cleanly.

### 10. Live slide authoring - two real bugs caught and fixed
First Plan 1 deck (15 slides) created in Solibri, but all slides had:
- empty title / stage / priority, default `type=Error` (instead of what we sent), and
- the same default screenshot.

Diagnosed by reading slide-summary back: only `status:Open` survived. Root cause:
`Slide.create()` → setX(...) → `Presentation.addSlide(s)` - the **pre-configured setters
don't persist** through addSlide. Fixed in `SuperuserServer.handlePresentationSlides`
(commit `bdf4a3e`):
1. `addSlide(s)` FIRST, then call all setX on the returned slide handle.
2. **Per-slide viewpoint capture**: `Camera.lookAt(components)` →
   `ThreeD.get3DSnapshot()` → `Viewpoint.create()` with
   `setCameraState(td.createCameraState())` + `setImagePngBytes(png)` →
   `Slide.addViewpoint(vp)`. Each slide now has its own camera fitted to its components.

`Camera` has no `getCameraState()` - found it on `ThreeD.createCameraState()`. Compile
errors are how you learn the SDK.

Re-ran with diversified 16-slide Plan 1 deck across 8 primary rules (RIVv-RIVr, RIVv,
Kollisjoner VVS/isolasjon, ARK Vegger, ARK Div). All metadata stuck, all 16
viewpoints captured, ~3.5s/slide wall time.

## Shipped

Plugin (`b271a61 → 2e283f4 → bdf4a3e`):
- `POST /rulesets/install` - JSON ruleset authoring via RulesImporter+CheckingPlugin.addModel.
- `POST /presentations/{id}/slides` (FIXED) - addSlide-first, setX-after; per-slide
  camera-fit + snapshot viewpoint.

Toolkit (`95f74b0`):
- `SuperuserClient.install_ruleset()`.
- `cli.py su ruleset-install PATH.json`.
- `solibri_loop/ifc_catalog.py` - federation property catalog + `find_property()` search.

Live in `LBK_C_SAMLETMODELL_Ed_claude.smc`:
- Ruleset `Skiplum_TMK_Claude_v1` (the API-installed clone).
- Presentation `Plan 1 review v2 (auto) — 2026-05-31 [Claude]` with 16 slides, full
  metadata + per-slide snapshots.

## Memory updates
- New: [[edkjo-collision-control-wishlist]] (8 items + how to apply).
- New: [[skiplum-vs-vedal-rulesets]] (primary check matrix + geometric heuristics:
  parallel-run, PFV full vs partial overlap, small-through-density).
- Extended: [[feedback-move-fast-when-reversible]] - "don't be shy about restarting"
  directive.

## State / next

- `LBK_claude.smc` open in Solibri; ARK spatial assignment index on disk; ownership +
  flagged GUIDs cached.
- Wave-2 build list (in [[solibri-superuser-plugin]] eventually): regenerate Plan 1 deck
  grouped by ARK space, install `Skiplum_ARK_Storey` classification, build RIVr-system
  classification from `MagiCAD Pset_Pipe.SystemType`, discipline-pair ruleset generator,
  BCF/CDE sync (users → issues → comments → statuses), auto-assign on toolkit-authored
  slides. Order to be decided next session.
- Pending plugin endpoint added but not yet wave-1-merged: `/rulesets/install`'s
  companion `/presentations/fromRuleset` (CreatePresentationFromRulesetTask) -
  one javap away.
