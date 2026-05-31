---
date: 2026-05-29
project: skiplum/toolkit/solibri + solibri-bim-tools
duration_hours: ~5
co_author: claude-opus-4-7
---

# Solibri superuser plugin — v2→v5, from "it loads" to broad in-JVM control

## What

Matured the custom Solibri SDK plugin (`SuperuserServer`, an in-JVM HTTP server on
`http://localhost:10999/skiplum/v1`) from a proof-of-concept ping into a broad operating
layer that fills the gaps the built-in REST API (10876) can't. Source at
[`C:\dev\solibri-bim-tools\java\src\main\java\no\skiplum\solibri\superuser\SuperuserServer.java`](C:\dev\solibri-bim-tools\java\src\main\java\no\skiplum\solibri\superuser\SuperuserServer.java).

## Why

The built-in REST API can't list/load rulesets, read checking results, query components by
property, or open projects programmatically. A plugin in Solibri's JVM has full SMC SDK **and
internal-class** access, so it can expose all of that. Goal unchanged: operate Solibri through
an agent, end to end.

## Arc

- **v2** — moved `/results` + `/components` **off the Swing EDT** (`invokeAndWait` froze the UI
  on the 31-submodel KSV federation and timed out at 20s). Added `?limit=` + early-stop and a
  `503 no project open` guard. `/results` then returned in 3ms.
- **v3** — `/classifications` GET + `POST /load` via the internal
  `ClassificationPlugin.openClassifications(...)`. **PROVEN**: loaded `.classification` files
  into a running session — the public API has no ruleset/classification write path.
- **v4** — `/recents` (`RecentResource` — lists files with `provider=BIM360`, i.e. the ACC
  handshake), `/projects/close` + `/projects/openFile`, `/classifications/{name}` detail.
  **PROVEN**: programmatic open of the 305 MB KSV project in **~20 s**; `/rulesets?json=1`
  returns the full **403 KB `Skiplum_TMK` rule tree** (impossible via built-in API).
- **v5** — `GET /component?guid=` (count-in-model probe), `POST /select` (set selection basket
  from a GUID list), `/classifications/{name}` now returns table rows, `POST /classifications/{name}/save`.
- **Decoded the classification formula model**: `TotalFilter` ⊃ `ClassAndPropertyFilter`
  (component class + `PropertyReference` + `Operators` + target value); property-reference
  subtypes (`PropertySetPropertyReference`, `DoubleIdentificationPropertyReference` for
  "Global Bottom Elevation", …). `KSV_KvA` = 7 elevation buckets (Plan 1–6 + U02).

## Build / deploy mechanics (load-bearing)

- Compile with Solibri's **bundled** `jre/bin/javac.exe` (+ `jar.exe`) — no Maven/JDK install.
  Classpath now includes `solibri.jar` for the internal classes; `--add-modules jdk.httpserver`;
  manifest `SMC-Rule: 1.0`.
- Registered as a `View` (only `getUniqueId` + `initializePanel` are abstract); the HTTP server
  starts from the constructor at app startup.
- **Hard harness limits (human-in-loop):** the agent cannot copy the self-starting-server jar
  into `Program Files\lib`, nor launch Solibri with the API flags — edkjo does both (copy +
  `Solibri (API).bat`). Each plugin change needs a Solibri restart, which drops the open
  project — so `/projects/openFile` then reopens it programmatically.
- Built-in `10876` only runs when launched via the API bat; the plugin's `10999` runs regardless,
  but `/projects/openFile` proxies to `10876`, so that must be up.

## State / next

- Plugin live through v5; every endpoint proven against the live KSV model.
- Open follow-ups: construct-classification-**from-spec** (write rows, not just read/save);
  `/projects/openAcc` via BIM360 `projectId`+`itemId`; wire the `10999` endpoints into `cli.py`.
