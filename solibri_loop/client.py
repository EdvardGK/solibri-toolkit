"""Client over the Solibri REST automation API (stdlib only - no deps).

Every endpoint here was verified live against Solibri 26.4.1 (product "Office") on
port 10876. Full route table is in docs/SOLIBRI_API.md. Endpoints the API does NOT
provide (ruleset list/load/write, GET checking results) are absent on purpose.

Read-only methods are safe to call against a live session. Methods that mutate the
session or take minutes are marked DISRUPTIVE in their docstring.
"""

from __future__ import annotations

import base64
import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Optional

DEFAULT_BASE = "http://localhost:10876/solibri/v1"


class SolibriError(RuntimeError):
    """Raised when the Solibri API returns a non-2xx response or is unreachable."""


@dataclass
class Status:
    filename: str
    status: str          # SAVED / MODIFIED / ...
    busy: bool
    timestamp: Optional[str] = None
    date_time: Optional[str] = None

    @property
    def is_open(self) -> bool:
        return bool(self.filename)


@dataclass
class CheckingResult:
    name: str
    description: str = ""
    severity: str = ""        # CRITICAL / MODERATE / LOW / ...
    location: str = ""
    unique_key: str = ""


class SolibriClient:
    def __init__(self, base_url: str = DEFAULT_BASE, timeout: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    # ---- low-level -------------------------------------------------------
    def _request(self, method: str, path: str, *, data: bytes | None = None,
                 headers: dict | None = None, timeout: float | None = None) -> tuple[int, bytes, str]:
        url = f"{self.base_url}/{path.lstrip('/')}"
        req = urllib.request.Request(url, data=data, method=method)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as resp:
                return resp.status, resp.read(), resp.headers.get("Content-Type", "")
        except urllib.error.HTTPError as e:
            body = e.read()
            raise SolibriError(f"{method} {path} -> HTTP {e.code}: "
                               f"{body[:300].decode('utf-8', 'replace')}") from e
        except urllib.error.URLError as e:
            raise SolibriError(f"{method} {path} -> unreachable: {e.reason} "
                               f"(is Solibri running with the REST API on {self.base_url}?)") from e

    def _get_json(self, path: str, timeout: float | None = None) -> Any:
        _, body, _ = self._request("GET", path, timeout=timeout)
        return json.loads(body.decode("utf-8")) if body else None

    def _post_json(self, path: str, payload: Any = None, timeout: float | None = None) -> Any:
        data = json.dumps(payload).encode("utf-8") if payload is not None else b""
        _, body, ctype = self._request("POST", path, data=data,
                                        headers={"Content-Type": "application/json"}, timeout=timeout)
        if not body:
            return None
        return json.loads(body.decode("utf-8")) if "json" in ctype else body.decode("utf-8", "replace")

    # ---- health / state (read-only) -------------------------------------
    def ping(self) -> bool:
        try:
            return (self._get_json("ping", timeout=5) or {}).get("answer") == "pong"
        except SolibriError:
            return False

    def about(self) -> dict:
        """Product + version, e.g. {'version': '26.4.1.108', 'product': 'Office'}."""
        return self._get_json("about")

    def status(self) -> Status:
        d = self._get_json("status")
        return Status(filename=d.get("filename", ""), status=d.get("status", ""),
                      busy=bool(d.get("busy", False)), timestamp=d.get("timestamp"),
                      date_time=d.get("dateTime"))

    def models(self) -> list[dict]:
        """Loaded sub-models: [{name, metadata, uuid}, ...]."""
        return self._get_json("models") or []

    # ---- selection (read + write; write mutates UI selection) -----------
    def get_selection(self) -> list[str]:
        """IFC GUIDs currently in the selection basket."""
        return self._get_json("selectionBasket") or []

    def set_selection(self, ifc_guids: list[str]) -> None:
        """Replace the selection basket with these IFC GUIDs (drives the UI selection)."""
        self._post_json("selectionBasket", list(ifc_guids))

    def clear_selection(self) -> None:
        self.set_selection([])

    # ---- 3D view (read + write camera) ----------------------------------
    def get_camera(self) -> dict:
        """Current 3D camera: {projection, location, direction, upDirection, fieldOfView, ...}."""
        return self._get_json("threed/camera")

    def set_camera(self, camera_state: dict) -> None:
        """Move the 3D camera. Pass a dict shaped like get_camera() output. DISRUPTIVE (UI)."""
        self._post_json("threed/camera", camera_state)

    # ---- execute checking (DISRUPTIVE) ----------------------------------
    def run_checking(self, timeout: float = 1800.0) -> list[CheckingResult]:
        """POST /checking - runs the loaded ruleset(s). DISRUPTIVE: mutates the live session,
        may take minutes. Returns parsed CheckingResultList (name/severity/description/location)."""
        st = self.status()
        if st.busy:
            raise SolibriError("Solibri is busy; refusing to start checking.")
        _, body, ctype = self._request("POST", "checking", data=b"", timeout=timeout)
        if not body:
            return []
        payload = json.loads(body.decode("utf-8")) if "json" in ctype else {}
        rows = payload.get("results", payload) if isinstance(payload, dict) else payload
        out = []
        for r in (rows or []):
            out.append(CheckingResult(
                name=r.get("name", ""), description=r.get("description", ""),
                severity=r.get("severity", ""), location=r.get("location", ""),
                unique_key=r.get("uniqueKey", "")))
        return out

    # ---- assess (BCF) (read-only export) --------------------------------
    def export_bcf(self, version: str = "two_one", timeout: float = 120.0) -> bytes:
        """GET /bcfxml/{version} -> decoded BCF 2.1 zip bytes (presentation/coordination topics).
        The API wraps the zip as {'content': base64}; we unwrap to raw zip bytes."""
        _, body, _ = self._request("GET", f"bcfxml/{version}", timeout=timeout)
        text = body.decode("utf-8")
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            return body
        content = payload.get("content")
        if content is None:
            raise SolibriError("BCF export missing 'content' field")
        return base64.b64decode(content)

    # ---- coordination authoring (DISRUPTIVE: adds to model) -------------
    def create_presentation(self, name: str, prefix: str = "") -> str:
        """Create a presentation (issue container). Returns its id. DISRUPTIVE."""
        resp = self._post_json("presentations", {"name": name, "prefix": prefix})
        return (resp or {}).get("id", "") if isinstance(resp, dict) else str(resp)

    def add_slide(self, slide: dict) -> Any:
        """Add a slide/issue to a presentation. DISRUPTIVE.

        slide fields (see SlideRequest): presentationId, title, description, components
        (IFC GUIDs), status, stage, priority, responsibilities, dueDate, labels, type.
        """
        return self._post_json("slides", slide)

    # ---- project lifecycle (DISRUPTIVE) ---------------------------------
    def save_project(self) -> None:
        self._post_json("project/persist")

    def close_project(self) -> None:
        self._post_json("project/close")
