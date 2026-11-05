#!/usr/bin/env python3
"""Remember a step's measurement against the bytes that produced it.

Six steps of the full gate compile something, which is why it takes seven
minutes; a tree that has not changed since the last real run does not need the
same six compiles again. This file is what makes that possible. `key` hashes the
inputs a step reads, `hit` answers whether those exact bytes were already proved
and what the step said when they were, `record` writes down what a run that did
happen printed.

A key is the one thing between this cache and a lie, so it covers the inputs and
nothing else: every file under the paths watched, in a fixed order, plus a stamp
naming the toolchain a step will build with. A build is reused only when the
bytes are the same bytes, and a step whose inputs are not watched cannot be
cached at all - the stamp is in the key for the same reason, because a build is
not reproducible across a different compiler.

What this deliberately does not decide is when reuse is allowed: check.sh runs
every one of these steps for real unless `--cached` was asked for, so the release
gate rebuilds everything and this only answers "has anything moved since the last
real run?". The store is `.gate_evidence.json` at the root, gitignored: it is
evidence about this worktree, not part of the tree, and a clone starts with none.

  python3 check_evidence.py key --stamp "..." --watch a b/    the inputs' key
  python3 check_evidence.py hit --name headroom --of KEY      ISO time and the
                                                              measurement, exit 3
                                                              when it is not this
                                                              key's evidence
  python3 check_evidence.py record --name headroom --of KEY --summary "..."
  python3 check_evidence.py list                              what is stored
"""
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STORE = ROOT / ".gate_evidence.json"

# What a build leaves beside its source. Hashing it would change a key the moment
# a step finished, so every reuse after the first would miss - and the in-place
# build path a deck compiles into is exactly that.
SKIP = {"build", "dist", ".gradle", ".git", "__pycache__", ".scratch", "node_modules"}


def named(path):
    """A path as a key sees it: relative to the root when it is inside it."""
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def digest(path):
    """One hash over every file a path holds, name and bytes, in a fixed order."""
    files = []
    if path.is_dir():
        for item in path.rglob("*"):
            if not item.is_file() or item.suffix == ".pyc":
                continue
            if any(part in SKIP for part in item.relative_to(path).parts):
                continue
            files.append(item)
    elif path.is_file():
        files.append(path)
    hasher = hashlib.sha256()
    for item in sorted(files, key=named):
        hasher.update(named(item).encode("utf-8") + b"\0")
        hasher.update(hashlib.sha256(item.read_bytes()).hexdigest().encode() + b"\n")
    return hasher.hexdigest()


def key(stamp, watches):
    parts = [stamp] + [digest(Path(watch)) for watch in watches]
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()[:16]


def load():
    """The store, or nothing: a cache that cannot be read is a cache that missed."""
    try:
        store = json.loads(STORE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return store if isinstance(store, dict) else {}


def parse(rest):
    """The four options, each one value, `--watch` taking every word after it."""
    found = {"stamp": "", "watches": [], "name": "", "of": "", "summary": ""}
    i = 0
    while i < len(rest):
        word = rest[i]
        if word == "--watch":
            i += 1
            while i < len(rest) and not rest[i].startswith("--"):
                found["watches"].append(rest[i])
                i += 1
            continue
        slot = {"--stamp": "stamp", "--summary": "summary",
                "--name": "name", "--of": "of"}.get(word)
        if slot:
            found[slot] = rest[i + 1] if i + 1 < len(rest) else ""
        i += 2 if slot else 1
    return found


def main(argv):
    if not argv or argv[0] in {"-h", "--help", "help"}:
        print(__doc__.strip())
        return 0 if argv else 2
    command = argv[0]
    options = parse(argv[1:])

    if command == "key":
        if not options["watches"]:
            print("nothing to key on: no file or folder was watched", file=sys.stderr)
            return 2
        for watch in options["watches"]:
            if not Path(watch).exists():
                print("watches %s, which this tree does not hold" % watch,
                      file=sys.stderr)
                return 2
        print(key(options["stamp"], options["watches"]))
        return 0

    if command == "hit":
        entry = load().get(options["name"])
        if not isinstance(entry, dict):
            return 3
        if entry.get("key") != options["of"] or not entry.get("summary"):
            return 3
        print("%s\t%s" % (entry.get("at", "?"), entry["summary"]))
        return 0

    if command == "record":
        if not options["summary"].strip():
            print("refuses to record %s with no summary: a reuse would say nothing"
                  % options["name"], file=sys.stderr)
            return 2
        store = load()
        store[options["name"]] = {
            "key": options["of"],
            "summary": options["summary"],
            "at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
        STORE.write_text(json.dumps(store, indent=2, sort_keys=True) + "\n",
                         encoding="utf-8")
        return 0

    if command == "list":
        store = load()
        if not store:
            print("nothing proved yet: %s holds no entry" % named(STORE))
            return 0
        for name in sorted(store):
            entry = store[name]
            print("%-12s %-18s %s  %s" % (name, entry.get("at", "?"),
                                          entry.get("key", "?"),
                                          entry.get("summary", "")))
        return 0

    print("is not a subcommand: key, hit, record or list", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
