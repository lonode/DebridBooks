#!/usr/bin/env python3
"""Opt-in, resumable, read-only tracker benchmark. Never downloads/adds torrents.

Only public release names and declared language/author metadata are retained.
Credentials, torrent links, hashes, feed bodies and exception messages are discarded.
Not run by CI. Use --live to acknowledge real requests; ABB is deliberately excluded.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

SOURCES = {
    "C411": ("https://c411.org/api/torznab", "c411_key", "3030"),
    "Tr4ker": ("https://tr4ker.net/api/torznab/all", "tr4ker_key", "3030"),
    "YggReborn": ("https://api.yggreborn.org/api", "yggreborn_key", "7105"),
}


def read_keys(path):
    keys = {}
    for line in path.read_text().splitlines():
        match = re.match(r"\s*(?:export\s+)?([\w]+)\s*=\s*(.*?)\s*$", line)
        if match:
            keys[match[1].lower()] = match[2].strip("\"'")
    return keys


def release(title, attrs):
    if not isinstance(title, str) or not title.strip() or len(title) > 2000:
        return None
    # A title is public text, but reject unexpected URLs or credential-looking data.
    if re.search(r"https?://|magnet:|(?:api[_-]?key|passkey|token)=", title, re.I):
        return None
    row = {"releaseName": title}
    for key in ("language", "author", "bookauthor", "booktitle"):
        value = attrs.get(key)
        if isinstance(value, str) and len(value) <= 500 and not re.search(r"https?://|=", value):
            row[key] = value
    return row


def parse(body):
    if body.lstrip().startswith(b"{"):
        root = json.loads(body)
        if root.get("error"):
            raise ValueError()
        channel = root.get("rss", root).get("channel")
        if not isinstance(channel, dict):
            raise ValueError()
        items = channel.get("item", [])
        if isinstance(items, dict):
            items = [items]
        rows = []
        for item in items[:1000]:
            attrs = {}
            for prefix in ("torznab:attr", "newznab:attr"):
                fields = item.get(prefix, [])
                if isinstance(fields, dict):
                    fields = [fields]
                for field in fields:
                    field = field.get("@attributes", field)
                    attrs[field.get("name")] = field.get("value")
            title = item.get("title")
            if isinstance(title, dict):
                title = title.get("#text")
            rows.append(release(title, attrs))
    else:
        if re.search(br"<!DOCTYPE|<!ENTITY", body, re.I):
            raise ValueError()
        # The Android transport supplies UTF-8 text to XmlPullParser. Some feeds
        # declare a legacy encoding despite sending UTF-8 bytes; mirror Android.
        root = ET.fromstring(body.decode("utf-8"))
        if root.tag not in ("rss", "channel"):
            raise ValueError()
        channel = root.find("channel") if root.tag == "rss" else root
        if channel is None:
            raise ValueError()
        rows = []
        for item in list(channel.findall("item"))[:1000]:
            attrs = {x.get("name"): x.get("value") for x in item.iter() if x.tag.split("}")[-1] == "attr"}
            rows.append(release(item.findtext("title"), attrs))
    return [row for row in rows if row]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--live", action="store_true", required=True)
    ap.add_argument("--keys-file", type=Path, required=True)
    ap.add_argument("--corpus", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--interval", type=float, default=10)
    args = ap.parse_args()
    if args.interval < 10:
        ap.error("Minimum interval is 10 seconds per source; retries are disabled.")
    books = json.loads(args.corpus.read_text())["books"]
    keys = read_keys(args.keys_file)
    sources = {name: info for name, info in SOURCES.items() if keys.get(info[1])}
    if not sources:
        ap.error("No configured tracker keys were found.")
    completed = set()
    stopped = set()
    failures = {name: 0 for name in sources}
    if args.output.exists():
        for line in args.output.read_text().splitlines():
            row = json.loads(line)
            completed.add((row["id"], row["source"]))
            if row.get("status") in ("HTTP_401", "HTTP_403", "HTTP_429"):
                stopped.add(row["source"])
    def search(book, name, info):
        endpoint, variable, category = info
        params = dict(apikey=keys[variable], t="search", q=book["query"], cat=category, limit="40", offset="0")
        req = urllib.request.Request(endpoint + "?" + urllib.parse.urlencode(params), headers={
            "User-Agent": "DebridBooks-Android/1 (+https://github.com/lonode/DebridBooks-App)",
            "Accept": "application/rss+xml, application/xml, application/json"})
        row = {"id": book["id"], "source": name, "query": book["query"]}
        try:
            # Redirects can leak API keys to unexpected hosts: reject all redirects.
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, *unused):
                    return None
            with urllib.request.build_opener(NoRedirect).open(req, timeout=25) as response:
                body = response.read(2_000_001)
            if len(body) > 2_000_000:
                raise ValueError()
            row.update(status="ok", releases=parse(body))
        except urllib.error.HTTPError as error:
            row["status"] = "HTTP_" + str(error.code)
        except Exception as error:
            row["status"] = "TIMEOUT" if isinstance(error, TimeoutError) or isinstance(getattr(error, "reason", None), TimeoutError) else "UNAVAILABLE"
        return row
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=3) as pool, args.output.open("a") as output:
        for book in books:
            pending = [(name, info) for name, info in sources.items() if (book["id"], name) not in completed]
            if not pending:
                continue
            started = time.monotonic()
            futures = [(name, None if name in stopped else pool.submit(search, book, name, info)) for name, info in pending]
            counts = []
            for name, future in futures:
                row = future.result() if future else {"id": book["id"], "source": name, "query": book["query"], "status": "SKIPPED_CIRCUIT_OPEN"}
                if row["status"] == "ok":
                    failures[name] = 0
                else:
                    failures[name] += 1
                    if row["status"] in ("HTTP_401", "HTTP_403", "HTTP_429") or failures[name] >= 3:
                        stopped.add(name)
                output.write(json.dumps(row, ensure_ascii=False) + "\n")
                output.flush()
                counts.append(name + ":" + str(len(row.get("releases", []))) + ":" + row["status"])
            print(str(book["id"]) + "/200 " + " ".join(counts), flush=True)
            if len(stopped) == len(sources):
                print("All sources stopped; remaining queries require a later run.", flush=True)
                break
            time.sleep(max(0, args.interval - (time.monotonic() - started)))


if __name__ == "__main__":
    main()
