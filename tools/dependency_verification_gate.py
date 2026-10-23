#!/usr/bin/env python3
"""Read-only security audit of committed vs. Gradle-generated SHA-256 metadata.

The generated verification-metadata.xml is a *candidate*, never a trusted
source. This tool never writes or approves verification metadata.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree as ET

NS = "{https://schema.gradle.org/dependency-verification}"
HEX64 = re.compile(r"[a-fA-F0-9]{64}\Z")
ComponentKey = tuple[str, str, str]
ArtifactKey = tuple[str, str, str, str]


class InvalidMetadata(ValueError):
    pass


@dataclass(frozen=True)
class Metadata:
    entries: dict[ArtifactKey, frozenset[str]]
    # Everything other than <components> must remain invariant in bootstrap.
    configuration_xml: bytes


def load_metadata(path: Path) -> Metadata:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        raise InvalidMetadata(f"Cannot parse {path}: {exc}") from exc

    if root.tag != NS + "verification-metadata":
        raise InvalidMetadata(f"Unexpected dependency-verification namespace in {path}")

    config = root.find(NS + "configuration")
    components = root.find(NS + "components")
    if config is None or components is None:
        raise InvalidMetadata("Missing <configuration> or <components> section")
    metadata_enabled = config.find(NS + "verify-metadata")
    if metadata_enabled is None or (metadata_enabled.text or "").strip() != "true":
        raise InvalidMetadata("SECURITY: <verify-metadata> must remain true")

    # Require an explicit absence of broad trust bypasses, but permit future
    # signature policies without changing their semantics.
    if config.find(NS + "trusted-artifacts") is not None:
        raise InvalidMetadata("SECURITY: broad <trusted-artifacts> exceptions need separate review")

    found_components: set[ComponentKey] = set()
    found: dict[ArtifactKey, frozenset[str]] = {}
    for component in components.findall(NS + "component"):
        coords = tuple(component.get(k, "").strip() for k in ("group", "name", "version"))
        if not all(coords):
            raise InvalidMetadata("Artifact component without group/name/version")
        if coords in found_components:
            raise InvalidMetadata(f"Duplicate component {':'.join(coords)}")
        found_components.add(coords)
        for artifact in component.findall(NS + "artifact"):
            name = artifact.get("name", "").strip()
            if not name:
                raise InvalidMetadata(f"Unnamed artifact {':'.join(coords)}")
            key = (*coords, name)
            if key in found:
                raise InvalidMetadata(f"Duplicate artifact {':'.join(key)}")
            hashes: set[str] = set()
            for tag in ("sha256", "also-trust"):
                for node in artifact.findall(NS + tag):
                    value = node.get("value", "").strip().lower()
                    if not HEX64.fullmatch(value):
                        raise InvalidMetadata(f"Invalid SHA-256 digest for {':'.join(key)}")
                    hashes.add(value)
            if not hashes:
                raise InvalidMetadata(f"Missing SHA-256 for {':'.join(key)}")
            found[key] = frozenset(hashes)
    if not found:
        raise InvalidMetadata("No SHA-256 artifacts in verification metadata")
    return Metadata(found, ET.tostring(config, encoding="utf-8"))


def compare(expected: Metadata, generated: Metadata) -> list[tuple[str, ArtifactKey, str]]:
    findings: list[tuple[str, ArtifactKey, str]] = []
    if expected.configuration_xml != generated.configuration_xml:
        findings.append(("POLICY_CHANGE", ("-", "-", "-", "-"), "Gradle candidate altered security policy"))

    for key, actual in sorted(generated.entries.items()):
        old = expected.entries.get(key)
        if old is None:
            findings.append(("MISSING_CHECKSUM", key, f"candidate SHA-256: {', '.join(sorted(actual))}"))
        elif old != actual:
            findings.append(("CHECKSUM_MISMATCH", key, f"recorded: {', '.join(sorted(old))}; candidate: {', '.join(sorted(actual))}"))

    for key in sorted(expected.entries.keys() - generated.entries.keys()):
        findings.append(("POLICY_CHANGE", key, "Gradle candidate removed a tracked artifact"))
    return findings


def render(findings: list[tuple[str, ArtifactKey, str]], expected_count: int) -> str:
    lines = ["# Dependency Verification Gate", ""]
    if not findings:
        lines.extend([f"PASS: {expected_count} approved artifacts; no newly required or altered SHA-256 entries.", ""])
    else:
        kinds = {k for k, _, _ in findings}
        lines.extend([
            f"FAIL: {len(findings)} difference(s). No generated checksums were approved.",
            "",
            "- **MISSING_CHECKSUM**: dependency/metadata artifact is not represented in the committed lock file.",
            "- **CHECKSUM_MISMATCH**: candidate differs from the approved checksum; investigate repository, mirror or tampering.",
            "- **POLICY_CHANGE**: unexpected change to security policy or artifact inventory.",
            "",
        ])
        for kind, (group, name, version, artifact), details in findings[:100]:
            lines.append(f"- **{kind}** `{group}:{name}:{version}` — `{artifact}` — {details}")
        if len(findings) > 100:
            lines.append(f"- ... {len(findings)-100} additional differences omitted")
        lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--approved", type=Path, required=True, help="Approved checksum file (not a generated proposal)")
    parser.add_argument("--candidate", type=Path, help="Generated candidate metadata")
    parser.add_argument("--report", type=Path, help="Optional Markdown diagnostic report")
    args = parser.parse_args(argv)
    try:
        approved = load_metadata(args.approved)
        findings = compare(approved, load_metadata(args.candidate)) if args.candidate else []
        report = render(findings, len(approved.entries))
        code = 1 if findings else 0
    except InvalidMetadata as exc:
        report = f"# Dependency Verification Gate\n\nFAIL: INVALID_METADATA — {exc}\n"
        code = 2
    print(report)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report, encoding="utf-8")
    return code


if __name__ == "__main__":
    sys.exit(main())
