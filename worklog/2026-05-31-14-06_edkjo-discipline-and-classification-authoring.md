---
date: 2026-05-31
project: toolkit/solibri + solibri-bim-tools (LBK_Landbrukskvartalet, _claude copy)
duration_hours: ~1
co_author: claude-opus-4-7
---

# Discipline + classification authoring; ARK_Space classification live

## What

Shipped four new plugin endpoints in one rebuild cycle that close the rest of wishlist
items 3 (discipline-tuned scoping) and 4 (classification-first targeting) on the read +
write side. Live in Solibri: two ARK-truth classifications (`Skiplum_ARK_Storey_Full`,
`Skiplum_ARK_Space`) covering 132k components, plus discipline-set on `LBK_RIVv_C`.

## Endpoints added (`plugin 0d8defc`, `toolkit dc1525f`)

| Endpoint | Internal backing |
|---|---|
| `GET /disciplines` | `BuildingIndustryDomains` constants enumerated via reflection (it's a typesafe-enum, not a real `Enum` - no `values()/valueOf()/name()`) |
| `GET /models/{uuid}/metadata` | `Model.getMetadata()` per submodel |
| `POST /models/{uuid}/discipline?value=ENUM` | `DisciplineTrackingUtil.trackDisciplineAssignedToModel(SModel, BuildingIndustryDomains, boolean)`. SModel obtained via `(SModel)((CoreModelWrapper)submodel).getCoreModel()` |
| `POST /classifications/createBulk` body=`{name, assignments:{className:[guid,...]}}` | `ClassificationPlugin.createClassificationIfNeeded(name)` + `setClassification(Collection<Entity>, name)` + `addClassificationToModel(c)`. Component → Entity downcast just worked (Component IS-A redox Entity in the impl) |

Python: `sc.disciplines()`, `sc.model_metadata(uuid)`, `sc.set_discipline(uuid, value)`,
`sc.create_classification_bulk(name, assignments)`.

CLI: `cli.py su {disciplines, model-metadata UUID, set-discipline UUID VALUE,
classify-bulk PATH.json}`.

## Live results in LBK_claude

| Classification | Classes | Components | Time |
|---|---:|---:|---:|
| `Skiplum_ARK_Storey_Full` | 8 | 132,505 (180 missing) | 2.9s |
| `Skiplum_ARK_Space` | **274** | 132,505 (180 missing) | 3.0s |

`Skiplum_ARK_Space` class sizes: min=2, median=116, max=**8,185** (`C - U1 / Teknisk rom
[CDC1.U101]`) - the hottest zone in the TMK10_Plan6 BCF data. The Pareto matches reality;
the new classification will plug directly into discipline-aware ruleset generators.

Top spaces by component count (Pareto for triage scoping):

| Space | Components |
|---|---:|
| C - U1 / Teknisk rom | 8,185 |
| C - Plan 1 / Kantine | 7,638 |
| C - Plan 2 / Kontorlandskap Vest | 6,575 |
| C - Plan 5 / Kontorlandskap Vest | 6,457 |
| C - Plan 4 / Kontorlandskap Vest | 6,277 |

## Gotchas / fixes burned

- `BuildingIndustryDomains` is a pre-Java-5 typesafe-enum. First attempt with
  `.values()/.valueOf()/.name()` failed to compile. Reflection over `getDeclaredFields()`
  filtered to public-static-final-of-self-type is the workaround. Cached via a volatile
  double-checked map.
- `Model.getMetadata()` does NOT contain the discipline - Solibri tracks discipline via a
  separate internal mechanism that's only visible in the Model Tree UI. Confirmed by the
  `set:true` response but empty metadata on read-back.

## State / next

**On-disk state** (in `C:\tmp\lbk_claude\`):

| File | Size | What |
|---|---:|---|
| `LBK_C_SAMLETMODELL_Ed_claude.smc` | 632 MB | LBK working copy, **last saved at 12:01:11** (save-as time). Original on ACC untouched. |
| `LBK_C_SAMLETMODELL_Ed_claude.smc_org.bak` | 624 MB | Solibri's auto-backup of an earlier state |
| `lbk_ark_space_assignment.json` | 13.8 MB | GUID → `{ARK_storey, ARK_space_name, longname}` for 132,505 components (8 storeys / 274 spaces / 84% federation coverage) |
| `lbk_ark_spaces.json` | 114 KB | 506 ARK spaces with XY bboxes + storey Z-ranges (cached for spatial assignment) |
| `lbk_federation_catalog.json` | 342 KB | Full per-model pset/property catalog for 13 submodels |
| `lbk_storey_index.json` | 6.2 MB | Self-reported storey index (pre-ARK-truth; less authoritative) |
| `C:/tmp/lbk_flagged_guids.pkl` | — | 3,349 BCF-derived flagged GUIDs from TMK10_Plan6 round |

**In-memory only — NOT on disk** (in the current Solibri session; lost on next restart
unless persisted via `cli.py su project-save --yes`):
- Ruleset `Skiplum_TMK_Claude_v1` (cloned from Skiplum_TMK via /rulesets/install)
- Presentation `Plan 1 review v2 (auto) — 2026-05-31 [Claude]` (16 slides, full metadata,
  per-slide camera-fit viewpoints)
- Classifications `Skiplum_ARK_Space` (274 classes), `Skiplum_ARK_Storey_Full` (8 classes),
  `Skiplum_ARK_Storey` (capped probe — can delete)
- LBK_RIVv_C discipline = VENTILATION

If left unsaved, the next session can recreate everything in 1-2 minutes from the
on-disk indexes (the ruleset clone, the bulk-classify calls, the slide-add loop).

**Resume from cold next session**:
```bash
cd c:/workspace/toolkit/solibri
python cli.py up                                  # check state
python cli.py launch --open "C:/tmp/lbk_claude/LBK_C_SAMLETMODELL_Ed_claude.smc"
python cli.py su rulesets                         # 3 (pre-clone state on disk)
python cli.py su classifications                  # 4 (pre-author state on disk)
```

Next high-value moves (each small, restart-friendly):
- Bulk-set every submodel discipline from `{name pattern → discipline}` (RIVv→VENTILATION,
  RIVr→PLUMBING, RIE→ELECTRICAL, ARK→ARCHITECTURE, RIB→STRUCTURAL_ELEMENTS, LARK→LANDSCAPE).
- Regenerate the Plan 1 review deck **grouped per ARK space**, using the new classification.
- Author a discipline-+-space-scoped ruleset (combines `Skiplum_ARK_Space` filter with
  discipline filters in the rule JSON).
- BCF/CDE sync wave-3 (the deeper item: list servers, pull issues + users + comments).

Last commits: toolkit `e0c25b7` (worklog + ifc_catalog), plugin `0d8defc` (4 endpoints).

Next high-value moves (each small, restart-friendly):
- Bulk-set every submodel discipline from `{name pattern → discipline}` (RIVv→VENTILATION,
  RIVr→PLUMBING, RIE→ELECTRICAL, ARK→ARCHITECTURE, RIB→STRUCTURAL_ELEMENTS, LARK→LANDSCAPE).
- Regenerate the Plan 1 review deck **grouped per ARK space**, using the new classification.
- Author a discipline-+-space-scoped ruleset (combines `Skiplum_ARK_Space` filter with
  discipline filters in the rule JSON).
- BCF/CDE sync wave-3 (the deeper item: list servers, pull issues + users + comments).
