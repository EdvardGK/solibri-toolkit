# Plan: Add IfcSpace containment to zone tagging

## Goal

Upgrade the tagging script to use a 3-level zone hierarchy with "smallest nested wins":

1. **IfcSpace** (finest — "Bad", "Sov", etc.) — from ARK models (currently only KvC)
2. **Building zone** (KvA/KvB/KvC/KvD) — from clash_zones.ifc polygon geometry
3. **Uteområde** — fallback

Storey comes from the ARK model that owns the matched building zone.

## Output properties (Skiplum_Scope Pset)

| Property | Source | Example |
|---|---|---|
| Zone | Building zone polygon | "KvC" |
| Storey | ARK storey elevations | "C 03" |
| Space | IfcSpace polygon (if match) | "C4-301 3R_S" |

## What changes

### 1. `geometry.py` — Add `extract_space_polygon()`

New function that takes an IfcSpace and returns a Shapely Polygon in world coordinates.

Handles the 3 profile types found in the data:
- **IfcRectangleProfileDef** (452 spaces in KvC, all 4 clash zones) → 4 corner points
- **IfcArbitraryClosedProfileDef** with IfcPolyline (541 in KvC) → vertex list
- **IfcArbitraryProfileDefWithVoids** (24 in KvC) → Shapely Polygon with holes
- Skip IfcCompositeCurve (only 1 in KvC — not worth the complexity)

Coordinate transform pipeline:
1. Get profile 2D points (local to profile)
2. Apply IfcExtrudedAreaSolid.Position (3D rotation + offset) → world XY
3. Return as `shapely.geometry.Polygon`

Also returns Z range (from placement Z + extrusion depth) for storey filtering.

### 2. `zones.py` — Add `SpaceInfo` dataclass + `load_spaces()`

```python
@dataclass
class SpaceInfo:
    name: str         # e.g. "C4-301"
    long_name: str    # e.g. "3R_S"
    building: str     # e.g. "KvC" — which ARK model it came from
    storey: str       # e.g. "C 03" — from IfcBuildingStorey parent
    polygon: Polygon  # Shapely polygon in world XY
    z_min: float
    z_max: float
    area: float       # polygon area, for smallest-first sorting
```

`load_spaces(ifc_path, building_name)` → list[SpaceInfo]:
- Opens the IFC file, iterates IfcSpace
- Extracts polygon via `extract_space_polygon()`
- Walks Decomposes chain to find parent IfcBuildingStorey name
- Sorts by area ascending (smallest first for priority)

### 3. `zones.py` — Add `find_space_for_point()`

```python
def find_space_for_point(x, y, z, spaces: list[SpaceInfo]) -> Optional[SpaceInfo]:
```

- Spaces pre-sorted by area (smallest first)
- For each space: check Z range first (cheap), then Shapely `polygon.contains(Point(x, y))`
- Returns first (= smallest) match, or None

### 4. `tag_zones.py` — Use clash zone polygons + spaces

**Loading phase** (in `main()`):
- Load clash zone polygons from `clash_zones.ifc` IfcSpaces → 4 Shapely Polygons (skip Uteområde CSG)
- Load IfcSpaces from each ARK model that has them
- Keep existing OBB analysis as fallback for buildings without clash zone polygons

**Tagging phase** (in `tag_model()`):
- For each element with XY+Z:
  1. Try `find_space_for_point()` → if match, use space.building as Zone, space.storey as Storey, format Space label
  2. Else try clash zone polygon containment → Zone = building name, Storey from elevation
  3. Else try OBB containment (existing, with padding) → Zone + Storey
  4. Else → "Uteområde"
- Write Zone, Storey, Space (if found) to Skiplum_Scope Pset

**New config constants:**
```python
SPACE_PROP = "Space"
CLASH_ZONES_FILE = OUTPUT_DIR / "clash_zones.ifc"
```

**Space label format:** `"{name} {long_name}"` e.g. "C4-301 3R_S" — name gives location, long_name gives function.

### 5. Performance considerations

- 994 spaces × 280k elements = ~278M point-in-polygon tests (worst case)
- Mitigation: Shapely STRtree spatial index on space polygons → O(log n) lookups
- Pre-filter by Z range before polygon test
- Pre-filter by building zone (only check spaces in the matched building)

Estimated time: building zone check first narrows to ~25% of spaces, STRtree handles the rest. Should add <30% to current runtime.

## Files to modify

| File | Changes |
|---|---|
| `ifc_workbench/spatial/geometry.py` | Add `extract_space_polygon()` |
| `ifc_workbench/spatial/zones.py` | Add `SpaceInfo`, `load_spaces()`, `find_space_for_point()` |
| `solibri-bim-tools/scripts/tag_zones.py` | Load clash zones + spaces, 3-level lookup, write Space property |

## Not in scope

- IfcCompositeCurve profiles (1 out of 994 — skip gracefully)
- Generating the clash_zones.ifc (already exists)
- Spaces for KvA/KvB/KvD (ARK models have none — will work automatically when they do)
