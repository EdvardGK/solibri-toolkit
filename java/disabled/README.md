# Disabled — earlier SDK rules and ITOs

All five custom classes built during the 2026-03 SDK exploration are parked here. None earned their keep:

**Zone/storey classification** — pivot away from SDK approach (Python tagging took over, then that effort was also retired):
- `info/BuildingZoneInfo.java` — auto-discovers zones from clash_zones model, Polygon2d containment
- `info/StoreyElevationInfo.java` — assigns ARK Kv B storey based on elevation
- `rules/ZoneScopeRule.java` — flags out-of-scope components (later deemed wrong approach)

**Other** — duplicate-of-builtin / hello-world:
- `rules/PropertyExistsRule.java` — duplicates Solibri's SOL/101 (Required Properties); intended IfcOpenShell input integration was never wired
- `info/MiddleElevationInfo.java` — example/hello-world ITO

To re-enable: move files back to their original locations under `src/main/java/no/skiplum/solibri/{info,rules}` and `src/main/resources/no/skiplum/solibri/{info,rules}`, and re-create `src/main/resources/META-INF/services/com.solibri.smc.api.info.Information` listing each Information class on its own line.
