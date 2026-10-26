# Solibri Rules & Classification Knowledge Base

> Skiplum AS — maintained by BIM coordination team
> Last updated: 2026-03-03

---

## 1. Current Rule Setup (KSV_Samletmodell)

### Active Rule/Constraint Classes

The Kystbyen Slemmestad combined model currently uses these rule types:

| Class | What it does |
|---|---|
| `GeometricIntersectionConstraint` | Hard clash detection — geometry-vs-geometry intersections |
| `GeneralInterferenceConstraint` | Soft clash / interference — with tolerance and special-case handling |
| `ComponentDistanceRule` | "Objects in front of doors/windows" — minimum clearance checks |
| `FreeAreaInFrontOfComponentsRule` | Free area requirements in front of components |
| `StructureArchitectureConformity` | Structure-vs-architecture conformity checks |
| `IdsRule` | IDS (Information Delivery Specification) validation |

### Active Rule Instances (from SMC scan)

Numbered rules following the pattern `<section>.<number>. <description>`:

```
1.6.  ARK Dør/Vindu - RIB
1.7.  ARK - RIB Objekter foran dører og vinduer
2.5.  ARK Dør/Vindu - RIE
2.7.  ARK - RIE Objekter foran dører og vinduer
3.4.  ARK Dør/Vindu - RIVv
3.4.  ARK Dør/Vindu - RIVspr
3.7.  ARK - RIV Objekter foran dører og vinduer
3.7.  ARK - RIVspr Objekter foran dører og vinduer
3.11. ARK Dør/Vindu - RIVr
3.14. ARK - RIVr Objekter foran dører og vinduer
5.2.  ARK Dør/Vindu - LARK
```

### Observations

- **Heavy door/window focus**: Almost all rules check ARK (architecture) doors/windows against other disciplines (RIB=structural, RIE=electrical, RIV=plumbing/HVAC, LARK=landscape)
- **"Objekter foran dører og vinduer"** = Objects in front of doors and windows — clearance checks using `ComponentDistanceRule` / `FreeAreaInFrontOfComponentsRule`
- **No accessibility rules active** (no TEK17 room height, door width, corridor width checks)
- **No property/data quality rules active** (no checks for missing properties, classifications, naming)
- **No model comparison rules active**
- **Clash detection is geometry-only** — no classification-based filtering beyond the discipline split

### False Positive Sources (observed in results)

- BIM Track clash spheres (`BIM Track-Clash Sphere:Red-New`) showing up in intersection results — external clash markers from BIM Track being treated as real geometry
- Assembly components generating noise (Assembly vs Door, Assembly vs Wall intersections)
- Cross-storey intersections flagged separately (`INTERSECTING DIFFERENT STOREY`, `SIMILARINTERSECTIONSDIFFSTOREY`)
- MEP system types (spillvann, overvann, gulvvarme, sprinkler) clashing with fire elements (E-FIRE) — possibly expected penetrations

---

## 2. Rule Template Reference

### Categories

| SOL Range | Category | Status at Skiplum |
|---|---|---|
| SOL/1-19 | **BIM Validation** (model integrity) | NOT USED |
| SOL/20-29 | **Deficiency Detection** (geometry defects) | NOT USED |
| SOL/30-59 | **Intersections / Clashes** | ACTIVE (primary use) |
| SOL/60-99 | **Spacing & Clearance** | PARTIALLY (door/window clearance) |
| SOL/100-149 | **Property & Attribute** | NOT USED |
| SOL/150-199 | **Accessibility** | NOT USED |
| SOL/200-229 | **Quantity & Measurement** | NOT USED |
| SOL/230-249 | **Model Comparison** | NOT USED |

### Key Rules Worth Activating

#### Tier 1 — High Value, Low Effort

| Rule | What | Why |
|---|---|---|
| SOL/1 | Component Containment | Catches components outside building — these pollute clash results |
| SOL/7 | Spatial Containment | All components must belong to a storey |
| SOL/100 | Required Property Sets | Validate Pset_WallCommon, Pset_DoorCommon exist |
| SOL/101 | Required Properties | FireRating, IsExternal, LoadBearing must be filled |
| SOL/230-232 | Model Comparison | Track what changed between model versions |

#### Tier 2 — TEK17 / Accessibility

| Rule | What | TEK17 Requirement |
|---|---|---|
| SOL/150 | Door Width | Clear opening ≥ 860mm (accessible) |
| SOL/151 | Corridor Width | ≥ 1500mm for wheelchair passing |
| SOL/152 | Ramp Slope | ≤ 1:20 (5%) for ramps > 3m |
| SOL/153 | Stair Dimensions | Riser 150-180mm, tread ≥ 250mm |
| SOL/154 | Threshold Height | ≤ 25mm |
| SOL/155 | Turning Space | 1500mm diameter wheelchair turning |
| SOL/201 | Room Height | ≥ 2400mm habitable, ≥ 2200mm secondary |
| SOL/202 | Daylight (window/floor ratio) | ≥ 10% for habitable rooms |

#### Tier 3 — Data Quality / Classification

| Rule | What | Value |
|---|---|---|
| SOL/3 | Duplicate GUIDs | Catches copy-paste errors in Revit |
| SOL/4 | Classification Check | Ensures NS3451 is applied |
| SOL/102 | Property Value Validation | FireRating values match allowed set |
| SOL/103 | Naming Convention | Regex pattern check on component names |
| SOL/104 | Required Classification | All elements classified |

---

## 3. Clash Detection — Reducing False Positives

### The Problem

Running "check everything vs everything" produces thousands of results, 80-90% of which are noise. Current setup already filters by discipline pairs (ARK vs RIB, ARK vs RIE, etc.) which is good, but there are more optimizations.

### Tolerance Settings

| Parameter | Recommended | Current Likely Default |
|---|---|---|
| **Tolerance** | 10-25mm start, tighten to 5mm | 0mm (too tight) |
| **Min. Overlap Volume** | 0.0005 m³ | Not set (generates surface-touch FPs) |
| **Allow Touching** | YES | Probably NO |
| **Allow Containment** | Context-dependent | Probably NO |

### Exclusion Checklist

Always exclude from clash detection:
- [ ] `IfcOpeningElement` — intentional voids
- [ ] `IfcSpace` / `IfcZone` — analytical, not physical
- [ ] `IfcAnnotation` — 2D elements
- [ ] Insulation components vs their parent elements
- [ ] Furniture vs architectural elements (unless specifically checking)
- [ ] "Existing" phase components (if model has mixed phases)
- [ ] BIM Track clash spheres (`BIM Track-Clash Sphere`) — **currently polluting results**
- [ ] `IfcBuildingElementProxy` — review separately

### Multi-Pass Strategy

Instead of one big clash check, run in passes:

```
Pass 1: CRITICAL - Structure vs MEP
  → Structural beams/columns/slabs vs HVAC ducts, pipes, cable trays
  → Tolerance: 5-10mm, Min volume: 0.0005 m³
  → Severity: Critical

Pass 2: Architecture vs MEP
  → Walls/slabs vs pipes/ducts (penetrations)
  → Tolerance: 15mm
  → Severity: Major
  → Note: Many are intentional — focus on load-bearing/fire-rated walls

Pass 3: MEP vs MEP (inter-discipline)
  → HVAC vs Plumbing vs Electrical
  → Tolerance: 5mm
  → Severity: Major

Pass 4: Door/Window Clearance (already active)
  → Objects blocking doors/windows
  → Using ComponentDistanceRule
  → Severity: Moderate

Pass 5: Intra-discipline QA
  → Duplicate components, self-intersections
  → Run per-model, not inter-model
```

### Result Grouping

Group clash results by **source component** rather than by pair. If a beam has 15 clashes with 15 pipes, that's 1 issue (the beam routing), not 15 issues.

---

## 4. Classification Strategy

### NS3451 Mapping

NS3451 (Bygningsdelstabell) main groups relevant to coordination:

| Code | Norwegian | English | IFC Types |
|---|---|---|---|
| 21 | Grunn/fundamenter | Foundation | IfcFooting, IfcPile |
| 22 | Bæresystem | Structural | IfcBeam, IfcColumn, IfcSlab (structural) |
| 23 | Yttervegger | External walls | IfcWall (IsExternal=TRUE) |
| 24 | Innervegger | Internal walls | IfcWall (IsExternal=FALSE) |
| 25 | Dekker | Floors/slabs | IfcSlab (floor) |
| 26 | Yttertak | Roof | IfcRoof, IfcSlab (roof) |
| 28 | Trapper/ramper | Stairs/ramps | IfcStair, IfcRamp |
| 31 | Sanitær | Plumbing | IfcPipeSegment, IfcSanitaryTerminal |
| 32 | Varme | Heating | IfcPipeSegment (heating) |
| 33 | Brannslokking | Fire suppression | IfcPipeSegment (sprinkler) |
| 36 | Luftbehandling | HVAC | IfcDuctSegment, IfcAirTerminal |
| 4x | Elkraft | Electrical | IfcCableCarrierSegment, IfcElectricAppliance |

### Classification-Based Rule Filtering

Use NS3451 codes to create surgical clash rules:
- **22xx vs 36xx** = Structure vs HVAC (critical)
- **22xx vs 31xx** = Structure vs Plumbing (critical)
- **23xx vs 36xx** = External walls vs HVAC (check fire rating!)
- **24xx vs 31xx** = Internal walls vs Plumbing (check fire rating!)

### Property-Based Classification

For elements missing NS3451 in IFC, classify by properties:
- `IsExternal` = TRUE → External element (23/26)
- `LoadBearing` = TRUE → Structural (22)
- `FireRating` ≠ empty → Fire-rated element (flag for penetration review)

---

## 5. Beyond Geometry — Function/Quality Rules

### What's Missing from Current Setup

The current ruleset is 100% geometry-focused (intersections + clearance). These non-geometric checks add significant value:

#### 5.1 Property Completeness

**Why**: Downstream processes (FM, energy, cost) depend on properties being filled. Catching missing data early saves rework.

**IfcOpenShell + Solibri approach**:
1. Python (`ifc_analyzer.py`) scans model for property completeness → identifies gaps
2. Solibri `PropertyExistsRule` (custom SDK rule, already deployed) enforces requirements
3. SOL/100-102 for standard property checks

**Key properties to validate** (from Slemmestad model psets):

| Property Set | Properties | Why |
|---|---|---|
| Pset_WallCommon | FireRating, IsExternal, LoadBearing | Structural + fire safety |
| Pset_DoorCommon | FireRating, IsExternal, HandicapAccessible | Accessibility + fire |
| Pset_SlabCommon | FireRating, LoadBearing | Structural integrity |
| Identity Data | Brannkrav, Akustikkerkrav, Brannkonsulentkrav | Norwegian fire/acoustic |
| NS8360 properties | Various per delivery spec | Contract compliance |

#### 5.2 Accessibility (TEK17)

**Why**: Currently running "basic door and window check that mostly has false positives." The FPs likely come from checking ALL doors against one width threshold. Fix by:

1. Classify doors by function (main entrance, internal, accessible WC, emergency)
2. Apply different width requirements per classification
3. Only flag doors in accessible zones

**Recommended setup**:
```
Rule: Door Width - Main Entrances
  Filter: Doors classified as "main entrance" or "HC entrance"
  Min width: 900mm clear opening

Rule: Door Width - Accessible Rooms
  Filter: Doors to rooms tagged "HC" or "tilgjengelig"
  Min width: 860mm clear opening

Rule: Door Width - Emergency Exits
  Filter: Doors with FireRating property
  Min width: per fire code (900mm typical)
```

#### 5.3 Fire Safety Integration

**Why**: Fire-rated walls with unprotected penetrations are a major real-world issue. Currently not checked.

**Approach**:
1. IfcOpenShell identifies all fire-rated walls (FireRating ≠ null)
2. Solibri checks for MEP penetrations through those walls
3. Flag penetrations without fire dampers/collars

#### 5.4 Model Comparison (Version Tracking)

**Why**: After each model delivery, know exactly what changed. Catches silent regressions.

**SOL/230-235**:
- SOL/230: Added components (new design)
- SOL/231: Removed components (demolished or error?)
- SOL/234: Geometry changes (moved, resized)
- SOL/235: Property changes (fire rating changed?)

---

## 6. SDK Custom Rules — What We Can Build

### Available Rule Base Classes

| Base Class | Use Case | Example |
|---|---|---|
| `OneByOneRule` | Single component validation | Property check, dimension check |
| `ConcurrentRule` | Multi-component analysis | Coverage, connectivity, complex clashes |

### Available Parameters

| Parameter Type | SDK Method | UI Display |
|---|---|---|
| `FilterParameter` | `params.createFilter(id)` | Component selection dialog |
| `DoubleParameter` | `params.createDouble(id, PropertyType)` | Number input with units |
| `BooleanParameter` | `params.createBoolean(id)` | Checkbox |
| `StringParameter` | `params.createString(id)` | Text input |
| `EnumerationParameter` | `params.createEnumeration(id, options)` | Dropdown/radio |
| `PropertyReferenceParameter` | `params.createPropertyReference(id)` | Property picker |
| `TableParameter` | `params.createTable(id, headers, types)` | Editable table |

### Component API

```java
component.getName()
component.getGUID()
component.getBoundingBox()           // AABB3d
component.getTriangleMesh()          // TriangleMesh
component.getGlobalTopElevation()
component.getGlobalBottomElevation()
component.getPropertySets(name)      // Collection<PropertySet>
component.getPropertyValue(ref)      // Optional<PropertyValue>
component.getIfcEntityType()         // IfcEntityType enum
component.getIntersections(other)    // Set<Intersection>
component.getFootprint()             // Footprint → Area
```

### Geometry API

```java
AABB3d box = component.getBoundingBox();
// box.getMinimumPoint(), box.getMaximumPoint()

AABBIntersectionFilter.ofComponentBounds(component, tolerance, tolerance)
  .and(userFilter);
SMC.getModel().getComponents(filter);
```

### Visualization API

```java
resultFactory.create(name, description)
  .withInvolvedComponent(target)
  .withVisualization(viz -> {
    viz.addComponent(comp, 0.5);  // semi-transparent
    viz.addComponentWithColor(comp, ARGBColor.create(255, 255, 0, 0));
    viz.addVisualizationItem(VisualizationItem.createDimension(p1, p2));
  });
```

### Deployed Custom Rules

| Rule | Class | Status |
|---|---|---|
| PropertyExistsRule | `no.skiplum.solibri.rules.PropertyExistsRule` | Deployed, needs Solibri restart |
| MiddleElevationInfo | `no.skiplum.solibri.info.MiddleElevationInfo` | Deployed, needs Solibri restart |

### Rules to Build Next

1. **FireRatedPenetrationRule** — Flag MEP through fire-rated walls without protection
2. **PropertyCompletenessRule** — Check multiple required properties at once (table parameter)
3. **ClassificationValidationRule** — Verify NS3451 codes match element types
4. **NamingConventionRule** — Regex-based naming validation
5. **ElevationConsistencyRule** — Flag elements at wrong elevation for their storey

---

## 7. Do's and Don'ts

### Do's

1. **Run BIM Validation (SOL/1-9) first** — fix model issues before clash detection
2. **Name rules clearly** — "HVAC Ducts vs Load-Bearing Walls (Critical)" not "SOL/30"
3. **Start with large tolerance (25mm), tighten gradually** — you can always reduce
4. **Set minimum overlap volume** — even 0.0005 m³ eliminates surface-touch FPs
5. **Group results by source component** — 1 beam with 15 clashes = 1 issue
6. **Exclude BIM Track spheres** from all clash checks
7. **Separate rulesets by purpose** — validation, clashes, accessibility, data quality
8. **Use BCF export** for communicating issues to design teams
9. **Run model comparison after every delivery** — know what changed
10. **Validate properties before checking geometry** — garbage data = garbage clashes

### Don'ts

1. **Don't check everything vs everything** — 90% noise guaranteed
2. **Don't use 0mm tolerance** — surface-touching = thousands of FPs
3. **Don't skip spatial containment checks** — floating components wreck clash results
4. **Don't treat all clashes equally** — pipe through beam ≠ ceiling tile touching wall
5. **Don't ignore "IKKE TILGJENGELIG" results** — these are real accessibility failures
6. **Don't run 200 rules simultaneously** — run by category for performance
7. **Don't accept Solibri defaults blindly** — always review tolerance and filter settings
8. **Don't forget phase/status filtering** — existing vs new construction matters
9. **Don't create one monolithic ruleset** — split by discipline and purpose
10. **Don't rely on geometry alone** — property checks catch more real issues

---

## 8. IfcOpenShell + Solibri Pipeline

### The Vision

```
IFC Model (from Revit/ArchiCAD/Tekla)
    │
    ├─→ IfcOpenShell (Python) ─── Fast data analysis
    │     • Property completeness scan
    │     • Element classification audit
    │     • Cross-model consistency check
    │     • Export analysis JSON
    │
    └─→ Solibri (Java SDK) ─── Visual QA + geometry
          • Custom rules consume IfcOpenShell output
          • Clash detection with smart filtering
          • Accessibility checks (TEK17)
          • BCF export for design teams
          • QTO / ITO reporting
```

### Tools

| Tool | Location | Purpose |
|---|---|---|
| `ifc_analyzer.py` | `C:\dev\solibri-bim-tools\python\analysis\` | IfcOpenShell data extraction |
| `PropertyExistsRule.java` | `C:\dev\solibri-bim-tools\java\src\...\rules\` | Custom Solibri rule |
| `MiddleElevationInfo.java` | `C:\dev\solibri-bim-tools\java\src\...\info\` | Custom ITO |
| `build.bat` | `C:\dev\solibri-bim-tools\scripts\` | Build + deploy to Solibri |
| `smc_patch.py` | (temp) | Patch BIM360 paths in SMC files |

### Build & Deploy

```batch
cd C:\dev\solibri-bim-tools\scripts
build.bat
:: → Builds JAR → copies to C:\Program Files\Solibri\SOLIBRI\lib\
:: → Restart Solibri to load
```

---

## 9. Recommended Ruleset Structure

```
Ruleset: "Skiplum BIM Koordinering"
│
├── 01 - Modellvalidering
│   ├── Komponentinnhold i bygg (SOL/1)
│   ├── Romlig tilhørighet (SOL/7)
│   ├── Dupliserte GUID (SOL/3)
│   └── Geometrivalidering (SOL/23)
│
├── 02 - Datakvalitet
│   ├── Påkrevde egenskapssett (SOL/100)
│   ├── Brannkrav utfylt (SOL/101 - FireRating)
│   ├── IsExternal utfylt (SOL/101)
│   ├── Bærende utfylt (SOL/101 - LoadBearing)
│   ├── NS3451 klassifisering (SOL/104)
│   └── Navnekonvensjon (SOL/103)
│
├── 03 - Tilgjengelighet (TEK17)
│   ├── Dørbredde - Hovedinngang (SOL/150, ≥900mm)
│   ├── Dørbredde - HC-rom (SOL/150, ≥860mm)
│   ├── Korridorbredde (SOL/151, ≥1500mm)
│   ├── Rampestigning (SOL/152, ≤1:20)
│   ├── Trappetrinn (SOL/153)
│   ├── Terskelhøyde (SOL/154, ≤25mm)
│   ├── Snusirkel (SOL/155, Ø1500mm)
│   ├── Romhøyde (SOL/201, ≥2400mm)
│   └── Dagslys vindusareal (SOL/202, ≥10%)
│
├── 04 - Kollisjoner: Konstruksjon vs VVS
│   ├── Bæresystem vs Ventilasjon (10mm tol.)
│   ├── Bæresystem vs Sanitær (10mm tol.)
│   ├── Bæresystem vs Elkraft (10mm tol.)
│   └── Bæresystem vs Sprinkler (10mm tol.)
│
├── 05 - Kollisjoner: Arkitektur vs VVS
│   ├── Vegger vs Ventilasjon (15mm tol.)
│   ├── Vegger vs Sanitær (15mm tol.)
│   ├── Dekker vs VVS (15mm tol.)
│   └── Brannvegger vs gjennomføringer (0mm, KRITISK)
│
├── 06 - Kollisjoner: VVS internt
│   ├── Ventilasjon vs Sanitær (5mm)
│   ├── Ventilasjon vs Elkraft (5mm)
│   └── Sanitær vs Elkraft (5mm)
│
├── 07 - Dør/Vindu klarering (existing)
│   ├── ARK Dør/Vindu vs RIB
│   ├── ARK Dør/Vindu vs RIE
│   ├── ARK Dør/Vindu vs RIV
│   └── Objekter foran dører/vinduer
│
├── 08 - Modellsammenligning
│   ├── Nye komponenter (SOL/230)
│   ├── Fjernede komponenter (SOL/231)
│   ├── Geometriendringer (SOL/234)
│   └── Egenskapsendringer (SOL/235)
│
└── 09 - Egendefinerte regler (SDK)
    ├── PropertyExistsRule
    ├── MiddleElevationInfo (ITO)
    └── (future: FireRatedPenetrationRule)
```
