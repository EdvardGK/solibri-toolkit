"""
IFC Analyzer - Fast data extraction and analysis using IfcOpenShell.

This module provides the analytical foundation that feeds into Solibri rules,
classifications, and ITOs. IfcOpenShell handles the heavy data lifting;
Solibri handles visualization, QA, and clash detection.

Usage:
    from analysis.ifc_analyzer import IfcAnalyzer
    analyzer = IfcAnalyzer(r"C:\path\to\model.ifc")
    report = analyzer.property_completeness()
    analyzer.export_for_solibri(report, r"C:\path\to\output.json")
"""

import ifcopenshell
import json
import sys
import os
from collections import defaultdict
from pathlib import Path


class IfcAnalyzer:
    def __init__(self, ifc_path: str):
        self.path = Path(ifc_path)
        self.model = ifcopenshell.open(str(self.path))
        self.schema = self.model.schema

    def summary(self) -> dict:
        """Quick model summary - element counts by IFC type."""
        counts = defaultdict(int)
        for entity in self.model.by_type("IfcProduct"):
            counts[entity.is_a()] += 1
        return dict(sorted(counts.items(), key=lambda x: -x[1]))

    def property_completeness(self, required_psets: dict[str, list[str]] = None) -> dict:
        """
        Analyze property completeness across the model.

        Args:
            required_psets: Dict of {pset_name: [property_names]}
                           If None, analyzes all found property sets.

        Returns:
            Dict with per-type statistics on property presence/absence.
        """
        if required_psets is None:
            required_psets = self._discover_psets()

        results = {}
        for ifc_type in self._get_product_types():
            elements = self.model.by_type(ifc_type)
            if not elements:
                continue

            type_results = {}
            for pset_name, props in required_psets.items():
                for prop_name in props:
                    key = f"{pset_name}.{prop_name}"
                    has_prop = 0
                    missing_prop = 0
                    missing_guids = []

                    for elem in elements:
                        value = self._get_property(elem, pset_name, prop_name)
                        if value is not None:
                            has_prop += 1
                        else:
                            missing_prop += 1
                            if hasattr(elem, 'GlobalId'):
                                missing_guids.append(elem.GlobalId)

                    if has_prop + missing_prop > 0:
                        type_results[key] = {
                            "total": has_prop + missing_prop,
                            "has": has_prop,
                            "missing": missing_prop,
                            "completeness": round(has_prop / (has_prop + missing_prop) * 100, 1),
                            "missing_guids": missing_guids[:50]  # cap for sanity
                        }

            if type_results:
                results[ifc_type] = type_results

        return results

    def element_data(self, ifc_type: str = None, properties: list[str] = None) -> list[dict]:
        """
        Extract element data as flat records - ideal for classification input.

        Args:
            ifc_type: Filter by IFC type (e.g., "IfcWall"). None = all products.
            properties: List of "PsetName.PropertyName" to extract. None = all.

        Returns:
            List of dicts with GlobalId, Name, Type, and requested properties.
        """
        elements = self.model.by_type(ifc_type or "IfcProduct")
        records = []

        for elem in elements:
            record = {
                "GlobalId": getattr(elem, 'GlobalId', None),
                "Name": getattr(elem, 'Name', None),
                "IfcType": elem.is_a(),
            }

            if properties:
                for prop_path in properties:
                    if '.' in prop_path:
                        pset, prop = prop_path.split('.', 1)
                        record[prop_path] = self._get_property(elem, pset, prop)
            else:
                for pset_name, props in self._get_all_properties(elem).items():
                    for prop_name, value in props.items():
                        record[f"{pset_name}.{prop_name}"] = value

            records.append(record)

        return records

    def clash_preparation(self, type_a: str, type_b: str) -> dict:
        """
        Pre-analyze elements for clash detection - identify overlapping bounding boxes.
        IfcOpenShell does the spatial indexing fast; Solibri does the precise geometry check.
        """
        elements_a = self.model.by_type(type_a)
        elements_b = self.model.by_type(type_b)

        return {
            "type_a": type_a,
            "type_b": type_b,
            "count_a": len(elements_a),
            "count_b": len(elements_b),
            "guids_a": [e.GlobalId for e in elements_a],
            "guids_b": [e.GlobalId for e in elements_b],
        }

    def export_for_solibri(self, data: dict, output_path: str):
        """Export analysis results as JSON for consumption by Solibri rules/scripts."""
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2, ensure_ascii=False, default=str)
        print(f"Exported to: {output_path}")

    # --- Internal helpers ---

    def _get_property(self, element, pset_name: str, prop_name: str):
        """Get a single property value from an element."""
        if not hasattr(element, 'IsDefinedBy'):
            return None
        for rel in element.IsDefinedBy:
            if rel.is_a('IfcRelDefinesByProperties'):
                prop_def = rel.RelatingPropertyDefinition
                if prop_def.is_a('IfcPropertySet') and prop_def.Name == pset_name:
                    for prop in prop_def.HasProperties:
                        if prop.Name == prop_name:
                            if hasattr(prop, 'NominalValue') and prop.NominalValue:
                                return prop.NominalValue.wrappedValue
        return None

    def _get_all_properties(self, element) -> dict:
        """Get all properties from an element as {pset: {prop: value}}."""
        result = {}
        if not hasattr(element, 'IsDefinedBy'):
            return result
        for rel in element.IsDefinedBy:
            if rel.is_a('IfcRelDefinesByProperties'):
                prop_def = rel.RelatingPropertyDefinition
                if prop_def.is_a('IfcPropertySet'):
                    pset = {}
                    for prop in prop_def.HasProperties:
                        if hasattr(prop, 'NominalValue') and prop.NominalValue:
                            pset[prop.Name] = prop.NominalValue.wrappedValue
                    if pset:
                        result[prop_def.Name] = pset
        return result

    def _discover_psets(self) -> dict[str, list[str]]:
        """Discover all property sets and properties in the model."""
        psets = defaultdict(set)
        for pset in self.model.by_type("IfcPropertySet"):
            for prop in pset.HasProperties:
                psets[pset.Name].add(prop.Name)
        return {k: sorted(v) for k, v in psets.items()}

    def _get_product_types(self) -> list[str]:
        """Get all unique IFC product types in the model."""
        types = set()
        for entity in self.model.by_type("IfcProduct"):
            types.add(entity.is_a())
        return sorted(types)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python ifc_analyzer.py <model.ifc> [command]")
        print("Commands: summary, psets, completeness, elements")
        sys.exit(1)

    ifc_path = sys.argv[1]
    command = sys.argv[2] if len(sys.argv) > 2 else "summary"

    analyzer = IfcAnalyzer(ifc_path)

    if command == "summary":
        for ifc_type, count in analyzer.summary().items():
            print(f"  {ifc_type:40s} {count:>6}")

    elif command == "psets":
        psets = analyzer._discover_psets()
        for pset_name, props in sorted(psets.items()):
            print(f"\n{pset_name}:")
            for prop in props:
                print(f"  - {prop}")

    elif command == "completeness":
        report = analyzer.property_completeness()
        for ifc_type, props in report.items():
            print(f"\n{ifc_type}:")
            for key, stats in props.items():
                bar = "█" * int(stats['completeness'] / 5) + "░" * (20 - int(stats['completeness'] / 5))
                print(f"  {key:40s} {bar} {stats['completeness']:5.1f}% ({stats['has']}/{stats['total']})")

    elif command == "elements":
        records = analyzer.element_data()
        print(json.dumps(records[:5], indent=2, default=str))
        print(f"... ({len(records)} total elements)")
