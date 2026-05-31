"""solibri_loop - drive Solibri's native rule checking in an edit/execute/assess feedback loop.

Solibri's unique value (vs open-source IFC tooling) is geometry-aware checking:
clash/intersection control, spatially-aware access/clearance rules, and geometric ITO.
Data/property checks (IDS) do NOT need Solibri - run those with ifctester instead.

This package wraps the live Solibri REST API (default http://localhost:10876) so a
checking ruleset can be executed and its issues assessed programmatically, closing the
loop: edit ruleset -> execute checking -> assess issues -> refine.

Verified API surface (Solibri 26.4.1, port 10876, base /solibri/v1):
    GET  /ping                         -> {"answer": "pong"}
    GET  /status                       -> {filename, timestamp, status, busy}
    GET  /models                       -> [{name, metadata, uuid}, ...]
    POST /checking                     -> runs checking, returns CheckingResultList
    GET  /bcfxml/two_one               -> {"content": base64(BCF 2.1 zip)}  (presentation topics)
    GET  /information                  -> text/plain
    POST /info/{guid}                  -> show component info
    GET/POST /project, /project/close, /project/persist
    /models/{uuid}/components | /metadata/{k} | /update | /partialUpdate | /deleteComponents

NOT exposed by the REST API: listing/loading rulesets, reading checking results via GET.
Ruleset names/rules are read out of the .cset / .smc serialized blob (see cset.py).
"""

from .client import SolibriClient, SolibriError, CheckingResult, Status
from .superuser import SuperuserClient, SuperuserError
from .bcf import BcfIssue, parse_bcf
from .assess import assess_issues
from .cset import read_ruleset_info
from . import launch

__all__ = [
    "SolibriClient",
    "SolibriError",
    "SuperuserClient",
    "SuperuserError",
    "CheckingResult",
    "Status",
    "BcfIssue",
    "parse_bcf",
    "assess_issues",
    "read_ruleset_info",
    "launch",
]

__version__ = "0.1.0"
