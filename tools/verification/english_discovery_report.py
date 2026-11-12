#!/usr/bin/env python3
"""Summarize an opt-in EnglishDiscoveryBenchmarkTest capture (public metadata only)."""
import argparse
from collections import Counter
from datetime import date, timedelta
import json
from pathlib import Path
import re
import statistics
import unicodedata


def words(text):
    normalized = "".join(char for char in unicodedata.normalize("NFKD", text.lower()) if not unicodedata.combining(char))
    return re.findall(r"[a-z0-9]+", normalized)


def candidate(book, result):
    """Conservative lexical candidate, never proof of recording identity/playability."""
    raw = set(words(result.get("releaseName") or ""))
    title = set(words(book["query"])) - {"the", "a", "an", "of", "and"}
    # Short generic names require the author's surname in the original release.
    author = any(words(name)[-1] in raw for name in book["authors"] if words(name))
    return bool(title) and title <= raw and (len(title) >= 3 or author)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    corpus = json.loads((args.directory / "corpus.json").read_text())
    rows = [json.loads(line) for line in (args.directory / "results.jsonl").read_text().splitlines()]
    cases = []
    sources = {}
    for row in rows:
        book = next(book for book in corpus["books"] if book["rank"] == row["rank"])
        matches = [result for result in row["results"] if candidate(book, result)]
        cases.append(dict(row, title=book["title"], releaseDate=book.get("releaseDate"), candidates=matches))
        for source in row["sources"]:
            name = source["source"]
            if name == "Book details":
                continue
            bucket = sources.setdefault(name, Counter())
            bucket[source["state"]] += 1
            bucket["returned"] += source["count"]
            if source["issue"]:
                bucket[source["issue"]] += 1

    def median(field):
        samples = [row[field] / 1000 for row in rows if row[field] is not None]
        return round(statistics.median(samples), 2) if samples else None

    summary = {
        "searched": len(rows), "total": len(corpus["books"]),
        "distinctQueries": len({row["query"] for row in rows}),
        "nonempty": sum(bool(row["results"]) for row in rows),
        "withLexicalCandidate": sum(bool(row["candidates"]) for row in cases),
        "candidateReleases": sum(len(row["candidates"]) for row in cases),
        "candidateReleasesWithCover": sum(bool(result["hasCover"]) for row in cases for result in row["candidates"]),
        "medianFirstResultsSeconds": median("firstResultsMs"),
        "medianSourcesDoneSeconds": median("sourcesDoneMs"),
        "medianCompletedSeconds": median("elapsedMs"),
        "runStates": dict(Counter(row["status"] for row in rows)),
        "sources": sources,
    }
    matched_times = [row["elapsedMs"] / 1000 for row in cases if row["candidates"]]
    summary["medianCandidateSearchCompletedSeconds"] = round(statistics.median(matched_times), 2) if matched_times else None
    cohorts = {}
    captured = date.fromisoformat(corpus["retrieved"])
    recent_months = {captured.strftime("%Y-%m"), (captured.replace(day=1) - timedelta(days=1)).strftime("%Y-%m")}
    for row in cases:
        month = (row.get("releaseDate") or "")[:7]
        cohort = month if month in recent_months else "older_or_other"
        group = cohorts.setdefault(cohort, {"searched": 0, "withCandidate": 0})
        group["searched"] += 1
        group["withCandidate"] += bool(row["candidates"])
    summary["releaseCohorts"] = cohorts
    times = sorted(row["elapsedMs"] / 1000 for row in rows)
    summary["p90CompletedSeconds"] = round(times[max(0, (9 * len(times) + 9) // 10 - 1)], 2) if times else None
    review_path = args.directory / "review.json"
    if review_path.exists():
        review = json.loads(review_path.read_text())
        accepted = review["acceptedReleaseNames"]
        for row in cases:
            names = accepted.get(str(row["rank"]), [])
            row["reviewedMatches"] = [result for result in row["results"] if result["releaseName"] in names]
        summary["reviewedQueriesWithMatch"] = sum(bool(row["reviewedMatches"]) for row in cases)
        summary["reviewedMatchingReleases"] = sum(len(row["reviewedMatches"]) for row in cases)
        summary["reviewedMatchingReleasesWithCover"] = sum(result["hasCover"] for row in cases for result in row["reviewedMatches"])
        summary["reviewedMatchesBySource"] = dict(Counter(result["source"] for row in cases for result in row["reviewedMatches"]))
    (args.directory / "report.json").write_text(json.dumps(dict(summary=summary, cases=cases), indent=2) + "\n")
    lines = ["# English audiobook discovery benchmark", "", corpus["description"],
             "", f"Source: {corpus['source']} (retrieved {corpus['retrieved']}).",
             "", corpus.get("configuration", "Only AllDebrid credentials; English filter; Knaben enabled; other source settings use app defaults."),
             "", corpus.get("runtime", "Actual Kotlin StandaloneApi including catalogue enrichment; host network/JVM, not Android UI timings."),
             "", "Page 1, title queries, ten-second pause between searches. Real source pacing and cooldowns are retained, never forced. No resolver calls or media downloads. Chart entries can include different editions of the same book, so repeated queries benefit from existing caches. Empty results during cooldown do not establish that a source lacks a book.",
             "", "Lexical candidates require all meaningful title words and, for short titles, an author surname in the raw release. They need human review: a different edition, collection or narrator can still match. A result count or cover does not prove availability, English narration, or playback. Cover measurements count metadata URLs, not successful image downloads. First-result timings include irrelevant results. Fast failures/cooldowns can make the overall completion median look good despite poor coverage; inspect source states and candidate-search timings together.",
             "", "```json", json.dumps(summary, indent=2), "```", "",
             "| Rank | Audiobook | Returned | Candidates | Reviewed matches | Candidate covers | First results (s) | Finished (s) |",
             "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for row in cases:
        first = round(row["firstResultsMs"] / 1000, 2) if row["firstResultsMs"] is not None else "—"
        reviewed = len(row["reviewedMatches"]) if "reviewedMatches" in row else "—"
        lines.append(f"| {row['rank']} | {row['title'].replace('|', '/')} | {len(row['results'])} | {len(row['candidates'])} | {reviewed} | {sum(r['hasCover'] for r in row['candidates'])} | {first} | {row['elapsedMs']/1000:.2f} |")
    phone = args.directory / "phone-checks.json"
    if phone.exists():
        lines.extend(["", "## ADB spot checks", "", "```json", phone.read_text().strip(), "```"])
    if review_path.exists():
        lines.extend(["", "## Manual relevance review", "", "```json", review_path.read_text().strip(), "```"])
    (args.directory / "report.md").write_text("\n".join(lines) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
