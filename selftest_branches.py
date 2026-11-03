#!/usr/bin/env python3
"""Exercise every verdict of check_branches.py in a throwaway repository.

This is the checker with no trustworthy passing path to lean on. On the real repo
it has printed "one branch, 10 commits past, 9 behind, 11 paths main has no bytes
for" — a paragraph a human then had to interpret, and twice the interpretation was
"that is a downgrade" or "that work is already here". Its quiet branches decide whether
work on another branch gets merged at all, so they are the ones worth pinning: a
branch whose changed file is the same bytes under a new name, a branch
whose patches are already upstream, a branch that would resurrect a folder this
tree renamed away.

Each case builds a real git repository in a temp directory — a base commit on
`main`, then one branch and one kind of divergence — and asks what the checker
says. Nothing outside the temp directory is read, no worktree is opened and the
real repository's refs are never touched.

  python3 selftest_branches.py    exit 0 when every branch still behaves

Git needs an identity to commit; the cases set their own, ignore the machine's
config, and never fetch.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "check_branches.py"

# git exports GIT_DIR, GIT_INDEX_FILE and friends to the hooks it runs, and those
# beat `-C <temp dir>`: inheriting them made this self-test commit into the real
# repository instead of its own when the pre-commit hook ran it. No case may see
# the caller's git environment, so every GIT_* variable is dropped, then the
# identity the cases need is set explicitly.
ENV = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
ENV.update(
    GIT_AUTHOR_NAME="Self Test",
    GIT_AUTHOR_EMAIL="selftest@example.invalid",
    GIT_COMMITTER_NAME="Self Test",
    GIT_COMMITTER_EMAIL="selftest@example.invalid",
    GIT_CONFIG_GLOBAL="/dev/null",
    GIT_CONFIG_NOSYSTEM="1",
)


# A commit made in the same second as its twin hashes to the same object, and a
# cherry-pick that lands as an identical commit is not "the same patch upstream" -
# it is the same commit, which the checker rightly calls nothing at all. Distinct,
# fixed dates keep every case the case it says it is.
_tick = 0


def stamp():
    global _tick
    _tick += 1
    when = "2026-01-01T00:00:%02d+0000" % _tick
    return dict(ENV, GIT_AUTHOR_DATE=when, GIT_COMMITTER_DATE=when)


def git(cwd, *args, env=None):
    done = subprocess.run(
        ["git", "-C", str(cwd), *args], capture_output=True, text=True, env=env or ENV
    )
    if done.returncode != 0:
        raise RuntimeError("git %s failed in %s: %s" % (" ".join(args), cwd, done.stderr))
    return done.stdout


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def commit(cwd, message):
    git(cwd, "add", "-A")
    git(cwd, "commit", "-q", "-m", message, env=stamp())


def repo(dest):
    """A base commit on main, and the checker itself, untracked."""
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CHECK, dest / "check_branches.py")
    git(dest, "init", "-q", "-b", "main")
    write(dest / "README.md", "the deck\n")
    commit(dest, "base")


def branch(dest, name, files):
    """A branch off main that adds the named files."""
    git(dest, "checkout", "-q", "-b", name)
    for path, text in files.items():
        write(dest / path, text)
    commit(dest, "%s: add %s" % (name, ", ".join(files)))
    git(dest, "checkout", "-q", "main")


def new_file(dest):
    branch(dest, "feature", {"new.txt": "fresh work\n"})


def same_bytes(dest):
    branch(dest, "feature", {"copy.txt": "the deck\n"})


def upstream_patch(dest):
    branch(dest, "feature", {"upstream.txt": "same patch\n"})
    git(dest, "cherry-pick", "feature", env=stamp())   # the same change, now on main


def resurrect(dest):
    branch(dest, "feature", {"old_deck/old_deck.ino": "// renamed away here\n"})


def behind(dest):
    branch(dest, "feature", {"new.txt": "fresh work\n"})
    write(dest / "LATER.md", "main moved on\n")
    commit(dest, "main moves on")


def merged_with_lagging_remote(dest):
    """Merged here, while origin/main still points at the old base."""
    branch(dest, "feature", {"new.txt": "fresh work\n"})
    base = git(dest, "rev-parse", "main").strip()
    git(dest, "merge", "-q", "--no-ff", "-m", "merge feature", "feature", env=stamp())
    git(dest, "update-ref", "refs/remotes/origin/main", base)


def pushed(dest):
    git(dest, "init", "-q", "--bare", str(dest.parent / ("remote-%s.git" % dest.name)))
    git(dest, "remote", "add", "origin", str(dest.parent / ("remote-%s.git" % dest.name)))
    branch(dest, "feature", {"new.txt": "fresh work\n"})
    git(dest, "push", "-q", "origin", "feature")


def unreachable_remote(dest):
    """A remote that cannot be fetched: the warning, not a silent nothing."""
    git(dest, "remote", "add", "origin", str(dest.parent / "gone.git"))


def broken_ref(dest):
    """A branch whose commit object cannot be read, so git fails loudly.

    A ref of forty zeros is not this: `for-each-ref` skips it, so the checker
    printed a quiet "nothing is pending" over a branch it could not read — the
    quiet pass this case exists to refuse. A real commit sha whose loose object
    is garbage is reached by `rev-list`, which fails.
    """
    branch(dest, "feature", {"new.txt": "fresh work\n"})
    tip = git(dest, "rev-parse", "feature").strip()
    loose = dest / ".git" / "objects" / tip[:2] / tip[2:]
    loose.chmod(0o644)
    loose.write_bytes(b"not a zlib stream\n")


CASES = [
    dict(
        name="a repo with only main is quiet",
        mutate=None,
        want_code=0,
        want=["nothing is pending against main"],
    ),
    dict(
        name="a branch already merged here is not pending, even when origin/main lags",
        mutate=merged_with_lagging_remote,
        want_code=0,
        want=["nothing is pending against main"],
        forbids=["needs a merge"],
    ),
    dict(
        name="a branch with a path main has no bytes for is pending",
        mutate=new_file,
        want_code=1,
        want=["1 branch holds work main does not have", "adds new.txt"],
        forbids=["Nothing to fold in"],
    ),
    dict(
        name="the same bytes under a new name are nothing to fold",
        mutate=same_bytes,
        want_code=0,
        want=["Nothing to fold in from 1 branch", "same bytes, already in main"],
    ),
    dict(
        name="a patch already upstream is nothing to fold",
        mutate=upstream_patch,
        want_code=0,
        want=["Nothing to fold in from 1 branch", "an equivalent patch is already in main"],
    ),
    dict(
        name="a branch that would resurrect a renamed-away folder says so",
        mutate=resurrect,
        want_code=1,
        want=["main has no old_deck/: merging this would bring it back"],
    ),
    dict(
        name="a branch main has moved past is named as needing a merge",
        mutate=behind,
        want_code=1,
        want=["1 behind it, so it needs a merge"],
    ),
    dict(
        name="--commits-only says the content was not checked",
        mutate=new_file,
        args=["--commits-only"],
        want_code=1,
        want=["(content not checked)"],
    ),
    dict(
        name="a pushed branch and its local copy are one line",
        mutate=pushed,
        want_code=1,
        want=["feature, origin/feature: 1 commit"],
    ),
    dict(
        name="a fetch that cannot reach the remote warns and compares what is here",
        mutate=unreachable_remote,
        args=["--fetch"],
        want_code=0,
        want=["warning: fetch failed, comparing the refs already here"],
    ),
    dict(
        name="a git command that fails is an error, not a quiet pass",
        mutate=broken_ref,
        want_code=2,
        want=["error: git rev-list"],
        forbids=["nothing is pending"],
    ),
    dict(
        name="a base that does not exist is an error, not a quiet pass",
        mutate=None,
        args=["--base", "no_such_base"],
        want_code=2,
        want=["no base to compare against"],
    ),
]


def main():
    failed = 0
    started = time.time()

    with tempfile.TemporaryDirectory() as tmp:
        for index, case in enumerate(CASES, 1):
            dest = Path(tmp) / f"case{index}"
            repo(dest)
            if case["mutate"]:
                case["mutate"](dest)

            done = subprocess.run(
                [sys.executable, str(dest / "check_branches.py"), *case.get("args", [])],
                capture_output=True,
                text=True,
                cwd=str(dest),
                env=ENV,
            )
            output = done.stdout + done.stderr

            problems = []
            if done.returncode != case["want_code"]:
                problems.append(f"exit {done.returncode}, wanted {case['want_code']}")
            missing = [text for text in case.get("want", []) if text not in output]
            if missing:
                problems.append("no " + ", ".join(repr(text) for text in missing))
            unexpected = [text for text in case.get("forbids", []) if text in output]
            if unexpected:
                problems.append("unasked-for " + ", ".join(map(repr, unexpected)))

            if problems:
                failed += 1
                print(f"FAIL {index}. {case['name']}: {'; '.join(problems)}")
                for line in output.strip().splitlines()[:14]:
                    print(f"     | {line}")
            else:
                print(f"ok   {index}. {case['name']}")

    elapsed = time.time() - started
    print()
    if failed:
        print(f"{failed} of {len(CASES)} cases failed", file=sys.stderr)
        return 1
    print(f"all {len(CASES)} branches of check_branches.py behave ({elapsed:.1f} s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
