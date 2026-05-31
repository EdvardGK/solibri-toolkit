---
date: 2026-05-23
project: skiplum/toolkit/solibri + solibri-bim-tools
duration_hours: ~6
co_author: claude-opus-4-7
---

# Solibri superuser — REST toolkit + custom SDK plugin (our own endpoints)

## What

Turned a running Solibri into something an agent can drive end to end. Two artifacts:

1. **`solibri_loop`** Python toolkit at [`C:\workspace\toolkit\solibri\`](C:\workspace\toolkit\solibri\)
   — stdlib-only client/CLI over the built-in REST API (port 10876): status, about,
   models, assess (BCF), selection, camera, ruleset-reader, run-checking. Plus
   [`docs/SOLIBRI_API.md`](C:\workspace\toolkit\solibri\docs\SOLIBRI_API.md) (full verified
   route table) and [`docs/SUPERUSER.md`](C:\workspace\toolkit\solibri\docs\SUPERUSER.md).

2. **`SuperuserServer`** — a custom Solibri SDK plugin at
   [`C:\dev\solibri-bim-tools\java\src\main\java\no\skiplum\solibri\superuser\SuperuserServer.java`](C:\dev\solibri-bim-tools\java\src\main\java\no\skiplum\solibri\superuser\SuperuserServer.java)
   that runs its **own HTTP server inside the Solibri JVM** on
   `http://localhost:10999/skiplum/v1`, backed by the full SMC SDK. PROVEN loaded.

## Why

The built-in REST API (10876) is missing exactly the things that make Solibri useful
to an agent: it can't list/load rulesets (`GET /checking` is 405, POST-only), can't read
checking results back, and its component filter doesn't round-trip over a query string.
Goal stated by edkjo: "set up the necessary conditions to use you as a Solibri superuser."
The plugin is the answer — an in-JVM extension can expose anything the SDK can reach.

## Arc of the session

1. **Found the live instance + mapped the real API.** Solibri running, REST on 10876.
   Extracted the full route table from `solibri.jar` (Spring controllers) + live probing:
   ping/status/models/checking(POST)/bcfxml/selectionBasket/threed.camera/presentations/
   slides/project/shutdown. Confirmed the hard gaps: no ruleset list/load, no GET results,
   broken component filter.

2. **Reverse-engineered the file formats.** `.cset` and `.smc` are `ZIP { Model<n>.smc =
   Java-serialized graph (0xACED0005), <version> }`. Wrote a serialized-string extractor
   ([`cset.py`](C:\workspace\toolkit\solibri\solibri_loop\cset.py)) that reads ruleset
   names, `.cset` filenames and rule classes. Found the live G55 project's 4 rulesets
   (BIM Validation - Architectural, General Space Check, Intersections…, Skiplum_TMK).

3. **Built the Python toolkit + BCF assess pipeline.** `GET /bcfxml/two_one` returns
   `{content: base64(BCF zip)}`; parser decodes → topics + affected component GUIDs →
   assessment by severity/clash-pair/component. Fixed an empty-export crash (models with
   no coordination topics return a 0-byte body).

4. **The clash question → native answer.** "Ignore clashes between objects sharing a
   property value" (concretely `G55_Prosjektinfo.MMI=7xx` = existing elements). Right
   answer is the native **Clash Detection Matrix** (classify by value, disable the
   diagonal) — not a custom rule. Captured in
   [memory](C:\Users\edkjo\.claude\projects\c--workspace-toolkit-solibri\memory\solibri-clash-ignore-same-property.md).

5. **Proved the edges before committing.** `.cset` round-trip edit on a copy (same-length
   serialized-string patch → repackage → re-reads valid). Solibri's bundled JRE has
   **`javac.exe` + `jar.exe`** and the SDK jars — so no Maven/JDK install needed. And
   `jdk.httpserver@21` is in the JRE.

6. **Built the plugin.** Registered as a `View` (only `getUniqueId` + `initializePanel`
   are abstract — the SDK startup hook); HTTP server started from the constructor;
   SDK reads wrapped in `SwingUtilities.invokeAndWait` (model access is EDT-bound).
   Endpoints: /ping /model /rulesets[?json] /results /components(pset,prop,value,op) /check.
   Compiled with Solibri's own javac (`--add-modules jdk.httpserver`), packaged with
   `SMC-Rule: 1.0` manifest. One compile fix: `getProperty` is generic (`Property<T>`) → `var`.

7. **Deploy + restart + verify.** Harness HARD-BLOCKS the agent from copying a
   self-starting-server jar into `Program Files` and from launching the API server —
   edkjo did both (copied the jar; relaunched via `Solibri (API).bat`). Graceful close
   via `POST /shutdown?force=true`. On restart the plugin loaded in **8 seconds**:
   `GET /skiplum/v1/ping` → `{"answer":"pong","plugin":"skiplum-superuser","productVersion":"26.4.1.108"}`.
   `/results` returned `[]`; `/model` and `/rulesets` correctly report null until a project
   is open (graceful JSON errors).

## State / next

- Plugin live and proven; data endpoints need a project open to return real data
  (Solibri relaunched to a blank start).
- Permissions: read-only `python cli.py …` rules added to user `settings.json`.
- Open follow-ups: wire the 10999 endpoints into `cli.py`; add `/components` lambda-filter
  for the MMI 7xx query; investigate whether the SDK exposes ruleset *loading* (only
  read seen so far). Recipe in
  [memory: solibri-superuser-plugin](C:\Users\edkjo\.claude\projects\c--workspace-toolkit-solibri\memory\solibri-superuser-plugin.md).

## Fundamentals hit (not arbitrary — harness security boundaries)

- Agent cannot edit `settings.json`/permissions (self-modification guard).
- Agent cannot deploy a self-starting-HTTP-server jar into a system dir, nor launch the
  Solibri API server — both require the human. Everything else automated.
