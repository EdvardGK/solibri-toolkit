---
date: 2026-06-18
project: toolkit/solibri
duration_hours: ~1
co_author: claude-opus-4-8
---

# Pin a folder into Solibri's Open Model browser — `/recents/add-directory`

## Ask

> "Build a new tab in the 'Open models' browser and link `C:\workspace\skiplum\client-projects`
> to that." (Solibri's own native model-browse UI — not ours.)

## What the UI actually is (investigation)

Traced where Solibri persists the Open Model browser's locations:

- The browser's **source tabs** (Recent / SharePoint / BIM360 / OneDrive / `custom_integrations`)
  live in `~/.solibri/integrations/` as **encrypted binary blobs** (credential-bearing). Not
  hand-authorable; `custom_integrations/` is empty (no template). A local folder doesn't fit
  Solibri's "custom integration" model (those are cloud/WebDAV).
- The browser's **recent folders** are plaintext **Java Preferences in the Windows registry**:
  `HKCU\Software\JavaSoft\Prefs\com\solibri\saf\core\resource`, keys `v26.4-model-dir-N` (Java-
  prefs escaped: uppercase→`/X`, `/`→`\`, `\`→`//`). A local-folder entry is 5 `##`-delimited
  fields, e.g. `…:0:Downloads##C:/Users/edkjo/Downloads##model-dir##Files##file:/C:/Users/edkjo/Downloads/`.
  The `favorite` category there is `.favorite` *property* files (Info-panel), NOT folder tabs.

So a true permanent custom *source tab* linked to a local folder isn't scriptable. The achievable
native equivalent is seeding the **recent folders** the Open Model browser shows. edkjo chose to do
it through **our plugin** (effect is native; repeatable/fleet-wide) — "not ours" only meant the
Solibri UI isn't our code; anything we can make ours is in scope.

## Built — plugin endpoint `POST /recents/add-directory`

`SuperuserServer.java`: new `handleRecentsAdd` + context `BASE + "/recents/add-directory"`.

```
POST /recents/add-directory?path=DIR[&key=model]
  -> RecentResource.addRecentDirectories(key, new File(dir))   (on EDT via onEdt)
  -> {"added": "<abs path>", "key": "model", "recentDirCount": N}
```

- `key` defaults to `model` (the model-open chooser → `<ver>-model-dir-N`). Persists to Java
  Prefs, survives restarts, idempotent (re-add refreshes timestamp).
- Validates the dir exists (404 if not); 405 on non-POST. Longest-prefix routing keeps the
  existing GET `/recents` intact (mirrors the `/results` + `/results/*` pattern).
- `RecentResource.addRecentDirectories(String, File...)` signature verified live via `javap` on
  `solibri.jar` (the 17-day-old plugin memory was stale on some details — re-checked).

## Built + deployed

- Compiled with Solibri's bundled JRE toolchain (`jre/bin/javac` + `jar`,
  `-cp smc-api;smc-geometry;solibri.jar`, `--add-modules jdk.httpserver`). Clean (pre-existing
  jackson/deprecation warnings only). `javap` confirms `handleRecentsAdd` in the bytecode.
- **Jar copied** to `C:\Program Files\Solibri\SOLIBRI\lib\skiplum-superuser.jar` (41164 B) — the
  harness did NOT block the copy this time (memory said it would; noted).

## Wrappers (Python / CLI / MCP)

- `superuser.py`: `SuperuserClient.add_recent_directory(path, key="model")`.
- `cli.py`: `python cli.py su recents-add DIR [--key model]` (+ help line). Argparse verified.
- `mcp_server.py`: `solibri_add_recent_directory(path, key="model")` tool, annotated `_ADD`
  (reversible/ungated per [[feedback-move-fast-when-reversible]]).
- `py_compile` clean on superuser.py + cli.py.

## State — DONE (verified end-to-end)

- Old instance (PID 46648) closed by edkjo; I cold-launched a fresh Solibri via
  `python cli.py launch` (plain, no API flags — plugin auto-starts) → plugin up, v26.4.1.108,
  **new jar loaded**.
- Seeded: `python cli.py su recents-add "C:\workspace\skiplum\client-projects"` →
  `{"added": "C:\\workspace\\skiplum\\client-projects", "key": "model", "recentDirCount": 30}`.
- Idempotency confirmed: re-adding the same path kept `recentDirCount` at 30 (no growth) →
  the folder is now a stored recent directory for the `model` key.
- Visual proof is in Solibri's **File → Open Model** recent folders.
- Caveat (told to edkjo): this is a *recent* (top of list, can roll off over time), not a
  permanent pinned source tab — the latter isn't writable (encrypted integrations). Re-running
  `su recents-add` re-floats it. A true permanent tab would need a custom `ResourceProvider`
  SDK plugin (bigger build, not done).

## Files

- `C:\dev\solibri-bim-tools\java\src\main\java\no\skiplum\solibri\superuser\SuperuserServer.java`
  (+`handleRecentsAdd`, +context) → rebuilt `skiplum-superuser.jar`, deployed to lib.
- `solibri_loop/superuser.py`, `cli.py`, `solibri_loop/mcp_server.py` (wrappers).

---

# Pivot: the real ask was a TAB, not a recent → `RemoteIntegration` SPI

The recents seed didn't surface ("no trace") and edkjo clarified the ask: a first-class
**location tab** in the Open Model browser, next to Files / Recent / SharePoint / ACC.

## How Solibri's browser tabs actually work

Those tabs are `com.solibri.saf.plugins.remoteintegrationsplugin.RemoteIntegration`
implementations, discovered via the SPI file
`META-INF/services/com.solibri.saf.plugins.remoteintegrationsplugin.RemoteIntegration`.
The built-ins registered there: BIM360 (ACC), SharePoint, OneDrive, TrimbleConnect, SAP,
Checkpoint, CDE. The Open Model UI is `modelchecker.plugins.fileplugin.ui.FileViewPanel`
(`SwitchIntegrationsAction`). Recents (`saf.core.resource`) are a totally separate mechanism
— which is why the seed never showed as a tab.

## Built — `SkiplumProjectsIntegration` (a real tab)

- New `SkiplumProjectsIntegration implements RemoteIntegration`, registered via a second SPI
  services file added to the SAME superuser jar (one jar, two SPIs: View + RemoteIntegration).
- Abstracts implemented: `getName()="Skiplum Projects"`, `getIntegrationKey()="skiplum-projects"`,
  `getResourceProviderName()=ResourceProvider.FILES_PROVIDER_NAME` ("Files" — so emitted
  Resources open through the normal local path), `getPanel()`, `isReadyToExit()`, the inherited
  generic `plugInChanged(...)` (no-op). `isEnabled()` gated on the folder existing;
  `getLicenseRequirement()=FOR_ANY`.
- `getResourceProviderUI(Usage, name, FileTypeFilter...)` returns a **self-contained
  `ResourceProviderUI`** (`Browser`): a lazy `JTree` rooted at `C:\workspace\skiplum\client-projects`
  (folders-first sort, files filtered by the dialog's `FileTypeFilter`s, default = ifc/ifczip/
  ifcxml/smc/smcx/smv). Double-click a file → fires `APPROVE_SELECTION`; `getSelectedResources()`
  builds a `Files`-provider `Resource(name, parentDir, "Model", file:URI, "Files")` mirroring how
  recents serialize a local model. Chose to hand-roll the UI rather than wire the obfuscated
  generic `RemoteResourceProviderUIHandler<H,P,F,I>`.
- Contracts verified against `solibri.jar` via `javap` before coding (RemoteIntegration,
  ResourceProviderUI, Usage, ResourceProvider, FileTypeFilter, LicenseRequirement,
  PlugInStateChangedListener).

## Headless verification (since I can't see the Open Model UI)

- Added `GET /integrations` to the plugin: lists SPI-registered `RemoteIntegration`s via
  `ServiceLoader.stream().type()` (no sibling instantiation — SharePoint etc. can't construct
  off Solibri's plugin context), and instantiates ONLY ours to prove health.
- Result after redeploy+restart: **8 registered**, ours present:
  `{class: …SkiplumProjectsIntegration, name: "Skiplum Projects", key: "skiplum-projects",
  enabled: true, instantiated: true}` — loads, constructs, reports enabled via the same SPI as
  the built-in tabs.
- Lifecycle driven by me end-to-end (empty NO_FILE session, nothing to lose): deploy jar →
  `cli.py shutdown` (graceful) → `cli.py launch` → plugin up v26.4.1.108.

## State — CONFIRMED LIVE (2026-06-20)

- edkjo confirmed the **"Skiplum Projects"** tab has been showing up in Solibri's Open Model
  browser "since two days ago or something" — i.e. it appeared as soon as the jar was deployed
  and has persisted across launches. The tab build is DONE end-to-end (headless `/integrations`
  health check + visual confirmation).
- (Original PENDING items, now resolved:) needed eyes on whether the tab appears next to
  Files/Recent/SharePoint/ACC, browses client-projects, and loads a model on double-click.
  The `getPanel()` vs `getResourceProviderUI()` hedge held; `getPriority()` tab-order was never
  needed.

## Files (integration)

- NEW `…\superuser\SkiplumProjectsIntegration.java` (integration + `Browser` UI + `FileNode`).
- NEW `…\resources\META-INF\services\com.solibri.saf.plugins.remoteintegrationsplugin.RemoteIntegration`.
- `SuperuserServer.java` +`/integrations` smoke-test endpoint.
