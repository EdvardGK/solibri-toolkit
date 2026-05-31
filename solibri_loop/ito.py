"""Information Takeoff (ITO) directly from an IFC file via IfcOpenShell.

Why Python, not the Solibri ITO engine: Solibri's public SDK exposes ItoDefinition as
read-only (getUniqueId/Name/Description); you can enumerate and execute definitions that
were already created in Solibri's UI, but you cannot DEFINE a takeoff from scratch through
the API. So for "compute a takeoff of model X" with no pre-existing definition, doing it
in IfcOpenShell is the right tool - same job, no Solibri dependency, no UI prerequisite.

Produces: total element count, count by IFC class (top N), count per IfcBuildingStorey,
and a sum of length-type base quantities (pipe/duct segments etc.) grouped by class.

Stdlib + ifcopenshell. Pure read - no mutation, no Solibri needed.
"""

from __future__ import annotations

from collections import Counter, defaultdict
from typing import Optional

import ifcopenshell
import ifcopenshell.util.element
import ifcopenshell.util.unit

# Length keys. Pset_*BaseQuantities use "Length"; Norwegian models often use "Lengde" on
# a custom pset (e.g. G55_RIV puts Lengde on a pset called "RIV"); other vendors:
# NominalLength, Distance.
_LENGTH_KEYS = ("Length", "Lengde", "NominalLength", "Distance")


def _element_length_raw(elem) -> Optional[float]:
    """First length-typed property found on this element (in model units, NOT scaled).
    Scans both quantity sets and property sets - custom psets like 'RIV.Lengde' aren't
    qtos but carry the real length in BIM-Q (Norwegian) deliverables."""
    psets = ifcopenshell.util.element.get_psets(elem) or {}
    for _name, values in psets.items():
        for k in _LENGTH_KEYS:
            if k in values:
                try:
                    return float(values[k])
                except (TypeError, ValueError):
                    pass
    return None


def compute_takeoff(ifc_path: str, *, top_classes: int = 20) -> dict:
    """Open an IFC and produce a structured takeoff.

    Returns:
        {
          "file": path, "schema": "IFC4/2x3/...", "elements": N,
          "by_class":   [(IfcClass, count, total_length_m_or_None), ...],   # all classes
          "by_storey":  [(storey_name, count), ...],
          "lengths_by_class": {IfcClass: total_m},
          "totals": {"length_m": N},
        }
    """
    model = ifcopenshell.open(ifc_path)
    # Project length unit -> meters. Custom psets (e.g. RIV.Lengde) follow this convention
    # by Norwegian-BIM practice; without scaling, "9,126,510 m of pipe" appears in a model
    # whose unit is mm. If you ever load a file where Lengde is genuinely in meters, the
    # scale will be 1.0 (no-op).
    unit_scale = ifcopenshell.util.unit.calculate_unit_scale(model)
    elements = model.by_type("IfcElement")

    by_class: Counter = Counter()
    by_storey: Counter = Counter()
    lengths_by_class: dict[str, float] = defaultdict(float)
    total_length = 0.0

    for elem in elements:
        cls = elem.is_a()
        by_class[cls] += 1
        container = ifcopenshell.util.element.get_container(elem)
        storey = container.Name if container and container.is_a("IfcBuildingStorey") else (
            container.is_a() if container else "(unplaced)")
        by_storey[storey] += 1
        L = _element_length_raw(elem)
        if L is not None:
            L *= unit_scale
            lengths_by_class[cls] += L
            total_length += L

    # by_class table: include length when present, sorted by count desc.
    by_class_table = [(cls, n, round(lengths_by_class.get(cls, 0.0), 2) or None)
                       for cls, n in by_class.most_common()]
    by_storey_table = sorted(by_storey.items(), key=lambda kv: (-kv[1], kv[0]))

    return {
        "file": ifc_path,
        "schema": model.schema,
        "elements": len(elements),
        "unit_scale_m": unit_scale,  # meters per project length unit (mm -> 0.001)
        "by_class": by_class_table,
        "by_storey": by_storey_table,
        "lengths_by_class": {k: round(v, 2) for k, v in
                              sorted(lengths_by_class.items(), key=lambda kv: -kv[1])},
        "totals": {"length_m": round(total_length, 2)},
        "top_classes_shown": top_classes,
    }


def format_takeoff(t: dict) -> str:
    """Compact human-readable table for the CLI."""
    unit_note = "" if t["unit_scale_m"] == 1.0 else f"   (model unit = {t['unit_scale_m']} m)"
    out = [f"file:   {t['file']}",
           f"schema: {t['schema']}   IfcElement total: {t['elements']:,}{unit_note}"]
    if t["totals"]["length_m"]:
        out.append(f"total length (Lengde / Length / NominalLength): {t['totals']['length_m']:,.1f} m")
    out.append("")
    out.append(f"Top {t['top_classes_shown']} classes by count:")
    out.append(f"  {'IfcClass':<32} {'count':>8}   {'length [m]':>12}")
    for cls, n, L in t["by_class"][:t["top_classes_shown"]]:
        out.append(f"  {cls:<32} {n:>8,}   {('' if L is None else f'{L:>12,.1f}')}")
    out.append("")
    out.append("Per IfcBuildingStorey:")
    for name, n in t["by_storey"]:
        out.append(f"  {name:<32} {n:>8,}")
    return "\n".join(out)
