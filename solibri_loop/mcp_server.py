"""Solibri MCP server - the agent-facing door onto the Skiplum superuser plugin.

This is a THIN, HONEST skin over `SuperuserClient` (solibri_loop/superuser.py): every tool
calls the exact same method the `cli.py su ...` command calls, against the same in-JVM HTTP
plugin on http://localhost:10999/skiplum/v1. No forked logic, no reimplementation - the CLI
stays the source of truth, this server is one more outlet onto it.

What makes it a real MCP rather than a wrapper dump:
  - read-only vs state-mutating tools are annotated (readOnlyHint / destructiveHint) so a
    client can reason about safety before calling;
  - actions that lose or overwrite session/disk state require an explicit `confirm=True`,
    mirroring the CLI's `--yes` guard (reversible UI ops are NOT gated - move fast);
  - 3D snapshots come back as INLINE IMAGES (MCP ImageContent), so a vision-capable agent
    actually sees the view it framed - the protocol-native version of `snapshot --out x.png`;
  - live session state (model, results, rulesets, project, presentations) is exposed as
    RESOURCES, so a client can attach Solibri context without spending a tool call.

Run (stdio transport):
    python -m solibri_loop.mcp_server

Configure (Claude Code / Desktop mcp servers entry):
    {
      "solibri": {
        "command": "python",
        "args": ["-m", "solibri_loop.mcp_server"],
        "cwd": "C:/workspace/toolkit/solibri",
        "env": {"SOLIBRI_SU_BASE": "http://localhost:10999/skiplum/v1"}
      }
    }

Base URL override: env SOLIBRI_SU_BASE (defaults to the plugin's standard address).
"""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any, Optional

from mcp.server.fastmcp import FastMCP, Image
from mcp.types import ToolAnnotations

from . import launch as launcher
from .ito import compute_takeoff
from .launch import LaunchError
from .superuser import DEFAULT_BASE, SuperuserClient, SuperuserError

# ---------------------------------------------------------------------------
# One shared client. Base URL from env so the same server can point at a remote
# workstation's plugin if ever tunnelled.
# ---------------------------------------------------------------------------
SU_BASE = os.environ.get("SOLIBRI_SU_BASE", DEFAULT_BASE)
_client = SuperuserClient(SU_BASE)

INSTRUCTIONS = f"""\
Drive a running Solibri Office session through the Skiplum superuser plugin ({SU_BASE}).

Two things to know before you act:
  1. IFC GlobalId is the currency. Components, selection, slides, snapshots all key on GUIDs.
  2. Tools are split by safety. Read-only tools (status, model, results, components, rules,
     classifications, presentations, camera) are safe on a live session. State-mutating tools
     are annotated destructive; the ones that LOSE or OVERWRITE state (open/close a project,
     run checking, calculate ITO, patch rule parameters, save, shut down, launch) refuse to
     run without confirm=true. Reversible UI ops (select, fit, set camera, snapshot) just run.

If a tool reports the plugin is unreachable, Solibri is not running (or no project is open);
use solibri_launch(open=..., confirm=true) to start it, then retry.

Typical coordination loop: solibri_results(severity="CRITICAL") -> pick a cluster ->
solibri_snapshot(guids=[...]) to see it -> solibri_set_result_decision / build a slide deck.
"""

mcp = FastMCP("solibri", instructions=INSTRUCTIONS)

# Annotation presets. openWorldHint=True everywhere: every call hits an external app.
_RO = ToolAnnotations(readOnlyHint=True, openWorldHint=True)
_MUT = ToolAnnotations(readOnlyHint=False, destructiveHint=True, openWorldHint=True)
_ADD = ToolAnnotations(readOnlyHint=False, destructiveHint=False, openWorldHint=True)


def _confirm(flag: bool, action: str) -> None:
    """Guard for state-loss / disk-write / app-control actions. Mirrors the CLI's --yes."""
    if not flag:
        raise ValueError(
            f"Refusing to {action} without confirm=true. This mutates or overwrites live "
            f"session/disk state and is not freely reversible - pass confirm=true to proceed."
        )


# ===========================================================================
# Health / orientation (read-only)
# ===========================================================================
@mcp.tool(annotations=_RO)
def solibri_status() -> dict:
    """Is the plugin reachable, and what is loaded? Returns reachable flag, Solibri product
    version, whether a project is open, and a summary of submodels + ruleset ids. The first
    call to make in any session - it tells you whether the rest of the toolset will work."""
    if not _client.ping():
        return {"reachable": False, "base": SU_BASE,
                "hint": "Solibri not running or plugin down. solibri_launch(confirm=true) to start."}
    model = _client.model()
    return {
        "reachable": True,
        "base": SU_BASE,
        "productVersion": _client.product_version(),
        "projectOpen": model.get("open", True) is not False,
        "name": model.get("name"),
        "busy": model.get("busy"),
        "subModels": model.get("subModels", []),
        "rulesetIds": model.get("rulesetIds", []),
    }


@mcp.tool(annotations=_RO)
def solibri_model() -> dict:
    """Open project descriptor: name, busy flag, submodels [{name,uuid}], ruleset ids.
    Returns {"open": false} when no project is loaded."""
    return _client.model()


@mcp.tool(annotations=_RO)
def solibri_project_info() -> dict:
    """Project file metadata: file path, fileStatus (NO_FILE/UNSAVED/SAVED/MODIFIED),
    lastSaveTime, industry, product/version. Use before save to know if there are changes."""
    return _client.project_info()


@mcp.tool(annotations=_RO)
def solibri_recents() -> dict:
    """Solibri's recent-files lists, including provider=BIM360 entries (the ACC handshake).
    Handy for finding a project path to open without the user pasting it."""
    return _client.recents()


@mcp.tool(annotations=_ADD)
def solibri_add_recent_directory(path: str, key: str = "model") -> dict:
    """Pin a folder into Solibri's recent-directories so it shows up as a quick-access
    location in the Open Model browser (and other file choosers for `key`). key defaults to
    "model" (the model-open chooser). Persists across restarts; idempotent (re-adding just
    refreshes the timestamp). Reversible — it only seeds Solibri's recents list."""
    return _client.add_recent_directory(path, key)


@mcp.tool(annotations=_RO)
def solibri_disciplines() -> dict:
    """Valid discipline (BuildingIndustryDomain) values accepted by solibri_set_discipline,
    e.g. ARCHITECTURE, STRUCTURAL_ELEMENTS, VENTILATION, ELECTRICAL."""
    return _client.disciplines()


# ===========================================================================
# Checking results (read-only)
# ===========================================================================
@mcp.tool(annotations=_RO)
def solibri_results(severity: Optional[str] = None, rule: Optional[str] = None,
                    full: bool = False) -> dict:
    """Checking results from the last run. One tool, four modes:
      - no filter            -> all results (name, severity, rule, uniqueKey, components[guids])
      - rule="<exact name>"  -> only that rule's results (adds scanned/skipped counts)
      - severity="CRITICAL"  -> only that severity (CRITICAL|MODERATE|LOW|PASSED, any case)
      - full=true            -> deep payload: category, decision, involved models, component
                                decisions + slide refs (heavier; combine with the filters)
    Resilient: a result that throws inside a Solibri getter is counted in `skipped`, not fatal.
    Run solibri_check first if results are empty/stale."""
    if rule:
        return _client.results_by_rule(rule, full=full)
    if severity:
        return _client.results_by_severity(severity, full=full)
    if full:
        return _client.results_full()
    return _client.results()


@mcp.tool(annotations=_RO)
def solibri_rules() -> dict:
    """Flat list of every Rule across all rulesets: name, ruleset, helpUrl, and parameters
    [{id,type,value}]. This is how you discover rule names for solibri_results(rule=...),
    solibri_rule_components, and solibri_set_rule_parameters."""
    return _client.rules()


@mcp.tool(annotations=_RO)
def solibri_rulesets(with_rules: bool = False) -> Any:
    """List rulesets in the open project. with_rules=true includes the full rule tree via
    Ruleset.toJson() (can be hundreds of KB - only request it when you need the tree)."""
    return _client.rulesets(with_json=with_rules)


@mcp.tool(annotations=_RO)
def solibri_rule_parameters(rule_name: str) -> dict:
    """Inspect one rule's parameter list (id, type, value, options, pset/prop). Read this
    before solibri_set_rule_parameters so you patch the right parameter ids."""
    return _client.rule_parameters(rule_name)


@mcp.tool(annotations=_RO)
def solibri_rule_components(rule_name: str, status: str = "checked", limit: int = 5000) -> dict:
    """Component GUIDs touched by a rule. status = passed | failed | checked. Feeds a
    snapshot or a slide deck of exactly the elements a rule flagged."""
    return _client.rule_components(rule_name, status=status, limit=limit)


# ===========================================================================
# Component / model queries (read-only)
# ===========================================================================
@mcp.tool(annotations=_RO)
def solibri_find_components(pset: str, prop: str, value: str = "", op: str = "equals",
                           limit: int = 200) -> dict:
    """Find components by a property value. op = equals | prefix | contains | exists.
    Example: pset="G55_Prosjektinfo", prop="MMI", value="350", op="prefix". Off-EDT scan,
    default limit 200 (max 10000). Returns {count, limit, components:[...]}."""
    return _client.components(pset, prop, value=value, op=op, limit=limit)


@mcp.tool(annotations=_RO)
def solibri_component_count(guids: list[str]) -> dict:
    """For each IFC GUID, how many components the federated model holds (a duplicate /
    double-modelled probe). Returns per-guid countInModel, type, name."""
    return _client.component(guids)


@mcp.tool(annotations=_RO)
def solibri_classifications() -> dict:
    """List classifications in the open project (e.g. NS3451, ARK_Space, MMI)."""
    return _client.classifications()


@mcp.tool(annotations=_RO)
def solibri_classification(name: str) -> dict:
    """Detail of one classification: its classes, columns, and table rows."""
    return _client.classification(name)


@mcp.tool(annotations=_RO)
def solibri_model_metadata(submodel_uuid: str) -> dict:
    """Read a submodel's metadata map (find the uuid via solibri_model().subModels)."""
    return _client.model_metadata(submodel_uuid)


# ===========================================================================
# Presentations / BCF (read-only reads; create/export are additive)
# ===========================================================================
@mcp.tool(annotations=_RO)
def solibri_presentations() -> dict:
    """List presentations in the open project: uniqueId, name, slideCount each."""
    return _client.presentations()


@mcp.tool(annotations=_RO)
def solibri_presentation_slides(presentation_id: str) -> dict:
    """Slides in a presentation with full metadata: title, status, stage, priority, type,
    dueDate, components, viewpoint/comment counts."""
    return _client.presentation_slides(presentation_id)


@mcp.tool(annotations=_ADD)
def solibri_create_presentation(name: str, prefix: Optional[str] = None) -> dict:
    """Create a new (empty) Presentation in the open project. Additive and reversible
    (close without save to discard). Returns the new presentation's uniqueId."""
    return _client.create_presentation(name, prefix=prefix)


@mcp.tool(annotations=_ADD)
def solibri_add_slide(presentation_id: str, title: str, description: Optional[str] = None,
                      status: Optional[str] = None, stage: Optional[str] = None,
                      priority: Optional[str] = None, slide_type: Optional[str] = None,
                      due_date: Optional[str] = None, components: Optional[list[str]] = None,
                      responsibilities: Optional[list[str]] = None,
                      labels: Optional[list[str]] = None) -> dict:
    """Add a slide (BCF topic) to a presentation. components = IFC GUIDs the slide is about,
    due_date = ISO YYYY-MM-DD. Additive. Build a coordination deck by looping results clusters."""
    slide: dict = {"title": title}
    for k, v in (("description", description), ("status", status), ("stage", stage),
                 ("priority", priority), ("type", slide_type), ("dueDate", due_date)):
        if v is not None:
            slide[k] = v
    if components:
        slide["components"] = components
    if responsibilities:
        slide["responsibilities"] = responsibilities
    if labels:
        slide["labels"] = labels
    return _client.add_slide(presentation_id, slide)


@mcp.tool(annotations=_ADD)
def solibri_export_bcfxml(file: str, version: str = "V2_1", scope: str = "ALL") -> dict:
    """Export presentations to a BCF XML zip on disk. version = V1_0|V2_0|V2_1|V3_0,
    scope = ALL|SELECTED|MARKED. Writes one file; safe (does not touch the project)."""
    return _client.export_bcfxml(file, version=version, scope=scope)


# ===========================================================================
# 3D view: selection, camera, snapshot (reversible UI - not gated)
# ===========================================================================
@mcp.tool(annotations=_ADD)
def solibri_select(guids: list[str]) -> dict:
    """Set the 3D selection basket to these IFC GUIDs. Reversible UI op. Returns how many
    GUIDs resolved to components. Pair with solibri_snapshot to see what you selected."""
    return _client.select(guids)


@mcp.tool(annotations=_RO)
def solibri_camera() -> dict:
    """Current 3D camera state: location, direction, up, projection, fov/scale."""
    return _client.threed_camera()


@mcp.tool(annotations=_ADD)
def solibri_set_camera(location: list[float], direction: list[float], up: list[float],
                       projection: str = "perspective", fov: Optional[float] = None,
                       scale: Optional[float] = None) -> dict:
    """Aim the 3D camera. location/direction/up are [x,y,z]. projection=perspective|orthogonal
    (fov in radians for perspective, scale for orthogonal). Reversible UI op."""
    return _client.set_threed_camera(location, direction, up, projection=projection,
                                     field_of_view=fov, view_to_world_scale=scale)


@mcp.tool(annotations=_ADD)
def solibri_fit(target: str = "selection") -> dict:
    """Fit the camera to the current selection or the whole model. target = selection | model."""
    return _client.threed_fit(target)


@mcp.tool(annotations=_ADD)
def solibri_snapshot(guids: Optional[list[str]] = None, width: Optional[int] = None,
                     height: Optional[int] = None, save_to: Optional[str] = None) -> Image:
    """Capture the 3D viewport and return it as an INLINE IMAGE you can actually look at.

    If `guids` is given, the basket is set to those components and the camera is fit to them
    first (so you frame exactly what you want before the shot). Optional width/height resize
    the capture; save_to also writes the PNG to that path on disk. This is the vision loop:
    select -> frame -> see, all in one call."""
    if guids:
        _client.select(guids)
        _client.threed_fit("selection")
    out = save_to or tempfile.mkstemp(prefix="solibri_snap_", suffix=".png")[1]
    res = _client.threed_snapshot(out, width=width, height=height)
    if not res.get("saved"):
        raise RuntimeError(f"snapshot failed: {res.get('error', res)}")
    data = Path(out).read_bytes()
    if not save_to:
        try:
            Path(out).unlink()
        except OSError:
            pass
    return Image(data=data, format="png")


# ===========================================================================
# Mutating QA / decisions (reversible-in-session - not gated, but destructive-hinted)
# ===========================================================================
@mcp.tool(annotations=_MUT)
def solibri_set_result_decision(unique_key: str, decision: str) -> dict:
    """Set the QA decision on a checking Result. decision = ACCEPTED | REJECTED | UNDEFINED.
    Closes the loop on triage. Reversible (set it again), but it mutates session QA state."""
    return _client.set_result_decision(unique_key, decision)


@mcp.tool(annotations=_MUT)
def solibri_set_discipline(submodel_uuid: str, value: str) -> dict:
    """Tag a submodel's discipline (value from solibri_disciplines()). Drives discipline-aware
    checking. Reversible by re-setting; mutates session model state."""
    return _client.set_discipline(submodel_uuid, value)


@mcp.tool(annotations=_MUT)
def solibri_create_classification_bulk(name: str, assignments: dict) -> dict:
    """Create a classification and bulk-assign GUIDs to classes. assignments =
    {className: [guid, ...], ...}. Additive to the project (reversible by closing unsaved);
    can be multi-MB. The classification-first half of discipline-aware coordination."""
    return _client.create_classification_bulk(name, assignments)


@mcp.tool(annotations=_MUT)
def solibri_install_ruleset(ruleset_json: str) -> dict:
    """Install a ruleset into the open project from its JSON (Ruleset.toJson() shape). No temp
    files, no UI prompts. Adds to the project's ruleset list (reversible by closing unsaved)."""
    return _client.install_ruleset(ruleset_json)


# ===========================================================================
# State-loss / disk / blocking / app-control - GATED by confirm
# ===========================================================================
@mcp.tool(annotations=_MUT)
def solibri_open(path: str, name: Optional[str] = None, confirm: bool = False) -> dict:
    """Open a file in-JVM. .smc/.smcx opens a full project (REPLACES whatever is open -
    state loss); .ifc ADDS a model to the already-open project. Gated: confirm=true."""
    _confirm(confirm, f"open {path} (replaces the current project)")
    return _client.open_file(path, name=name)


@mcp.tool(annotations=_MUT)
def solibri_close(confirm: bool = False) -> dict:
    """Close the open project (discards unsaved session state). Gated: confirm=true."""
    _confirm(confirm, "close the open project")
    return _client.close_project()


@mcp.tool(annotations=_MUT)
def solibri_check(confirm: bool = False) -> dict:
    """Run checking on the open project. Mutates the live session and can take minutes.
    Gated: confirm=true. Returns the fresh result set."""
    _confirm(confirm, "run checking (slow, mutates the session)")
    return _client.check()


@mcp.tool(annotations=_MUT)
def solibri_set_rule_parameters(rule_name: str, values: dict, confirm: bool = False) -> dict:
    """Patch a rule's parameters in-session. values = {parameter_id: value}. Read current ids
    with solibri_rule_parameters first. Mutates ruleset state. Gated: confirm=true."""
    _confirm(confirm, f"patch parameters on rule {rule_name!r}")
    return _client.set_rule_parameters(rule_name, values)


@mcp.tool(annotations=_RO)
def solibri_ito_list() -> dict:
    """List ItoDefinitions in the open project (id, name, description)."""
    return _client.ito()


@mcp.tool(annotations=_RO)
def solibri_ito_results() -> dict:
    """Current ITO results: columns, rows (typed cells), totals."""
    return _client.ito_results()


@mcp.tool(annotations=_MUT)
def solibri_ito_calculate(ito_id: Optional[str] = None, confirm: bool = False) -> dict:
    """Calculate all ITOs (ito_id omitted) or one by UUID. BLOCKS the session until done,
    can take minutes on large models. Gated: confirm=true."""
    _confirm(confirm, "calculate ITO (blocks the session)")
    return _client.ito_calculate(ito_id)


@mcp.tool(annotations=_MUT)
def solibri_project_save(confirm: bool = False) -> dict:
    """Save the open project back to its current file (writes disk). 409 if it has no file -
    use solibri_project_save_as. Gated: confirm=true."""
    _confirm(confirm, "save the project to disk")
    return _client.project_save()


@mcp.tool(annotations=_MUT)
def solibri_project_save_as(path: str, confirm: bool = False) -> dict:
    """Save the open project to a new .smc/.smcx (writes disk; the project's file becomes
    `path`). The snapshot primitive. Gated: confirm=true."""
    _confirm(confirm, f"save the project as {path}")
    return _client.project_save_as(path)


@mcp.tool(annotations=_MUT)
def solibri_project_set_industry(value: str) -> dict:
    """Set Project.Industry (UNDEFINED|ARCHITECTURE|ENGINEERING|CONSULTING|CONSTRUCTION|
    OTHER_AEC). Persists on next save."""
    return _client.project_set_industry(value)


# ===========================================================================
# Lifecycle - start / stop the Solibri process itself (GATED)
# ===========================================================================
@mcp.tool(annotations=_RO)
def solibri_up() -> dict:
    """Which Solibri servers are up (plugin 10999 + built-in 10876) and what's open, without
    starting anything. The process-level health check."""
    return launcher.status(base=SU_BASE)


@mcp.tool(annotations=_MUT)
def solibri_launch(open: Optional[str] = None, api: bool = False, confirm: bool = False) -> dict:
    """Start a Solibri instance (a window appears - there is no supported headless mode) and
    wait for the plugin. open = a .smc/.smcx/.ifc to load on startup; api=true also brings up
    the legacy 10876 server. Gated: confirm=true."""
    _confirm(confirm, "launch a new Solibri process")
    return launcher.launch(api=api, open_file=open, wait=True, base=SU_BASE)


@mcp.tool(annotations=_MUT)
def solibri_shutdown(force: bool = False, confirm: bool = False) -> dict:
    """Stop the running Solibri instance (loses unsaved state). force=true is immediate.
    Gated: confirm=true."""
    _confirm(confirm, "shut down Solibri")
    return _client.shutdown(force=force)


# ===========================================================================
# IFC-native takeoff via IfcOpenShell - no Solibri needed (read-only on a file)
# ===========================================================================
@mcp.tool(annotations=ToolAnnotations(readOnlyHint=True, openWorldHint=False))
def solibri_ifc_takeoff(path: str, top: int = 20) -> dict:
    """Information takeoff straight from an IFC file via IfcOpenShell - no Solibri required.
    Returns total element count, counts by IFC class (top N) and by storey, and summed
    length-type quantities by class (handles Norwegian custom psets like RIV.Lengde).
    Use this for quick quantity sanity on a file before/without loading a project."""
    return compute_takeoff(path, top_classes=top)


# ===========================================================================
# Resources - live session state, attachable without a tool call
# ===========================================================================
def _safe(fn) -> str:
    try:
        return json.dumps(fn(), ensure_ascii=False, indent=2)
    except (SuperuserError, LaunchError) as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)


@mcp.resource("solibri://model", mime_type="application/json")
def res_model() -> str:
    """The open project: name, submodels, ruleset ids."""
    return _safe(_client.model)


@mcp.resource("solibri://project", mime_type="application/json")
def res_project() -> str:
    """Project file metadata: path, save status, industry, version."""
    return _safe(_client.project_info)


@mcp.resource("solibri://results", mime_type="application/json")
def res_results() -> str:
    """Current checking results."""
    return _safe(_client.results)


@mcp.resource("solibri://rulesets", mime_type="application/json")
def res_rulesets() -> str:
    """Rulesets in the open project (names/ids; not the full tree)."""
    return _safe(lambda: _client.rulesets(with_json=False))


@mcp.resource("solibri://presentations", mime_type="application/json")
def res_presentations() -> str:
    """Presentations in the open project."""
    return _safe(_client.presentations)


@mcp.resource("solibri://classifications", mime_type="application/json")
def res_classifications() -> str:
    """Classifications in the open project."""
    return _safe(_client.classifications)


# ===========================================================================
# Prompts - guided multi-step workflows
# ===========================================================================
@mcp.prompt()
def triage_clashes(severity: str = "CRITICAL") -> str:
    """Guide the agent through triaging checking results at a given severity."""
    return (
        f"Triage the {severity} checking results in the open Solibri project.\n"
        f"1. Call solibri_results(severity=\"{severity}\") to get the hits.\n"
        "2. Group them by rule and by the involved components (shared GUIDs = same conflict).\n"
        "3. For each cluster, call solibri_snapshot(guids=[...]) and look at the image to judge "
        "whether it is a real clash or by-design (e.g. reference/existing geometry, or an "
        "ARK<->RIB slab overlap that is expected).\n"
        "4. Summarise each real cluster in one Norwegian sentence with a recommended action, and "
        "propose a solibri_set_result_decision for the false positives (REJECTED).\n"
        "Do not set any decision or run checking without explicit confirmation from me."
    )


@mcp.prompt()
def build_coordination_deck(name: str = "Coordination") -> str:
    """Guide the agent through building a presentation deck from current results."""
    return (
        f"Build a coordination presentation called \"{name}\" from the current checking results.\n"
        "1. solibri_results(full=true) to get results with components and categories.\n"
        "2. solibri_create_presentation(name) to make the deck.\n"
        "3. For each meaningful cluster of results, solibri_snapshot(guids=[...]) to verify, then "
        "solibri_add_slide(presentation_id, title=..., components=[...], priority=...) with a "
        "concise Norwegian title and the involved GUIDs.\n"
        "4. When done, solibri_export_bcfxml(file=..., version=\"V2_1\") to hand off to ACC.\n"
        "Keep slides few and high-signal - one per real, distinct coordination issue."
    )


def main() -> None:
    """Console entry point - stdio transport."""
    mcp.run()


if __name__ == "__main__":
    main()
