---
date: 2026-06-02
project: toolkit/solibri
duration_hours: ~1
co_author: claude-opus-4-8
---

# MCP server over the superuser plugin — the agent-facing door

## What

Built `solibri_loop/mcp_server.py`: a FastMCP (mcp 1.27.1) server that exposes the Solibri
superuser plugin as a real MCP. It is a **thin, honest skin over `SuperuserClient`** — every
tool calls the exact method `cli.py su …` calls, no forked logic. CLI stays the source of
truth; the MCP is one more outlet onto the same client.

Decision context (this session): CLI is the better engine and does ~everything, *including*
camera + issues (snapshot-to-file + Read is a full vision loop). MCP is shipped anyway as a
**credibility/visibility token** — the box people check before taking a tool seriously — and
for **optionality** (non-Claude-Code clients: Desktop, web). Goal was "best MCP, not a
placeholder gimmick", so it's built to be genuinely usable by an agent, not a wrapper dump.

## Shape (verified end-to-end)

- **43 tools** — 22 read-only, 14 destructive, all with real descriptions (no blanks).
- **9 state-loss tools confirm-gated** (`open, close, check, set_rule_parameters,
  ito_calculate, project_save, project_save_as, launch, shutdown`) — mirrors the CLI `--yes`.
  Reversible UI ops (`select, fit, set_camera, snapshot, set_result_decision`,
  presentation/slide/bcf, classify-bulk, install-ruleset) run ungated per
  [[feedback-move-fast-when-reversible]].
- **`solibri_snapshot` returns an inline `Image`** and composes `select → fit → capture` — an
  agent frames GUIDs and actually sees the view. Protocol-native answer to the camera/issue
  "gap" — no `--out` file round-trip needed inside the MCP.
- **6 resources** (`solibri://model|project|results|rulesets|presentations|classifications`) —
  live state attachable without a tool call. This is what separates a real MCP from a tool dump.
- **2 prompts** (`triage_clashes`, `build_coordination_deck`) — guided multi-step workflows,
  Norwegian-language output, explicit "don't mutate without confirmation".
- **`solibri_ifc_takeoff`** — IFC-native ITO via IfcOpenShell, no Solibri needed (the
  out-of-band tool living inside the same door).
- Tool/result annotations: `readOnlyHint` / `destructiveHint` / `openWorldHint=True` (every
  call hits an external app).

## How it routes

Base URL from env `SOLIBRI_SU_BASE` (default `http://localhost:10999/skiplum/v1`). One shared
`SuperuserClient`. `solibri_results()` consolidates four backend methods (all / by-rule /
by-severity / full) into one filtered tool. Errors propagate as `SuperuserError` → clean MCP
tool errors carrying the "is Solibri running?" hint.

## Verification done

- Imports clean; `list_tools/resources/prompts` all populate; snapshot has no dict output
  schema (→ emits image content, not JSON).
- Full **stdio handshake with a real `ClientSession`**: initialize (instructions present) →
  tools/list (43) → resources/list (6) → prompts/list (2) → `call_tool(solibri_status)` →
  `get_prompt`. All green.
- `solibri_status` returned `reachable: true, productVersion 26.4.1.108` against the **live**
  running Solibri instance — so it drove the real plugin, not a mock.

## NOT done — pick up next time

- **REAL USE TEST (primary):** register in `~/.claude/settings.json` `mcpServers` and drive it
  from inside Claude Code / Desktop against a project that is actually **open with checking
  results**. So far only `solibri_status` was exercised live; the result/snapshot/slide tools
  need a loaded federation to prove out. Snapshot image round-trip especially (does the PNG
  come back as viewable inline content in the client, at sane size?).
- Registration snippet (not yet wired — left settings.json untouched):
  ```json
  "solibri": {
    "command": "python",
    "args": ["-m", "solibri_loop.mcp_server"],
    "cwd": "C:/workspace/toolkit/solibri",
    "env": { "SOLIBRI_SU_BASE": "http://localhost:10999/skiplum/v1" }
  }
  ```
- `docs/MCP.md` + `tests/test_mcp_server.py` (the stdio handshake is a ready-made test, just
  needs to move into a real test file).
- Consider: `solibri_snapshot` by-GUID currently selects+fits (mutates the basket). A true
  non-mutating frame needs the `/threed/snapshot/by-guids` plugin endpoint (in the build queue,
  not yet wired) so it doesn't disturb the user's selection.

## Files

- `solibri_loop/mcp_server.py` (new, ~430 lines)
- No changes to `cli.py` / `superuser.py` — the MCP imports them unchanged.
