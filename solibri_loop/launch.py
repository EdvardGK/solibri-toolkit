"""Start, wait for, and stop a Solibri instance (Windows, stdlib only).

The Skiplum superuser plugin starts on EVERY Solibri launch (it's an SDK View whose HTTP
server starts from the constructor), and as of plugin v6 it has no dependency on the
built-in 10876 REST server. So a PLAIN launch of Solibri.exe is enough to get a fully
working API on http://localhost:10999/skiplum/v1 - no special flags or .bat required.

Pass api=True to ALSO bring up the legacy built-in 10876 server (only needed for the old
SolibriClient / endpoints not yet mirrored on 10999).

Headless: Solibri's plugin is a GUI View and only loads when the application UI starts, so
there is no supported headless mode that keeps the API alive. launch() starts the normal
app (a window appears). "Headless" is tracked as an open question - see docs/SUPERUSER.md.
"""

from __future__ import annotations

import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

from .superuser import DEFAULT_BASE, SuperuserClient

DEFAULT_EXE = r"C:\Program Files\Solibri\SOLIBRI\Solibri.exe"
BUILTIN_PING = "http://localhost:10876/solibri/v1/ping"
BUILTIN_SHUTDOWN = "http://localhost:10876/solibri/v1/shutdown?force=true"

# Built-in REST server flags (mirrors the "Solibri (API).bat" launcher).
API_FLAGS = [
    "--rest-api-server-port=10876",
    "--rest-api-server-http",
    "--rest-api-server-local-content",
]

# Detach the child so Solibri keeps running after this Python process exits.
_DETACHED = 0x00000008  # DETACHED_PROCESS
_NEW_GROUP = 0x00000200  # CREATE_NEW_PROCESS_GROUP


class LaunchError(RuntimeError):
    pass


def solibri_exe(explicit: Optional[str] = None) -> str:
    """Locate Solibri.exe. Tries an explicit path, the default install, then a glob over
    Program Files (handles version-suffixed install dirs)."""
    if explicit:
        if Path(explicit).is_file():
            return explicit
        raise LaunchError(f"Solibri.exe not found at {explicit}")
    if Path(DEFAULT_EXE).is_file():
        return DEFAULT_EXE
    for root in (r"C:\Program Files\Solibri", r"C:\Program Files (x86)\Solibri"):
        hits = sorted(Path(root).glob("*/Solibri.exe")) if Path(root).is_dir() else []
        if hits:
            return str(hits[-1])
    raise LaunchError("Could not locate Solibri.exe (pass an explicit path)")


def is_up(base: str = DEFAULT_BASE) -> bool:
    """Is the superuser plugin (10999) responding?"""
    return SuperuserClient(base).ping()


def is_builtin_up() -> bool:
    """Is the built-in 10876 REST server responding?"""
    try:
        with urllib.request.urlopen(BUILTIN_PING, timeout=4):
            return True
    except (urllib.error.URLError, OSError):
        return False


def wait_up(base: str = DEFAULT_BASE, timeout: float = 120.0, interval: float = 2.0) -> bool:
    """Poll the plugin until it answers /ping or `timeout` elapses."""
    client = SuperuserClient(base)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if client.ping():
            return True
        time.sleep(interval)
    return False


def launch(*, exe: Optional[str] = None, api: bool = False, open_file: Optional[str] = None,
           wait: bool = True, timeout: float = 180.0, base: str = DEFAULT_BASE) -> dict:
    """Start Solibri from scratch.

    api       - also pass the built-in 10876 REST flags (default False: plugin-only).
    open_file - after the API is up, open this .smc/.smcx/.ifc via the plugin.
    wait      - block until the plugin answers /ping (up to `timeout`).

    Refuses to launch if the plugin is already up (a second instance would fail to bind
    port 10999). DISRUPTIVE: spawns the Solibri GUI application.
    """
    if is_up(base):
        raise LaunchError("plugin already up on 10999 - an instance is already running "
                          "(refusing to start a second one)")
    exe_path = solibri_exe(exe)
    cmd = [exe_path] + (API_FLAGS if api else [])
    subprocess.Popen(cmd, creationflags=_DETACHED | _NEW_GROUP, close_fds=True,
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL)
    result = {"launched": exe_path, "api": api, "waited": wait}
    if not wait:
        return result
    if not wait_up(base, timeout=timeout):
        raise LaunchError(f"launched {exe_path} but plugin did not come up within {timeout:.0f}s")
    result["up"] = True
    if open_file:
        result["open"] = SuperuserClient(base).open_file(open_file)
    return result


def wait_down(base: str = DEFAULT_BASE, timeout: float = 30.0, interval: float = 1.0) -> bool:
    """Poll the plugin until /ping STOPS answering (process exiting), bounded by timeout."""
    client = SuperuserClient(base)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not client.ping():
            return True
        time.sleep(interval)
    return False


def shutdown(*, force: bool = False, base: str = DEFAULT_BASE, wait: bool = True,
             timeout: float = 30.0) -> dict:
    """Stop the running Solibri. DISRUPTIVE: closes the application (unsaved work is lost).

    Order of preference:
      1. Plugin (10999) POST /shutdown - SMC.shutdown / shutdownNow (self-sufficient).
      2. Built-in (10876) POST /shutdown?force=true - legacy graceful path.
      3. taskkill /IM Solibri.exe - last resort.

    `force` selects SMC.shutdownNow on the plugin path, taskkill /F on the fallback path.
    `wait` polls until the plugin stops answering (confirms the process exited).
    """
    out: dict = {}
    # 1. plugin (preferred). The plugin responds 200 then schedules SMC.shutdown(), so the
    # JVM commonly dies while we are still reading the response - WinError 10054 is EXPECTED
    # in that race and is not a failure. Truth is whether the process actually exited.
    if is_up(base):
        plugin_reset = None
        try:
            out["plugin"] = SuperuserClient(base).shutdown(force=force)
        except Exception as e:
            plugin_reset = str(e)
        if wait:
            confirmed = wait_down(base, timeout=timeout)
            out["confirmed_down"] = confirmed
            if confirmed:
                out["method"] = "plugin-10999"
                if plugin_reset:
                    out["plugin_reset_during_response"] = plugin_reset
                return out
        else:
            out["method"] = "plugin-10999"
            return out
        if plugin_reset:
            out["plugin_error"] = plugin_reset
    # 2. legacy 10876
    if is_builtin_up():
        try:
            req = urllib.request.Request(BUILTIN_SHUTDOWN, method="POST", data=b"")
            with urllib.request.urlopen(req, timeout=10):
                pass
            out["method"] = "builtin-10876"
            if wait:
                out["confirmed_down"] = wait_down(base, timeout=timeout)
            return out
        except (urllib.error.URLError, OSError) as e:
            out["builtin_error"] = str(e)
    # 3. taskkill
    args = ["taskkill", "/IM", "Solibri.exe"] + (["/F"] if force else [])
    proc = subprocess.run(args, capture_output=True, text=True)
    out["method"] = "taskkill"
    out["returncode"] = proc.returncode
    out["output"] = (proc.stdout + proc.stderr).strip()
    if wait:
        out["confirmed_down"] = wait_down(base, timeout=timeout)
    return out


def status(base: str = DEFAULT_BASE) -> dict:
    """Quick lifecycle snapshot: which servers are up and what's open."""
    client = SuperuserClient(base)
    up = client.ping()
    snap = {"plugin_10999": up, "builtin_10876": is_builtin_up()}
    if up:
        snap["productVersion"] = client.product_version()
        m = client.model()
        snap["project"] = m.get("name") if m.get("open", True) is not False else None
        snap["subModels"] = len(m.get("subModels", []))
    return snap
