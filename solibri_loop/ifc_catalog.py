"""Catalog every (model, IfcClass, pset, property) combination across a federation.

Output is JSON: {file -> {schema, total_elements, by_class: {IfcClass -> count},
                          psets: {pset_name -> {prop_name -> {count, sample_values:[...]}}}}}
Reference for targeting properties/filters in custom rules and classifications.

Stdlib + ifcopenshell. Pure read.
"""
from __future__ import annotations
import json, os, time
from collections import Counter, defaultdict
from typing import Optional

import ifcopenshell
import ifcopenshell.util.element as ue


def catalog_model(path: str, *, max_sample_values: int = 8) -> dict:
    """Walk every IfcElement in `path`, collect pset/property usage with sample values."""
    t0 = time.time()
    m = ifcopenshell.open(path)
    elements = m.by_type("IfcElement")
    by_class: Counter = Counter()
    psets: dict[str, dict[str, dict]] = defaultdict(lambda: defaultdict(
        lambda: {"count": 0, "sample_values": []}))
    for e in elements:
        by_class[e.is_a()] += 1
        try:
            ps = ue.get_psets(e) or {}
        except Exception:
            continue
        for ps_name, ps_vals in ps.items():
            for k, v in ps_vals.items():
                if k == "id":
                    continue
                slot = psets[ps_name][k]
                slot["count"] += 1
                if len(slot["sample_values"]) < max_sample_values:
                    s = str(v)[:80]
                    if s not in slot["sample_values"]:
                        slot["sample_values"].append(s)
    return {
        "file": path,
        "schema": m.schema,
        "total_elements": len(elements),
        "load_seconds": round(time.time() - t0, 1),
        "by_class": dict(by_class.most_common()),
        "psets": {ps: dict(props) for ps, props in psets.items()},
    }


def catalog_federation(ifc_dir: str, *, files: Optional[list[str]] = None,
                       out_path: Optional[str] = None) -> dict:
    """Catalog a folder of IFCs (or a named subset). Sorted by file size, small first."""
    if files is None:
        files = sorted(f for f in os.listdir(ifc_dir) if f.lower().endswith(".ifc"))
    sized = [(f, os.path.getsize(os.path.join(ifc_dir, f))) for f in files]
    sized.sort(key=lambda x: x[1])
    out = {}
    for fn, sz in sized:
        full = os.path.join(ifc_dir, fn)
        print(f"  cataloging {fn:<40} ({sz/1_048_576:>6.0f} MB)...", flush=True)
        try:
            out[fn] = catalog_model(full)
            n = out[fn]["total_elements"]
            ps = len(out[fn]["psets"])
            print(f"    -> {n:>6} elements, {ps} distinct psets, {out[fn]['load_seconds']}s")
        except Exception as e:
            out[fn] = {"file": full, "error": str(e)}
            print(f"    -> ERROR: {e}")
    if out_path:
        with open(out_path, "w", encoding="utf-8") as fh:
            json.dump(out, fh, indent=2, ensure_ascii=False)
    return out


def find_property(catalog: dict, prop_pattern: str, *, case_insensitive: bool = True) -> list[dict]:
    """Search the catalog for any property whose name contains `prop_pattern`.
    Returns list of {file, ifc_class_total, pset, prop, count, samples}."""
    import re
    flags = re.I if case_insensitive else 0
    pat = re.compile(prop_pattern, flags)
    hits = []
    for fn, fdata in catalog.items():
        for ps_name, props in (fdata.get("psets") or {}).items():
            for prop_name, prop_data in props.items():
                if pat.search(prop_name) or pat.search(ps_name):
                    hits.append({
                        "file": fn,
                        "pset": ps_name,
                        "prop": prop_name,
                        "count": prop_data["count"],
                        "samples": prop_data["sample_values"],
                    })
    return hits
