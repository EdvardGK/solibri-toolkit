"""Client over the Skiplum "superuser" plugin (stdlib only - no deps).

The plugin is a small HTTP server running INSIDE the Solibri JVM, on
http://localhost:10999/skiplum/v1, backed by the full SMC SDK. It fills the gaps the
built-in REST API (port 10876) cannot: list rulesets + full rule trees, read checking
results, query components by property, list/load/save classifications, and open project
files in-JVM.

Unlike the built-in 10876 server (which only runs when Solibri is launched with the
--rest-api-server-* flags), this plugin starts on EVERY Solibri launch and, as of plugin
v6, has no dependency on 10876 at all. See docs/SUPERUSER.md and the plugin source at
C:/dev/solibri-bim-tools/java/.../SuperuserServer.java.

Read-only methods are safe on a live session. Methods that mutate the session or take
minutes are marked DISRUPTIVE in their docstring.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Optional

DEFAULT_BASE = "http://localhost:10999/skiplum/v1"


class SuperuserError(RuntimeError):
    """Raised when the plugin returns a non-2xx response or is unreachable."""


class SuperuserClient:
    def __init__(self, base_url: str = DEFAULT_BASE, timeout: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    # ---- low-level -------------------------------------------------------
    def _request(self, method: str, path: str, *, query: dict | None = None,
                 data: bytes | None = None, headers: dict | None = None,
                 timeout: float | None = None) -> tuple[int, bytes]:
        url = f"{self.base_url}/{path.lstrip('/')}"
        if query:
            url += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
        req = urllib.request.Request(url, data=data, method=method)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            body = e.read()
            raise SuperuserError(f"{method} {path} -> HTTP {e.code}: "
                                 f"{body[:300].decode('utf-8', 'replace')}") from e
        except urllib.error.URLError as e:
            raise SuperuserError(f"{method} {path} -> unreachable: {e.reason} "
                                 f"(is Solibri running? the plugin listens on {self.base_url})") from e
        except (ConnectionError, TimeoutError, OSError) as e:
            # Bare socket errors: connection reset (e.g. JVM dying mid-response on shutdown),
            # connection refused after the port unbinds, timeouts. URLError above covers many
            # but not all - ConnectionResetError comes through raw from getresponse().
            raise SuperuserError(f"{method} {path} -> connection error: {e}") from e

    def _get(self, path: str, query: dict | None = None, timeout: float | None = None) -> Any:
        _, body = self._request("GET", path, query=query, timeout=timeout)
        return json.loads(body.decode("utf-8")) if body else None

    def _post(self, path: str, query: dict | None = None, data: bytes | None = None,
              timeout: float | None = None) -> Any:
        _, body = self._request("POST", path, query=query, data=data, timeout=timeout)
        return json.loads(body.decode("utf-8")) if body else None

    # ---- health / state (read-only) -------------------------------------
    def ping(self) -> bool:
        try:
            return (self._get("ping", timeout=5) or {}).get("answer") == "pong"
        except SuperuserError:
            return False

    def product_version(self) -> str:
        return (self._get("ping", timeout=5) or {}).get("productVersion", "")

    def model(self) -> dict:
        """Open project: {name, busy, subModels:[{name,uuid}], rulesetIds:[...]}.
        Returns {"open": False} when no project is open."""
        return self._get("model") or {}

    def is_project_open(self) -> bool:
        return self.model().get("open", True) is not False

    def recents(self) -> dict:
        """Solibri's recent-files lists (incl. provider=BIM360 = ACC handshake)."""
        return self._get("recents") or {}

    def add_recent_directory(self, path: str, key: str = "model") -> dict:
        """Pin a folder into Solibri's recent-directories registry so it shows up as a
        quick-access location in the Open Model browser (and other file choosers for that
        key). key defaults to "model" (the model-open chooser, stored as <ver>-model-dir-N).
        Persists to Java Preferences, so it survives restarts; idempotent (re-adding just
        refreshes the timestamp). DISRUPTIVE (mutates Solibri's persisted UI state).
        -> {added, key, recentDirCount}."""
        return self._post("recents/add-directory", query={"path": path, "key": key}) or {}

    # ---- rulesets (read-only) -------------------------------------------
    def rulesets(self, with_json: bool = False) -> list:
        """List rulesets in the open project. with_json=True includes the full rule
        tree via Ruleset.toJson() (can be hundreds of KB)."""
        return self._get("rulesets", query={"json": "1"} if with_json else None,
                          timeout=120) or []

    # ---- checking results (read-only) -----------------------------------
    def results(self) -> dict:
        """Current checking results: {count, skipped, results:[{name,description,
        severity,rule,uniqueKey,location,components:[guids]}]}. Resilient: a result that
        throws inside a Solibri internal getter is counted in `skipped`, not fatal."""
        return self._get("results", timeout=120) or {}

    # ---- component queries (read-only) ----------------------------------
    def components(self, pset: str, prop: str, value: str = "", op: str = "equals",
                   limit: int = 200) -> dict:
        """Find components by property. op = equals | prefix | contains | exists.
        Off-EDT scan; default limit 200 (max 10000). -> {count, limit, components}."""
        return self._get("components", query={"pset": pset, "prop": prop, "value": value,
                                              "op": op, "limit": limit}, timeout=120) or {}

    def component(self, guids: list[str]) -> dict:
        """For each IFC GUID, how many components the model holds (dedup probe).
        -> {requested, totalComponentsInModel, results:[{guid,countInModel,type,name}]}."""
        return self._get("component", query={"guid": ",".join(guids)}, timeout=120) or {}

    def components_bbox(self, guids: list[str]) -> dict:
        """Per-GUID world axis-aligned bounding box + type/name/model. Single off-EDT model
        pass; batch the GUID list. -> {count, boxes:[{guid,type,name,model,min:[x,y,z],
        max:[x,y,z],center:[x,y,z]}]}. min/max in model units (mm). Powers orientation
        (wall normal = thin axis, run = long axis) + spatial duplicate detection."""
        return self._get("components/bbox", query={"guid": ",".join(guids)}, timeout=120) or {}

    # ---- classifications (read; load/save mutate the session) -----------
    def classifications(self) -> dict:
        """List classifications in the open project."""
        return self._get("classifications") or {}

    def classification(self, name: str) -> dict:
        """Detail of one classification: classes, columns, table rows."""
        return self._get(f"classifications/{urllib.parse.quote(name)}") or {}

    def load_classification(self, file: str) -> dict:
        """Load a .classification file into the running session. DISRUPTIVE (adds to session)."""
        return self._post("classifications/load", query={"file": file}) or {}

    def save_classification(self, name: str, file: str) -> dict:
        """Persist a classification to disk via Classification.saveIntoFile."""
        return self._post(f"classifications/{urllib.parse.quote(name)}/save",
                          query={"file": file}) or {}

    # ---- selection (write: drives the UI selection basket) --------------
    def select(self, ifc_guids: list[str]) -> dict:
        """Resolve GUIDs to components and set the selection basket. DISRUPTIVE (UI).
        -> {requested, guidsResolved, componentsSelected}."""
        data = json.dumps(list(ifc_guids)).encode("utf-8")
        return self._post("select", data=data, timeout=120) or {}

    # ---- checking (DISRUPTIVE) ------------------------------------------
    def check(self, timeout: float = 1800.0) -> dict:
        """Run checking. DISRUPTIVE: mutates the live session, may take minutes.
        -> {count, results:[...]}."""
        return self._post("check", timeout=timeout) or {}

    # ---- project lifecycle (DISRUPTIVE) ---------------------------------
    def open_file(self, path: str, name: Optional[str] = None, timeout: float = 600.0) -> dict:
        """Open a file in-JVM (no built-in 10876 needed). DISRUPTIVE.

        .smc / .smcx -> opens a full project via ProjectRobot.
        .ifc etc.    -> ADDS the model to the OPEN project via SMC.openModel; returns 409
                        if no project is open (open a .smc/.smcx first)."""
        q = {"path": path}
        if name:
            q["name"] = name
        return self._post("projects/openFile", query=q, timeout=timeout) or {}

    def close_project(self) -> dict:
        return self._post("projects/close") or {}

    # ---- checking-results-decisions (wave 1) --------------
    def results_full(self) -> dict:
        """Deep checking results: per Result adds category/categories, decision, involved
        models, hasCustomUniqueKey, componentDecisions, componentSlides, slide ref. Heavier
        payload than results(); resilient (bad results counted in `skipped`)."""
        return self._get("results/full", timeout=180) or {}

    def results_by_rule(self, rule: str, full: bool = False) -> dict:
        """Filter checking results by exact Rule name. full=True uses the deep serializer.
        -> {count, scanned, skipped, rule, results:[...]}"""
        q: dict = {"rule": rule}
        if full:
            q["full"] = "1"
        return self._get("results/byRule", query=q, timeout=180) or {}

    def results_by_severity(self, sev: str, full: bool = False) -> dict:
        """Filter checking results by Severity (CRITICAL|MODERATE|LOW|PASSED, case-insensitive).
        full=True uses the deep serializer. -> {count, scanned, skipped, severity, results}."""
        q: dict = {"sev": sev}
        if full:
            q["full"] = "1"
        return self._get("results/bySeverity", query=q, timeout=180) or {}

    def set_result_decision(self, unique_key: str, decision: str) -> dict:
        """Set Decision on a Result (ACCEPTED|REJECTED|UNDEFINED). DISRUPTIVE (mutates session
        QA state). -> {uniqueKey, before, requested, after}."""
        path = f"results/{urllib.parse.quote(unique_key, safe='')}/decision"
        return self._post(path, query={"decision": decision}) or {}

    # ---- checking-rules-parameters (wave 1) ---------------
    # ---- rules (read-only) ----------------------------------------------
    def rules(self) -> dict:
        """Flat list of all Rule nodes across rulesets -> {count, rules:[{name,ruleset,helpUrl,parameters:[{id,type,value}]}]}."""
        return self._get("rules", timeout=60) or {}

    def rule_parameters(self, rule_name: str) -> dict:
        """Inspect a single rule's parameter list (id, type, value, options, pset/prop)."""
        return self._get(f"rules/{urllib.parse.quote(rule_name, safe='')}/parameters",
                          timeout=30) or {}

    def set_rule_parameters(self, rule_name: str, values: dict) -> dict:
        """Patch RuleParameter values. DISRUPTIVE (mutates ruleset state in-session).

        values: {parameter_id: value}. Booleans/ints/doubles/strings accepted; coerced to
        string on the wire and parsed back to the parameter's native type by the plugin.
        Returns {appliedCount, failedCount, applied:[{id,value}], failed:[{id,error}]}."""
        body = json.dumps({k: ("true" if v is True else "false" if v is False else str(v))
                            for k, v in values.items()}).encode("utf-8")
        return self._post(f"rules/{urllib.parse.quote(rule_name, safe='')}/parameters",
                           data=body, timeout=30) or {}

    def rule_components(self, rule_name: str, status: str = "checked",
                          limit: int = 5000) -> dict:
        """Per-rule component GUIDs. status = passed | failed | checked.
        -> {rule, status, total, returned, limit, guids:[...]}"""
        return self._get(f"rules/{urllib.parse.quote(rule_name, safe='')}/components",
                          query={"status": status, "limit": limit}, timeout=60) or {}

    # ---- coordination-presentations-slides (wave 1) -------
    def presentations(self) -> dict:
        """List presentations in the open project: {count, presentations:[{uniqueId,name,slideCount}]}."""
        return self._get("presentations") or {}

    def presentation_slides(self, presentation_id: str) -> dict:
        """List slides in a presentation: {presentationId,count,slides:[{uniqueId,title,status,stage,priority,type,dueDate,viewpointCount,commentCount,components}]}."""
        return self._get(f"presentations/{urllib.parse.quote(presentation_id, safe='')}/slides",
                          timeout=60) or {}

    def create_presentation(self, name: str, prefix: Optional[str] = None) -> dict:
        """Create a new Presentation in the open project. DISRUPTIVE."""
        body = {"name": name}
        if prefix:
            body["prefix"] = prefix
        data = json.dumps(body).encode("utf-8")
        return self._post("presentations", data=data) or {}

    def add_slide(self, presentation_id: str, slide: dict) -> dict:
        """Add a Slide to a presentation. DISRUPTIVE.

        slide fields: title, description, status, stage, priority, type, dueDate (ISO),
        components (IFC GUIDs), responsibilities, labels."""
        data = json.dumps(slide).encode("utf-8")
        return self._post(f"presentations/{urllib.parse.quote(presentation_id, safe='')}/slides",
                           data=data, timeout=120) or {}

    def export_bcfxml(self, file: str, version: str = "V2_1", scope: str = "ALL") -> dict:
        """Export the open project's presentations as a BCF XML zip to disk.

        version = V1_0|V2_0|V2_1|V3_0, scope = ALL|SELECTED|MARKED."""
        return self._post("bcfxml/export",
                          query={"file": file, "version": version, "scope": scope},
                          timeout=300) or {}

    # ---- ito-full-coverage (wave 1) -----------------------
    def ito(self) -> dict:
        """List ItoDefinitions in the open project: {count, definitions:[{id,name,description}]}."""
        return self._get("ito") or {}

    def ito_calculate(self, ito_id: Optional[str] = None, timeout: float = 1800.0) -> dict:
        """Calculate all ITOs (id=None) or one by UUID. DISRUPTIVE: blocks until the takeoff completes, may take minutes on large models. -> {calculated, definitions, rows, elapsedMs}."""
        q = {"id": ito_id} if ito_id else None
        return self._post("ito/calculate", query=q, timeout=timeout) or {}

    def ito_results(self) -> dict:
        """Current ITO results: {columns:[{name,type,numeric}], count, skipped, rows:[[cell,...]], total:[cell,...]|null}. Cell values are JSON-typed via ItoColumn.PropertyType (numbers as numbers, booleans as booleans, everything else stringified)."""
        return self._get("ito/results", timeout=120) or {}

    # ---- project-save-metadata (wave 1) -------------------
    # ---- project metadata / lifecycle (read + save) ---------------------
    def project_info(self) -> dict:
        """Project metadata: file, fileStatus (NO_FILE/UNSAVED/SAVED/MODIFIED), lastSaveTime,
        industry, product/version. Returns {"open": False} when nothing is loaded."""
        return self._get("project/info") or {}

    def project_save(self, timeout: float = 600.0) -> dict:
        """Save the open project back to its current file. DISRUPTIVE (writes to disk).
        Returns 409 if the project has no file yet - use project_save_as(path) instead."""
        return self._post("project/save", timeout=timeout) or {}

    def project_save_as(self, path: str, timeout: float = 600.0) -> dict:
        """Save the open project to a new .smc / .smcx file. DISRUPTIVE (writes to disk;
        Solibri's notion of the project's file path becomes `path`)."""
        return self._post("project/saveAs", query={"path": path}, timeout=timeout) or {}

    def project_set_industry(self, value: str) -> dict:
        """Set Project.Industry. value in {UNDEFINED, ARCHITECTURE, ENGINEERING, CONSULTING,
        CONSTRUCTION, OTHER_AEC}. DISRUPTIVE (mutates project metadata; persists on next save)."""
        return self._post("project/industry", query={"value": value}) or {}

    # ---- threed-camera-snapshots (wave 1) -----------------
    def threed_camera(self) -> dict:
        """Current 3D camera: {location, direction, up, projection?, fieldOfView?|viewToWorldScale?}."""
        return self._get("threed/camera") or {}

    def set_threed_camera(self, location: list[float], direction: list[float],
                          up: list[float], projection: str = "perspective",
                          field_of_view: float | None = None,
                          view_to_world_scale: float | None = None) -> dict:
        """Apply a 3D camera state. DISRUPTIVE (UI). projection = perspective|orthogonal;
        supply field_of_view (radians) for perspective or view_to_world_scale for orthogonal."""
        payload: dict = {
            "location": list(location),
            "direction": list(direction),
            "up": list(up),
            "projection": projection,
        }
        if field_of_view is not None:
            payload["fieldOfView"] = field_of_view
        if view_to_world_scale is not None:
            payload["viewToWorldScale"] = view_to_world_scale
        data = json.dumps(payload).encode("utf-8")
        return self._post("threed/camera", data=data) or {}

    def threed_snapshot(self, file: str, width: int | None = None,
                        height: int | None = None) -> dict:
        """Save a PNG of the current 3D viewport to `file`. DISRUPTIVE (touches disk).
        Optional width/height resize the captured image. -> {saved,path,width,height,size}."""
        q: dict = {"file": file}
        if width is not None:
            q["width"] = width
        if height is not None:
            q["height"] = height
        return self._post("threed/snapshot", query=q, timeout=60) or {}

    def threed_fit(self, target: str = "selection") -> dict:
        """Fit the 3D camera to the current selection or the whole model. DISRUPTIVE (UI).
        target = 'selection' | 'model'. -> {fit, target, components}."""
        return self._post("threed/fit", query={"target": target}) or {}

    # ---- model discipline + metadata ------------------------------------
    def disciplines(self) -> dict:
        """List the valid BuildingIndustryDomains values (for set_discipline)."""
        return self._get("disciplines") or {}

    def model_metadata(self, submodel_uuid: str) -> dict:
        """Read a submodel's metadata map (note: discipline is NOT stored here -
        Solibri tracks discipline via a separate internal mechanism)."""
        return self._get(f"models/{submodel_uuid}/metadata") or {}

    def set_discipline(self, submodel_uuid: str, value: str) -> dict:
        """Set a submodel's discipline via internal DisciplineTrackingUtil. DISRUPTIVE.
        `value` must be one of disciplines()['disciplines'] (e.g. VENTILATION, ARCHITECTURE)."""
        return self._post(f"models/{submodel_uuid}/discipline", query={"value": value}) or {}

    # ---- classification authoring (DISRUPTIVE: adds new classification to project) ---
    def create_classification_bulk(self, name: str, assignments: dict) -> dict:
        """Create a classification programmatically and bulk-assign GUIDs per class.
        `assignments` = {className: [guid, guid, ...], ...}. DISRUPTIVE.
        Uses ClassificationPlugin.createClassificationIfNeeded + setClassification +
        addClassificationToModel internally. Body can be multi-MB; timeout is generous."""
        body = json.dumps({"name": name, "assignments": assignments}).encode("utf-8")
        _, resp = self._request("POST", "classifications/createBulk", data=body,
                                 headers={"Content-Type": "application/json"}, timeout=600)
        return json.loads(resp.decode("utf-8")) if resp else {}

    # ---- ruleset authoring (DISRUPTIVE: attaches new ruleset to open project) -----
    def install_ruleset(self, ruleset_json: str | dict, timeout: float = 300.0) -> dict:
        """Install a ruleset into the open project from its JSON definition.

        ruleset_json: either a JSON string (same shape as Ruleset.toJson() output) or a
        dict that will be json.dumps'd. The plugin imports via RulesImporter +
        CheckingPlugin.addModel - no temp files, no UI prompts. Returns:
          {installed, uniqueId, name, topLevelChildren, rulesetsBefore, rulesetsAfter}
        DISRUPTIVE: mutates the open project's ruleset list.
        """
        if isinstance(ruleset_json, dict):
            body = json.dumps(ruleset_json).encode("utf-8")
        elif isinstance(ruleset_json, str):
            body = ruleset_json.encode("utf-8")
        else:
            raise ValueError("ruleset_json must be str or dict")
        # _post takes a body via the data param; preserve content-type as application/json
        _, resp = self._request("POST", "rulesets/install", data=body,
                                 headers={"Content-Type": "application/json"}, timeout=timeout)
        return json.loads(resp.decode("utf-8")) if resp else {}

    # ---- lifecycle (DISRUPTIVE: closes the whole application) -----------
    def shutdown(self, force: bool = False) -> dict:
        """Stop the running Solibri instance. force=False is graceful (SMC.shutdown);
        force=True is immediate (SMC.shutdownNow). The plugin flushes the response BEFORE
        the JVM exits, so a 200 is the success signal; subsequent requests will fail."""
        return self._post("shutdown", query={"force": "1"} if force else None, timeout=10) or {}
