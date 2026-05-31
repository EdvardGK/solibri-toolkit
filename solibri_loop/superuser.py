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

    # ---- lifecycle (DISRUPTIVE: closes the whole application) -----------
    def shutdown(self, force: bool = False) -> dict:
        """Stop the running Solibri instance. force=False is graceful (SMC.shutdown);
        force=True is immediate (SMC.shutdownNow). The plugin flushes the response BEFORE
        the JVM exits, so a 200 is the success signal; subsequent requests will fail."""
        return self._post("shutdown", query={"force": "1"} if force else None, timeout=10) or {}
