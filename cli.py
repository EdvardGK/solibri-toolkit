#!/usr/bin/env python3
"""solibri-loop CLI - operate Solibri through its REST APIs.

Two servers: the always-on Skiplum plugin (port 10999, preferred) and the legacy built-in
REST API (port 10876, only up when launched with --rest-api-server flags). The `su` and
lifecycle commands use the plugin; the bare commands below use the built-in 10876 API.

Lifecycle (start/stop a Solibri instance from scratch):
    python cli.py up                     # which servers are up + what's open
    python cli.py launch [--api] [--open PATH]   # start Solibri, wait for the plugin
    python cli.py shutdown [--force]     # stop Solibri (graceful via 10876, else taskkill)

Plugin (port 10999, works on any launch - no .bat needed):
    python cli.py su model               # open project: name, busy, submodels, rulesets
    python cli.py su results [--json]    # checking results (count/skipped/results)
    python cli.py su rulesets [--json]   # rulesets (--json = full rule tree)
    python cli.py su classifications     # list classifications
    python cli.py su classification NAME # one classification (classes, columns, rows)
    python cli.py su components --pset PS --prop P [--value V --op OP --limit N]
    python cli.py su component GUID...   # count each GUID in the model (dedup probe)
    python cli.py su recents             # recent files (incl. ACC/BIM360)
    python cli.py su select GUID...      # set selection basket [DISRUPTIVE]
    python cli.py su open PATH [--name N] # open .smc/.smcx (project) or .ifc (add) [DISRUPTIVE]
    python cli.py su check --yes         # RUN checking [DISRUPTIVE, slow]
    python cli.py su close               # close the open project [DISRUPTIVE]

Built-in 10876 (read-only, safe on a live session):
    python cli.py status | about | models | assess [--json] | bcf OUT | selection
    python cli.py camera | ruleset PATH.cset
Built-in 10876 (mutating - guarded):
    python cli.py select GUID... | select-clear | check --yes
"""

from __future__ import annotations

import argparse
import json
import sys

from solibri_loop import launch as launcher
from solibri_loop.assess import format_assessment
from solibri_loop.client import SolibriClient, SolibriError
from solibri_loop.cset import read_ruleset_info
from solibri_loop.ito import compute_takeoff, format_takeoff
from solibri_loop.launch import LaunchError
from solibri_loop.loop import pull_and_assess, run_cycle
from solibri_loop.superuser import DEFAULT_BASE as SU_BASE
from solibri_loop.superuser import SuperuserClient, SuperuserError


def cmd_status(c, args):
    if not c.ping():
        print("Solibri: NOT reachable on", c.base_url); return 1
    st = c.status()
    print("Solibri: up")
    print(f"  file   : {st.filename or '(none)'}")
    print(f"  status : {st.status}   busy={st.busy}")
    models = c.models()
    print(f"  models : {len(models)} loaded")
    for m in models:
        print(f"    - {m.get('name')}")
    return 0


def cmd_about(c, args):
    print(json.dumps(c.about(), indent=2)); return 0


def cmd_models(c, args):
    for m in c.models():
        print(f"{m.get('uuid')}  {m.get('name')}")
    return 0


def cmd_assess(c, args):
    res = pull_and_assess(c)
    print(format_assessment(res.summary))
    if args.json:
        print("\n" + json.dumps(res.summary, indent=2, ensure_ascii=False))
    return 0


def cmd_bcf(c, args):
    data = c.export_bcf()
    with open(args.out, "wb") as fh:
        fh.write(data)
    print(f"wrote {len(data)} bytes -> {args.out}"); return 0


def cmd_selection(c, args):
    guids = c.get_selection()
    print(f"{len(guids)} selected")
    for g in guids:
        print(" ", g)
    return 0


def cmd_select(c, args):
    c.set_selection(args.guids)
    print(f"selection set: {len(args.guids)} component(s)"); return 0


def cmd_select_clear(c, args):
    c.clear_selection(); print("selection cleared"); return 0


def cmd_camera(c, args):
    print(json.dumps(c.get_camera(), indent=2)); return 0


def cmd_ruleset(c, args):
    info = read_ruleset_info(args.path)
    print(f"source: {info['source']}")
    print(f"rulesets       : {info['rulesets']}")
    print(f"ruleset files  : {info['ruleset_files']}")
    print(f"rule classes   : {info['rule_classes']}")
    if info["rule_unique_ids"]:
        print(f"rule SOL ids   : {info['rule_unique_ids']}")
    if args.titles:
        print("titles sample  :")
        for t in info["titles_sample"]:
            print(f"    - {t}")
    return 0


def cmd_check(c, args):
    if not args.yes:
        print("Refusing to run checking without --yes (mutates the live session, may take "
              "minutes).", file=sys.stderr)
        return 2
    before = pull_and_assess(c, label="before")
    print(f"before: {before.summary['total']} issues. Running checking...")
    res = run_cycle(c, label="after", confirm=True, previous=before)
    print(f"checking done in {res.duration_s:.1f}s")
    if res.checking_response:
        print(f"checking returned {len(res.checking_response)} result rows")
    print()
    print(format_assessment(res.summary))
    if res.diff_vs_previous:
        print("\nDELTA vs before:", json.dumps(res.diff_vs_previous, ensure_ascii=False))
    return 0


def _dump(obj):
    print(json.dumps(obj, indent=2, ensure_ascii=False))


def cmd_ito(args):
    """Information Takeoff over an IFC (counts by class/storey, sum of length quantities)."""
    t = compute_takeoff(args.path, top_classes=args.top)
    print(format_takeoff(t))
    if args.json:
        print()
        _dump(t)
    return 0


# ---- lifecycle (plugin / process) ---------------------------------------
def cmd_up(args):
    _dump(launcher.status(base=args.su_base)); return 0


def cmd_launch(args):
    res = launcher.launch(exe=args.exe, api=args.api, open_file=args.open,
                          wait=not args.no_wait, base=args.su_base)
    _dump(res); return 0


def cmd_shutdown(args):
    _dump(launcher.shutdown(force=args.force, base=args.su_base)); return 0


# ---- superuser plugin (port 10999) --------------------------------------
def cmd_su(args):
    sc = SuperuserClient(args.su_base)
    s = args.su_cmd
    if s == "model":
        _dump(sc.model())
    elif s == "results":
        r = sc.results()
        if "error" in r:
            print("ERROR:", r["error"], file=sys.stderr); return 1
        print(f"{r.get('count', 0)} results, {r.get('skipped', 0)} skipped")
        if args.json:
            _dump(r)
    elif s == "rulesets":
        _dump(sc.rulesets(with_json=args.json))
    elif s == "classifications":
        _dump(sc.classifications())
    elif s == "classification":
        _dump(sc.classification(args.name))
    elif s == "components":
        _dump(sc.components(args.pset, args.prop, args.value, args.op, args.limit))
    elif s == "component":
        _dump(sc.component(args.guids))
    elif s == "recents":
        _dump(sc.recents())
    elif s == "select":
        _dump(sc.select(args.guids))
    elif s == "open":
        _dump(sc.open_file(args.path, args.name))
    elif s == "check":
        if not args.yes:
            print("Refusing to run checking without --yes (mutates the live session).",
                  file=sys.stderr); return 2
        _dump(sc.check())
    elif s == "close":
        _dump(sc.close_project())
    # checking-results-decisions
    elif s == "results-full":
        r = sc.results_full()
        if "error" in r:
            print("ERROR:", r["error"], file=sys.stderr); return 1
        print(f"{r.get('count', 0)} results, {r.get('skipped', 0)} skipped")
        if args.json:
            _dump(r)
    elif s == "results-by-rule":
        r = sc.results_by_rule(args.rule, full=args.full)
        if "error" in r:
            print("ERROR:", r["error"], file=sys.stderr); return 1
        print(f"{r.get('count', 0)}/{r.get('scanned', 0)} match rule {args.rule!r}, {r.get('skipped', 0)} skipped")
        if args.json:
            _dump(r)
    elif s == "results-by-severity":
        r = sc.results_by_severity(args.sev, full=args.full)
        if "error" in r:
            print("ERROR:", r["error"], file=sys.stderr); return 1
        print(f"{r.get('count', 0)}/{r.get('scanned', 0)} match severity {r.get('severity', args.sev)}")
        if args.json:
            _dump(r)
    elif s == "decision":
        r = sc.set_result_decision(args.unique_key, args.decision)
        if "error" in r:
            print("ERROR:", r["error"], file=sys.stderr); return 1
        print(f"{r.get('uniqueKey')}: {r.get('before')!r} -> {r.get('after')!r} (requested {r.get('requested')!r})")
    # checking-rules-parameters
    elif s == "rules":
        r = sc.rules()
        if "error" in r:
            print("ERROR:", r["error"], file=sys.stderr); return 1
        print(f"{r.get('count', 0)} rules")
        if args.json:
            _dump(r)
        else:
            for rule in r.get("rules", []):
                print(f"  [{rule.get('ruleset','')}] {rule.get('name','')}"
                      f"  ({len(rule.get('parameters', []))} params)")
    elif s == "rule-params":
        if args.set:
            if not args.yes:
                print("Refusing to patch rule parameters without --yes (mutates ruleset state).",
                      file=sys.stderr); return 2
            patches = {}
            for kv in args.set:
                if "=" not in kv:
                    print(f"--set expects ID=VAL, got: {kv}", file=sys.stderr); return 2
                k, v = kv.split("=", 1)
                patches[k.strip()] = v
            _dump(sc.set_rule_parameters(args.rule, patches))
        else:
            _dump(sc.rule_parameters(args.rule))
    elif s == "rule-components":
        r = sc.rule_components(args.rule, args.status, args.limit)
        if "error" in r:
            print("ERROR:", r["error"], file=sys.stderr); return 1
        print(f"{r.get('returned', 0)} of {r.get('total', 0)} {args.status} components for rule {args.rule!r}")
        if args.json:
            _dump(r)
        else:
            for g in r.get("guids", []):
                print(" ", g)
    # coordination-presentations-slides
    elif s == "presentations":
        r = sc.presentations()
        print(f"{r.get('count', 0)} presentation(s)")
        for p in r.get("presentations", []):
            print(f"  {p.get('uniqueId')}  [{p.get('slideCount', 0):>3} slides]  {p.get('name')}")
    elif s == "slides":
        r = sc.presentation_slides(args.presentation_id)
        print(f"{r.get('count', 0)} slide(s) in {r.get('presentationId', args.presentation_id)}")
        for sl in r.get("slides", []):
            print(f"  {sl.get('uniqueId')}  [{sl.get('status','')}] {sl.get('title','')}"
                  f"  ({len(sl.get('components', []))} components)")
        if args.json:
            _dump(r)
    elif s == "presentation-create":
        _dump(sc.create_presentation(args.name, args.prefix))
    elif s == "slide-add":
        slide = {"title": args.title}
        if args.description is not None: slide["description"] = args.description
        if args.status is not None: slide["status"] = args.status
        if args.stage is not None: slide["stage"] = args.stage
        if args.priority is not None: slide["priority"] = args.priority
        if args.slide_type is not None: slide["type"] = args.slide_type
        if args.due_date is not None: slide["dueDate"] = args.due_date
        if args.component: slide["components"] = args.component
        if args.responsibility: slide["responsibilities"] = args.responsibility
        if args.label: slide["labels"] = args.label
        _dump(sc.add_slide(args.presentation_id, slide))
    elif s == "bcfxml-export":
        r = sc.export_bcfxml(args.file, args.version, args.scope)
        if r.get("exported"):
            print(f"wrote {r.get('size', 0)} bytes -> {r.get('path')}  [{r.get('version')}/{r.get('scope')}]")
        else:
            _dump(r)
    elif s == "ruleset-install":
        with open(args.file, "r", encoding="utf-8") as fh:
            _dump(sc.install_ruleset(fh.read()))
    elif s == "disciplines":
        _dump(sc.disciplines())
    elif s == "model-metadata":
        _dump(sc.model_metadata(args.uuid))
    elif s == "set-discipline":
        _dump(sc.set_discipline(args.uuid, args.value))
    elif s == "classify-bulk":
        with open(args.file, "r", encoding="utf-8") as fh:
            payload = json.load(fh)
        _dump(sc.create_classification_bulk(payload["name"], payload["assignments"]))
    # ito-full-coverage
    elif s == "ito":
        r = sc.ito()
        print(f"{r.get('count', 0)} ITO definition(s)")
        for d in r.get("definitions", []):
            print(f"  {d.get('id')}  {d.get('name')}")
        _dump(r)
    elif s == "ito-calculate":
        if not args.yes:
            print("Refusing to calculate ITO without --yes (blocks the session until done).", file=sys.stderr); return 2
        r = sc.ito_calculate(args.id)
        _dump(r)
    elif s == "ito-results":
        r = sc.ito_results()
        cols = [c.get("name") for c in r.get("columns", [])]
        print(f"{r.get('count', 0)} row(s), {r.get('skipped', 0)} skipped, columns: {cols}")
        if args.json:
            _dump(r)
    # project-save-metadata
    elif s == "project-info":
        _dump(sc.project_info())
    elif s == "project-save":
        if not args.yes:
            print("Refusing to save without --yes (writes to disk).", file=sys.stderr); return 2
        _dump(sc.project_save())
    elif s == "project-save-as":
        if not args.yes:
            print("Refusing to save-as without --yes (writes a new .smc to disk).", file=sys.stderr); return 2
        _dump(sc.project_save_as(args.path))
    elif s == "project-industry":
        _dump(sc.project_set_industry(args.value))
    # threed-camera-snapshots
    elif s == "threed-camera":
        r = sc.threed_camera()
        loc = r.get("location"); d = r.get("direction"); u = r.get("up")
        if not r:
            print("no camera data")
        elif r.get("open") is False:
            print("no project open")
        else:
            print(f"projection: {r.get('projection', 'unknown')}")
            print(f"location  : {loc}")
            print(f"direction : {d}")
            print(f"up        : {u}")
            if "fieldOfView" in r:
                print(f"fov (rad) : {r['fieldOfView']}")
            if "viewToWorldScale" in r:
                print(f"v2wScale  : {r['viewToWorldScale']}")
        if args.json:
            _dump(r)
    elif s == "threed-camera-set":
        def _xyz(spec: str) -> list[float]:
            parts = [p.strip() for p in spec.split(",")]
            if len(parts) != 3:
                raise ValueError(f"expected 3 comma-separated numbers, got {spec!r}")
            return [float(p) for p in parts]
        _dump(sc.set_threed_camera(_xyz(args.location), _xyz(args.direction), _xyz(args.up),
                                    projection=args.projection,
                                    field_of_view=args.fov,
                                    view_to_world_scale=args.scale))
    elif s == "threed-snapshot":
        r = sc.threed_snapshot(args.file, width=args.width, height=args.height)
        if r.get("saved"):
            print(f"wrote {r.get('size')} bytes to {r.get('path')} ({r.get('width')}x{r.get('height')})")
        else:
            print("snapshot failed:", r.get("error", r))
            return 1
        _dump(r)
    elif s == "threed-fit":
        r = sc.threed_fit(args.target)
        if r.get("fit"):
            print(f"fitted to {r.get('target')} ({r.get('components')} component(s))")
        else:
            print("fit failed:", r.get("error", r))
            return 1
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(prog="solibri-loop", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base", default="http://localhost:10876/solibri/v1",
                   help="built-in 10876 REST base URL")
    p.add_argument("--su-base", default=SU_BASE, help="plugin (10999) base URL")
    sub = p.add_subparsers(dest="cmd", required=True)

    # lifecycle (plugin / process)
    sub.add_parser("up")
    lp = sub.add_parser("launch")
    lp.add_argument("--api", action="store_true", help="also start the built-in 10876 server")
    lp.add_argument("--open", help="open this .smc/.smcx/.ifc once the API is up")
    lp.add_argument("--no-wait", action="store_true", help="don't wait for the plugin to come up")
    lp.add_argument("--exe", help="explicit path to Solibri.exe")
    sd = sub.add_parser("shutdown"); sd.add_argument("--force", action="store_true")

    # IFC takeoff (no Solibri needed)
    ito = sub.add_parser("ito")
    ito.add_argument("path", help="path to an .ifc file")
    ito.add_argument("--top", type=int, default=20, help="show top N classes (default 20)")
    ito.add_argument("--json", action="store_true", help="also print structured JSON")

    # superuser plugin (port 10999)
    su = sub.add_parser("su"); su_sub = su.add_subparsers(dest="su_cmd", required=True)
    su_sub.add_parser("model")
    sr = su_sub.add_parser("results"); sr.add_argument("--json", action="store_true")
    srs = su_sub.add_parser("rulesets"); srs.add_argument("--json", action="store_true")
    su_sub.add_parser("classifications")
    scl = su_sub.add_parser("classification"); scl.add_argument("name")
    scp = su_sub.add_parser("components")
    scp.add_argument("--pset", required=True); scp.add_argument("--prop", required=True)
    scp.add_argument("--value", default=""); scp.add_argument("--op", default="equals",
                     choices=["equals", "prefix", "contains", "exists"])
    scp.add_argument("--limit", type=int, default=200)
    scc = su_sub.add_parser("component"); scc.add_argument("guids", nargs="+")
    su_sub.add_parser("recents")
    ssel = su_sub.add_parser("select"); ssel.add_argument("guids", nargs="+")
    sop = su_sub.add_parser("open"); sop.add_argument("path"); sop.add_argument("--name")
    sck = su_sub.add_parser("check"); sck.add_argument("--yes", action="store_true")
    su_sub.add_parser("close")
    # checking-results-decisions (wave 1)
    srf = su_sub.add_parser("results-full"); srf.add_argument("--json", action="store_true")
    srbr = su_sub.add_parser("results-by-rule"); srbr.add_argument("rule"); srbr.add_argument("--full", action="store_true"); srbr.add_argument("--json", action="store_true")
    srbs = su_sub.add_parser("results-by-severity"); srbs.add_argument("sev", choices=["CRITICAL", "MODERATE", "LOW", "PASSED", "critical", "moderate", "low", "passed"]); srbs.add_argument("--full", action="store_true"); srbs.add_argument("--json", action="store_true")
    sdec = su_sub.add_parser("decision"); sdec.add_argument("unique_key"); sdec.add_argument("decision", choices=["ACCEPTED", "REJECTED", "UNDEFINED", "accepted", "rejected", "undefined"])
    # checking-rules-parameters (wave 1)
    sru = su_sub.add_parser("rules"); sru.add_argument("--json", action="store_true")
    srp = su_sub.add_parser("rule-params"); srp.add_argument("rule"); srp.add_argument("--set", action="append", default=[], metavar="ID=VAL", help="patch a parameter (repeatable)"); srp.add_argument("--yes", action="store_true", help="required with --set (DISRUPTIVE)")
    src = su_sub.add_parser("rule-components"); src.add_argument("rule"); src.add_argument("--status", default="checked", choices=["passed","failed","checked"]); src.add_argument("--limit", type=int, default=5000); src.add_argument("--json", action="store_true")
    # coordination-presentations-slides (wave 1)
    su_sub.add_parser("presentations")
    spsl = su_sub.add_parser("slides"); spsl.add_argument("presentation_id"); spsl.add_argument("--json", action="store_true")
    spcr = su_sub.add_parser("presentation-create"); spcr.add_argument("name"); spcr.add_argument("--prefix", default=None)
    spsa = su_sub.add_parser("slide-add"); spsa.add_argument("presentation_id"); spsa.add_argument("--title", required=True); spsa.add_argument("--description", default=None); spsa.add_argument("--status", default=None); spsa.add_argument("--stage", default=None); spsa.add_argument("--priority", default=None); spsa.add_argument("--type", dest="slide_type", default=None); spsa.add_argument("--due", dest="due_date", default=None, help="ISO date YYYY-MM-DD"); spsa.add_argument("--component", action="append", default=[], help="IFC GUID (repeatable)"); spsa.add_argument("--responsibility", action="append", default=[]); spsa.add_argument("--label", action="append", default=[])
    sbcf = su_sub.add_parser("bcfxml-export"); sbcf.add_argument("file"); sbcf.add_argument("--version", default="V2_1", choices=["V1_0","V2_0","V2_1","V3_0"]); sbcf.add_argument("--scope", default="ALL", choices=["ALL","SELECTED","MARKED"])
    sri = su_sub.add_parser("ruleset-install"); sri.add_argument("file", help="path to a ruleset JSON file (Ruleset.toJson() shape)")
    su_sub.add_parser("disciplines")
    smd = su_sub.add_parser("model-metadata"); smd.add_argument("uuid")
    ssd = su_sub.add_parser("set-discipline"); ssd.add_argument("uuid"); ssd.add_argument("value", help="e.g. ARCHITECTURE, VENTILATION, STRUCTURAL_ELEMENTS")
    scb = su_sub.add_parser("classify-bulk"); scb.add_argument("file", help="JSON file: {name, assignments:{className:[guid,...]}}")
    # ito-full-coverage (wave 1)
    su_sub.add_parser("ito")
    sic = su_sub.add_parser("ito-calculate"); sic.add_argument("--id", help="ItoDefinition UUID; omit to calculate all"); sic.add_argument("--yes", action="store_true", help="required (blocks, mutates live ITO state)")
    sir = su_sub.add_parser("ito-results"); sir.add_argument("--json", action="store_true")
    # project-save-metadata (wave 1)
    su_sub.add_parser("project-info")
    sps = su_sub.add_parser("project-save"); sps.add_argument("--yes", action="store_true")
    spsa = su_sub.add_parser("project-save-as"); spsa.add_argument("path"); spsa.add_argument("--yes", action="store_true")
    spi = su_sub.add_parser("project-industry"); spi.add_argument("value", choices=["UNDEFINED","ARCHITECTURE","ENGINEERING","CONSULTING","CONSTRUCTION","OTHER_AEC"])
    # threed-camera-snapshots (wave 1)
    stcg = su_sub.add_parser("threed-camera"); stcg.add_argument("--json", action="store_true")
    stcs = su_sub.add_parser("threed-camera-set")
    stcs.add_argument("--location", required=True, help="x,y,z")
    stcs.add_argument("--direction", required=True, help="x,y,z")
    stcs.add_argument("--up", required=True, help="x,y,z")
    stcs.add_argument("--projection", default="perspective", choices=["perspective", "orthogonal"])
    stcs.add_argument("--fov", type=float, default=None, help="field of view in radians (perspective)")
    stcs.add_argument("--scale", type=float, default=None, help="view-to-world scale (orthogonal)")
    stsn = su_sub.add_parser("threed-snapshot")
    stsn.add_argument("file", help="output PNG path")
    stsn.add_argument("--width", type=int, default=None)
    stsn.add_argument("--height", type=int, default=None)
    stft = su_sub.add_parser("threed-fit")
    stft.add_argument("--target", default="selection", choices=["selection", "model"])

    # built-in 10876
    sub.add_parser("status")
    sub.add_parser("about")
    sub.add_parser("models")
    a = sub.add_parser("assess"); a.add_argument("--json", action="store_true")
    b = sub.add_parser("bcf"); b.add_argument("out")
    sub.add_parser("selection")
    s = sub.add_parser("select"); s.add_argument("guids", nargs="+")
    sub.add_parser("select-clear")
    sub.add_parser("camera")
    r = sub.add_parser("ruleset"); r.add_argument("path"); r.add_argument("--titles", action="store_true")
    ch = sub.add_parser("check"); ch.add_argument("--yes", action="store_true")

    args = p.parse_args(argv)

    # plugin / lifecycle commands don't use the 10876 client
    standalone = {"up": cmd_up, "launch": cmd_launch, "shutdown": cmd_shutdown, "su": cmd_su,
                   "ito": cmd_ito}
    try:
        if args.cmd in standalone:
            return standalone[args.cmd](args)
        c = SolibriClient(args.base)
        handlers = {"status": cmd_status, "about": cmd_about, "models": cmd_models,
                    "assess": cmd_assess, "bcf": cmd_bcf, "selection": cmd_selection,
                    "select": cmd_select, "select-clear": cmd_select_clear,
                    "camera": cmd_camera, "ruleset": cmd_ruleset, "check": cmd_check}
        return handlers[args.cmd](c, args)
    except (SolibriError, SuperuserError, LaunchError) as e:
        print("ERROR:", e, file=sys.stderr); return 1


if __name__ == "__main__":
    raise SystemExit(main())
