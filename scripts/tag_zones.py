#!/usr/bin/env python3
"""
Tag IFC elements with building zone and storey properties.

Uses ARK model wall footprints for zone containment (OBB) and
ARK storey elevations for floor assignment. Each element gets a
Pset with Zone and Storey values that Solibri can filter on.

Usage:
    python tag_zones.py                 # tag all discipline models
    python tag_zones.py --dry-run       # show storeys + model list only
    python tag_zones.py --model FILE    # tag a single model
"""

import argparse
import sys
import time
from pathlib import Path

import ifcopenshell
import ifcopenshell.api
import ifcopenshell.util.placement

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))
sys.path.insert(0, "C:/dev/projects/ifc-workbench")

from ifc_workbench.spatial.zones import analyze_buildings, find_zone_for_point
from ifc_workbench.spatial.geometry import get_element_xy

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

IFC_DIR = Path(
    "C:/Users/edkjo/DC/ACCDocs/Skiplum AS/Skiplum Backup/"
    "Project Files/10010 - Kystbyen Slemmestad/1. IFC"
)
OUTPUT_DIR = Path(
    "C:/Users/edkjo/DC/ACCDocs/Skiplum AS/Skiplum Backup/"
    "Project Files/10010 - Kystbyen Slemmestad/"
    "B_Leveranser/05_Modellopplasting/01_IFC/IFC_sonemerket"
)

ARK_FILES = {
    "KvA": IFC_DIR / "102.07_ARK_Kv A.ifc",
    "KvB": IFC_DIR / "102.07_ARK_Kv B.ifc",
    "KvC": IFC_DIR / "102.07_ARK_Kv C.ifc",
    "KvD": IFC_DIR / "102.07_ARK_Kv D med trafo.ifc",
}

# Building letter prefix for storey filtering
BUILDING_PREFIX = {
    "KvA": "A",
    "KvB": "B",
    "KvC": "C",
    "KvD": "D",
}

# Models to tag (everything except clash_zones)
MODELS_TO_TAG = [
    "102.07_ARK_Kv A.ifc",
    "102.07_ARK_Kv B.ifc",
    "102.07_ARK_Kv C.ifc",
    "102.07_ARK_Kv D med trafo.ifc",
    "101.02_VA.ifc",
    "102.02_RIVA_Gen.ifc",
    "102.02_VA_Asbuilt_Sekkefabrikken.ifc",
    "102.02_VA_Gen.ifc",
    "102.03_RIE_Gen.ifc",
    "102.04_RIVr_Gen.ifc",
    "102.04_RIVv_Gen.ifc",
    "102.04_RIsol_kv-A.ifc",
    "102.05_RIB_Amfiet.ifc",
    "102.05_RIB_Kjeller.ifc",
    "102.05_RIB_Kjeller_Armering_Kulvert_Endevegg.ifc",
    "102.05_RIB_Kjeller_Armering_kulvert.ifc",
    "102.05_RIB_Kjeller_Plasstøptdekke.ifc",
    "102.05_RIBp_Kjeller.ifc",
    "102.05_RIBp_Kv A.ifc",
    "102.05_RIBp_Kv B.ifc",
    "102.05_RIBp_Kv B_betong.ifc",
    "102.05_RIBp_Kv C.ifc",
    "102.05_RIBp_Kv C_KUN INFO FOR LASTER KJELLER.ifc",
    "102.05_RIBp_TRAPP.ifc",
    "102.05_RIBp_TRAPP_UTSP.ifc",
    "102.06_LARK_Amfiet.ifc",
    "102.06_LARK_Gen.ifc",
    "102.06_LARK_KVA_KVB.ifc",
    "102.06_LARK_KVC.ifc",
    "102.06_LARK_KVC_KUN INFO FOR LASTER KJELLER.ifc",
    "102.09_AVFS_Gen.ifc",
    "102.10 FVFK_Kjeller_Kulvert.ifc",
    "102.10_FVFK_Gen_Utomhus.ifc",
    "102.5_RIBp_Kjeller.ifc",
    "102.5_RIBp_Kjeller_betong.ifc",
    "103-02.10_FV.ifc",
]

ZONE_PADDING = 1000  # 1m extra margin around each building OBB

PSET_NAME = "Skiplum_Scope"
ZONE_PROP = "Zone"
STOREY_PROP = "Storey"


# ---------------------------------------------------------------------------
# Storey logic
# ---------------------------------------------------------------------------

def build_storey_ranges(analyses):
    """
    Build storey lookup ranges for each building.

    Returns dict: building_name -> list of (min_z, max_z, storey_name)
    Filters to building-specific storeys only (prefix match).
    """
    ranges = {}
    for a in analyses:
        prefix = BUILDING_PREFIX.get(a.name, "")
        # Filter to building-specific storeys
        storeys = [
            s for s in sorted(a.storeys, key=lambda s: s.elevation)
            if s.name.startswith(prefix)
        ]
        if not storeys:
            ranges[a.name] = []
            continue

        building_ranges = []
        for i, s in enumerate(storeys):
            min_z = s.elevation
            if i + 1 < len(storeys):
                max_z = storeys[i + 1].elevation
            else:
                # Last storey: extend to a reasonable ceiling
                max_z = s.elevation + 10000  # 10m above last storey
            building_ranges.append((min_z, max_z, s.name))

        ranges[a.name] = building_ranges
    return ranges


def find_storey(z, building_name, storey_ranges):
    """Find which storey a Z elevation falls into for a given building."""
    ranges = storey_ranges.get(building_name, [])
    if not ranges:
        return None

    for min_z, max_z, name in ranges:
        if min_z <= z < max_z:
            return name

    # Below first storey
    if z < ranges[0][0]:
        return f"Under {ranges[0][2]}"

    # Above last storey
    return f"Over {ranges[-1][2]}"


def get_element_z(element):
    """Extract global Z coordinate from element placement."""
    if not element.ObjectPlacement:
        return None
    try:
        matrix = ifcopenshell.util.placement.get_local_placement(
            element.ObjectPlacement
        )
        return matrix[2][3]
    except Exception:
        return None


# ---------------------------------------------------------------------------
# Tagging
# ---------------------------------------------------------------------------

def tag_model(model_path, output_path, analyses, storey_ranges):
    """
    Tag all IfcElement instances in a model with zone and storey properties.

    Returns stats dict: { "zone/storey": count }
    """
    ifc = ifcopenshell.open(str(model_path))
    all_elements = ifc.by_type("IfcElement")

    # Skip assemblies — their placement origin is often at (0,0),
    # while the actual child parts have correct positions.
    assemblies = set(id(e) for e in ifc.by_type("IfcElementAssembly"))
    elements = [e for e in all_elements if id(e) not in assemblies]
    skipped = len(all_elements) - len(elements)
    if skipped:
        print(f"    Skipping {skipped} IfcElementAssembly parents")

    total = len(elements)
    stats = {}
    no_placement = 0
    tagged = 0

    for i, element in enumerate(elements):
        if (i + 1) % 2000 == 0:
            print(f"    {i + 1}/{total}...", flush=True)

        xy = get_element_xy(element)
        if xy is None:
            no_placement += 1
            continue

        zone = find_zone_for_point(xy[0], xy[1], analyses, padding=ZONE_PADDING)
        zone_label = zone if zone else "Uteområde"

        z = get_element_z(element)
        storey_label = ""
        if z is not None and zone:
            storey_label = find_storey(z, zone, storey_ranges) or ""

        properties = {ZONE_PROP: zone_label}
        if storey_label:
            properties[STOREY_PROP] = storey_label

        pset = ifcopenshell.api.run(
            "pset.add_pset", ifc, product=element, name=PSET_NAME
        )
        ifcopenshell.api.run(
            "pset.edit_pset", ifc, pset=pset, properties=properties
        )

        key = f"{zone_label}/{storey_label or '—'}"
        stats[key] = stats.get(key, 0) + 1
        tagged += 1

    print(f"    Tagged {tagged}/{total} elements ({no_placement} without placement)")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    ifc.write(str(output_path))
    print(f"    Saved to {output_path}")
    return stats


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Tag IFC elements with zone/storey")
    parser.add_argument("--dry-run", action="store_true",
                        help="Show storey data and model list, don't tag")
    parser.add_argument("--model", type=str,
                        help="Tag a single model filename (e.g., '102.02_RIVA_Gen.ifc')")
    args = parser.parse_args()

    print("Analyzing ARK models...")
    t0 = time.time()
    analyses = analyze_buildings(ARK_FILES)
    print(f"  Done in {time.time() - t0:.1f}s\n")

    storey_ranges = build_storey_ranges(analyses)

    # Print storey summary
    for a in analyses:
        prefix = BUILDING_PREFIX.get(a.name, "")
        bb = a.bounding_box
        print(f"=== {a.name} ===")
        print(f"  OBB: ({bb.center_x:.0f}, {bb.center_y:.0f}) "
              f"{bb.width:.0f}x{bb.depth:.0f} mm, rot {bb.angle * 57.2958:.1f}°")
        ranges = storey_ranges.get(a.name, [])
        print(f"  Building storeys ({len(ranges)}, prefix='{prefix}'):")
        for min_z, max_z, name in ranges:
            print(f"    {min_z:>8.0f} – {max_z:>8.0f} mm  {name}")
        print()

    if args.dry_run:
        print("Models to tag:")
        for m in MODELS_TO_TAG:
            p = IFC_DIR / m
            size = p.stat().st_size / 1e6 if p.exists() else 0
            exists = "OK" if p.exists() else "MISSING"
            print(f"  [{exists}] {m} ({size:.1f} MB)")
        return

    # Determine which models to process
    if args.model:
        models = [args.model]
    else:
        models = MODELS_TO_TAG

    print(f"Tagging {len(models)} model(s)...\n")

    for model_name in models:
        model_path = IFC_DIR / model_name
        if not model_path.exists():
            print(f"SKIP {model_name} (not found)")
            continue

        output_path = OUTPUT_DIR / model_name
        size_mb = model_path.stat().st_size / 1e6
        print(f"\n--- {model_name} ({size_mb:.1f} MB) ---")

        t0 = time.time()
        stats = tag_model(model_path, output_path, analyses, storey_ranges)
        elapsed = time.time() - t0

        print(f"    Time: {elapsed:.1f}s")
        for key in sorted(stats.keys()):
            print(f"    {key}: {stats[key]}")

    print("\nDone!")


if __name__ == "__main__":
    main()
