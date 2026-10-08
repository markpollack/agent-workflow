#!/usr/bin/env python3
"""Verify that each published Workflow POM exports its consumer security floors."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


JACKSON2 = {
    ("com.fasterxml.jackson.core", "jackson-databind"): "2.22.3",
}
JACKSON2_DATETIME = {
    ("com.fasterxml.jackson.datatype", "jackson-datatype-jsr310"): "2.22.3",
}
JACKSON3 = {
    ("tools.jackson.core", "jackson-core"): "3.2.3",
    ("tools.jackson.core", "jackson-databind"): "3.2.3",
}
NETWORKNT = {
    ("com.networknt", "json-schema-validator"): "3.0.7",
}

JACKSON_AND_NETWORKNT_MODULES = {
    "workflow-agents",
    "workflow-api",
    "workflow-batch",
    "workflow-core",
    "workflow-flows",
    "workflow-journal",
    "workflow-temporal",
}
PUBLISHED_MODULES = JACKSON_AND_NETWORKNT_MODULES | {"workflow-tools"}


def required_floors(artifact_id: str) -> dict[tuple[str, str], str]:
    floors = dict(JACKSON3)
    if artifact_id in JACKSON_AND_NETWORKNT_MODULES:
        floors.update(JACKSON2)
        floors.update(NETWORKNT)
    if artifact_id == "workflow-journal":
        floors.update(JACKSON2_DATETIME)
    return floors


def numeric_version(value: str) -> tuple[int, ...]:
    if not re.fullmatch(r"\d+(?:\.\d+)*", value):
        raise ValueError(f"non-numeric version: {value}")
    return tuple(int(part) for part in value.split("."))


def version_at_least(actual: str, required: str) -> bool:
    left = numeric_version(actual)
    right = numeric_version(required)
    width = max(len(left), len(right))
    return left + (0,) * (width - len(left)) >= right + (0,) * (width - len(right))


def direct_dependencies(pom: Path) -> dict[tuple[str, str], str | None]:
    root = ET.parse(pom).getroot()
    namespace = ""
    if root.tag.startswith("{"):
        namespace = root.tag.partition("}")[0] + "}"
    dependencies: dict[tuple[str, str], str | None] = {}
    for dependency in root.findall(f"{namespace}dependencies/{namespace}dependency"):
        group = dependency.findtext(f"{namespace}groupId")
        artifact = dependency.findtext(f"{namespace}artifactId")
        version = dependency.findtext(f"{namespace}version")
        if group and artifact:
            dependencies[(group.strip(), artifact.strip())] = version.strip() if version else None
    return dependencies


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-id", required=True)
    parser.add_argument("--pom", type=Path, required=True)
    args = parser.parse_args()

    if args.artifact_id not in PUBLISHED_MODULES:
        print(f"SKIP {args.artifact_id}: not a published Workflow JAR module")
        return 0
    if not args.pom.is_file():
        print(f"FAIL {args.artifact_id}: flattened POM does not exist: {args.pom}", file=sys.stderr)
        return 1

    dependencies = direct_dependencies(args.pom)
    failures = []
    for coordinate, required in sorted(required_floors(args.artifact_id).items()):
        actual = dependencies.get(coordinate)
        label = f"{coordinate[0]}:{coordinate[1]}"
        if actual is None:
            failures.append(f"missing direct dependency with explicit version: {label}")
            continue
        try:
            if not version_at_least(actual, required):
                failures.append(f"direct dependency below floor: {label}:{actual} < {required}")
        except ValueError as error:
            failures.append(f"direct dependency version is not resolved for {label}: {error}")

    if failures:
        print(f"FAIL {args.artifact_id}: published security floors are not exported", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1
    print(f"PASS {args.artifact_id}: flattened POM exports {len(required_floors(args.artifact_id))} security floors")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
