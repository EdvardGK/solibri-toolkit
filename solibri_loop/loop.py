"""The feedback loop: snapshot -> (edit) -> execute -> assess -> diff.

A cycle runs the loaded ruleset, exports issues, and assesses them. An optional `edit_fn`
hook runs before checking so a caller can mutate the ruleset (e.g. patch a .cset tolerance,
swap in a new ruleset via the SDK) and measure the effect against the previous cycle.

Executing checking MUTATES the live Solibri session and can take minutes on large models,
so `run_cycle` requires `confirm=True` to actually POST /checking. Without it, the cycle is
read-only: it just exports + assesses the current state.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Callable, Optional

from .assess import assess_issues, diff_assessments
from .bcf import BcfIssue, parse_bcf
from .client import SolibriClient


@dataclass
class CycleResult:
    label: str
    ran_checking: bool
    duration_s: float
    issues: list[BcfIssue]
    summary: dict
    checking_response: object = None
    diff_vs_previous: Optional[dict] = None


def pull_and_assess(client: SolibriClient, label: str = "current") -> CycleResult:
    """Read-only: export current issues and assess them (no checking run)."""
    t0 = time.time()
    issues = parse_bcf(client.export_bcf())
    summary = assess_issues(issues)
    return CycleResult(label=label, ran_checking=False, duration_s=time.time() - t0,
                       issues=issues, summary=summary)


def run_cycle(client: SolibriClient,
              label: str = "cycle",
              edit_fn: Optional[Callable[[], None]] = None,
              confirm: bool = False,
              previous: Optional[CycleResult] = None) -> CycleResult:
    """One feedback-loop iteration.

    edit_fn : optional ruleset mutation applied before checking.
    confirm : must be True to actually run checking (it mutates the live session).
    previous: prior CycleResult to diff against (the feedback signal).
    """
    t0 = time.time()
    if edit_fn is not None:
        edit_fn()

    checking_response = None
    ran = False
    if confirm:
        st = client.status()
        if st.busy:
            raise RuntimeError("Solibri is busy; refusing to start checking.")
        checking_response = client.run_checking()
        ran = True

    issues = parse_bcf(client.export_bcf())
    summary = assess_issues(issues)
    diff = diff_assessments(previous.summary, summary) if previous else None

    return CycleResult(label=label, ran_checking=ran, duration_s=time.time() - t0,
                       issues=issues, summary=summary,
                       checking_response=checking_response, diff_vs_previous=diff)
