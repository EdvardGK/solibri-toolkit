"""Parse a BCF 2.1 zip (as exported by Solibri) into structured issues.

BCF zip layout:
    bcf.version
    <topic-guid>/markup.bcf      (XML: Header files + Topic metadata + Viewpoints refs)
    <topic-guid>/viewpoint.bcfv  (XML: Components/Component IfcGuid -> affected elements)
    <topic-guid>/snapshot.png

Each Topic becomes one BcfIssue. Affected component IFC GUIDs are collected from the
referenced viewpoint files. markup.xsd uses no namespace, so plain tag lookups work.
"""

from __future__ import annotations

import io
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field


@dataclass
class BcfFile:
    filename: str
    ifc_project: str = ""


@dataclass
class BcfIssue:
    guid: str
    title: str
    topic_type: str = ""        # Error / Warning / Info / ...
    status: str = ""            # Open / Closed / ...
    index: str = ""
    description: str = ""
    author: str = ""
    created: str = ""
    modified: str = ""
    assigned_to: str = ""
    source_files: list[BcfFile] = field(default_factory=list)
    components: list[str] = field(default_factory=list)  # IFC GUIDs of affected elements

    @property
    def discipline_pair(self) -> str:
        """For clash topics: the pair of source model filenames, normalised + sorted."""
        names = sorted({f.filename for f in self.source_files})
        return " x ".join(names) if names else ""


def _text(el, tag, default=""):
    child = el.find(tag)
    return (child.text or "").strip() if child is not None and child.text else default


def parse_bcf(zip_bytes: bytes) -> list[BcfIssue]:
    issues: list[BcfIssue] = []
    # Solibri returns an empty body when the model has no coordination topics.
    if not zip_bytes or zip_bytes[:2] != b"PK":
        return issues
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        names = zf.namelist()
        markups = [n for n in names if n.endswith("markup.bcf")]
        for markup in markups:
            folder = markup.rsplit("/", 1)[0] if "/" in markup else ""
            try:
                root = ET.fromstring(zf.read(markup))
            except ET.ParseError:
                continue
            topic = root.find("Topic")
            if topic is None:
                continue
            issue = BcfIssue(
                guid=topic.get("Guid", folder),
                title=_text(topic, "Title"),
                topic_type=topic.get("TopicType", ""),
                status=topic.get("TopicStatus", ""),
                index=_text(topic, "Index"),
                description=_text(topic, "Description"),
                author=_text(topic, "CreationAuthor"),
                created=_text(topic, "CreationDate"),
                modified=_text(topic, "ModifiedDate"),
                assigned_to=_text(topic, "AssignedTo"),
            )
            header = root.find("Header")
            if header is not None:
                for f in header.findall("File"):
                    issue.source_files.append(
                        BcfFile(filename=_text(f, "Filename"), ifc_project=f.get("IfcProject", ""))
                    )
            # collect affected components from referenced viewpoints
            for vp in root.findall("./Viewpoints/Viewpoint"):
                pass  # handled below by scanning bcfv siblings
            issue.components = _components_for_folder(zf, folder)
            issues.append(issue)
    return issues


def _components_for_folder(zf: zipfile.ZipFile, folder: str) -> list[str]:
    guids: list[str] = []
    prefix = f"{folder}/" if folder else ""
    for name in zf.namelist():
        if name.startswith(prefix) and name.endswith(".bcfv"):
            try:
                vroot = ET.fromstring(zf.read(name))
            except ET.ParseError:
                continue
            for comp in vroot.iter("Component"):
                g = comp.get("IfcGuid")
                if g:
                    guids.append(g)
    return guids
