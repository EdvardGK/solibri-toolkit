"""Aggregate parsed BCF issues into an assessment summary - the 'assess' stage of the loop.

The summary is the signal a feedback loop reacts to: counts by severity/type/status,
clash discipline-pairs, and the most-affected components. Keep it plain dict/Counter so
it diffs cleanly between cycles and serialises to JSON.
"""

from __future__ import annotations

from collections import Counter
from typing import Iterable

from .bcf import BcfIssue


def _title_group(title: str) -> str:
    """Coarse grouping key: strip trailing _DelN / numeric suffixes so variants collapse."""
    import re
    t = re.sub(r"[_\s]*(Del)?\d+$", "", title).strip(" _-")
    return t or title


def assess_issues(issues: Iterable[BcfIssue]) -> dict:
    issues = list(issues)
    by_type = Counter(i.topic_type or "(none)" for i in issues)
    by_status = Counter(i.status or "(none)" for i in issues)
    by_group = Counter(_title_group(i.title) for i in issues)
    by_pair = Counter(i.discipline_pair for i in issues if i.discipline_pair)
    by_author = Counter(i.author or "(none)" for i in issues)

    component_hits = Counter()
    for i in issues:
        for g in i.components:
            component_hits[g] += 1

    return {
        "total": len(issues),
        "by_type": dict(by_type.most_common()),
        "by_status": dict(by_status.most_common()),
        "by_title_group": dict(by_group.most_common(25)),
        "by_discipline_pair": dict(by_pair.most_common(25)),
        "by_author": dict(by_author.most_common()),
        "total_component_refs": sum(component_hits.values()),
        "unique_components": len(component_hits),
        "top_components": dict(component_hits.most_common(15)),
    }


def format_assessment(summary: dict) -> str:
    """Human-readable assessment for the terminal."""
    lines = []
    lines.append(f"Issues: {summary['total']}  "
                 f"({summary['unique_components']} unique components, "
                 f"{summary['total_component_refs']} refs)")

    def block(title, d, limit=None):
        if not d:
            return
        lines.append(f"\n{title}")
        items = list(d.items())[:limit] if limit else list(d.items())
        width = max((len(str(k)) for k, _ in items), default=0)
        for k, v in items:
            lines.append(f"  {str(k).ljust(width)}  {v}")

    block("By type:", summary["by_type"])
    block("By status:", summary["by_status"])
    block("By discipline pair (clash):", summary["by_discipline_pair"], 15)
    block("By title group:", summary["by_title_group"], 15)
    return "\n".join(lines)


def diff_assessments(before: dict, after: dict) -> dict:
    """What changed between two cycles - the loop's feedback signal."""
    out = {"total_delta": after["total"] - before["total"], "by_type_delta": {}}
    types = set(before["by_type"]) | set(after["by_type"])
    for t in sorted(types):
        d = after["by_type"].get(t, 0) - before["by_type"].get(t, 0)
        if d:
            out["by_type_delta"][t] = d
    return out
