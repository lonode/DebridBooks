#!/usr/bin/env python3
"""Summarize sanitized benchmark captures; author presence is NOT an accuracy score."""
import argparse
from collections import Counter
import json
from pathlib import Path


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--corpus", type=Path, required=True)
    ap.add_argument("--searches", type=Path, required=True)
    ap.add_argument("--metadata", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()
    corpus = json.loads(args.corpus.read_text())
    requests = [json.loads(line) for line in args.searches.read_text().splitlines()]
    searches = list({(row["id"], row["source"]): row for row in requests}.values())
    metadata = {row["releaseName"]: row for row in (json.loads(line) for line in args.metadata.read_text().splitlines())}
    cases = []
    for book in corpus["books"]:
        sources = {row["source"]: row for row in searches if row["id"] == book["id"]}
        releases = list(dict.fromkeys(item["releaseName"] for row in sources.values() for item in row.get("releases", [])))
        cases.append({
            "id": book["id"], "cohort": book["cohort"], "title": book["title"], "author": book["author"], "query": book["query"],
            "sources": {name: {"status": row["status"], "returned": len(row.get("releases", []))} for name, row in sources.items()},
            "uniqueReturnedReleases": len(releases),
            "releases": [{key: value for key, value in metadata.get(raw, {"releaseName": raw, "notAudited": True}).items() if key != "bookId"} for raw in releases],
        })
    raw = {item["releaseName"] for row in searches for item in row.get("releases", [])}
    audited = [metadata[name] for name in raw if name in metadata]
    report = {
        "captured": corpus["captured"], "method": corpus["method"],
        "limits": [
            "Page 1 only, up to 40 releases per configured tracker; no AudiobookBay or AllDebrid requests.",
            "A nonempty feed may contain unrelated books. It is not proof that the requested work is available.",
            "No-results queries are not parser passes; author presence is not a measured correctness percentage.",
            "Per-release native metadata audit has the app's 3-second budget; it does not qualify the whole Android UI or playback.",
        ],
        "summary": {
            "books": len(cases), "sourceRequests": len(requests), "sourceResponses": len(searches),
            "sourceStatuses": dict(Counter(row["status"] for row in searches)),
            "queriesWithAnyRelease": sum(bool(case["uniqueReturnedReleases"]) for case in cases),
            "queriesWithNoRelease": sum(not case["uniqueReturnedReleases"] for case in cases),
            "uniqueReleaseNames": len(raw), "auditedReleaseNames": len(audited),
            "displayedAuthors": sum(bool(row["author"]) for row in audited),
            "unresolvedAuthors": sum(not row["author"] for row in audited),
            "metadataDeadlineReached": sum(not row["completed"] for row in audited),
        },
        "cohorts": {name: {"books": sum(case["cohort"] == name for case in cases),
                           "queriesWithAnyRelease": sum(case["cohort"] == name and bool(case["uniqueReturnedReleases"]) for case in cases)}
                    for name in dict.fromkeys(case["cohort"] for case in cases)},
        "cases": cases,
    }
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report["summary"]))


if __name__ == "__main__":
    main()
