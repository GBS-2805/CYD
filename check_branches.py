#!/usr/bin/env python3
"""Say when another chat's branch holds work `main` does not have yet.

Every chat builds in its own worktree on its own `freebuff/<id>` branch, so a
commit made over there is invisible here: `main` stays green, the numbers in
these docs stay true for the tree that printed them, and two copies of the repo
quietly disagree about what the deck does. Nothing catches that, because a branch
is not a file and no compile reads it.

Counting commits is not enough to answer it. A branch can sit ahead and carry
nothing worth merging: rebased or cherry-picked work whose patches are already
upstream, or a folder that was renamed here so the same bytes are filed under a
new name. This asks git about the content as well as the commits — what the
branch changed since it forked, and whether those bytes already exist in the base
somewhere else — so a branch is only called pending when merging it would
actually add something.

  ./check_branches.py              exit 0 when nothing is pending, 1 otherwise
  ./check_branches.py --fetch      look at the remote first, in case a chat pushed
  ./check_branches.py --base freebuff-base   compare against something other than this branch
  ./check_branches.py --all        include the branch this worktree has checked out
  ./check_branches.py --commits-only   skip the content check: commits alone, no files read

It reads refs and objects only: no compile, no checkout, nothing written, and no
file under a worktree is opened — another chat's branch can be unreadable on disk
and still answer here.
"""
import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def git(*args, check=True):
    done = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True)
    if check and done.returncode != 0:
        # A check that cannot read the repo should say so, not print a traceback.
        print("error: git %s failed: %s" % (" ".join(args), done.stderr.strip()),
              file=sys.stderr)
        raise SystemExit(2)
    return done


def base_branch(named):
    """The branch this worktree has, so already-merged work is not called pending.

    origin/main reads well only while everything is pushed, and this history is
    not pushed as it lands: the two chats commit on their own branches, so work
    merged here days ago came back as
    "10 commits on top of origin/main, 11 paths it has no bytes for" — twice, and
    both times a person had to check by hand that the merge had happened. The
    branch checked out here is the tree the answer is about; the old names stay as
    fallbacks for a detached HEAD or a bare clone.
    """
    if named:
        candidates = [named]
    else:
        here = git("rev-parse", "--abbrev-ref", "HEAD", check=False).stdout.strip()
        candidates = [here if here and here != "HEAD" else "HEAD",
                      "origin/main", "main", "freebuff-base"]
    for name in candidates:
        found = git("rev-parse", "--verify", "--quiet", f"{name}^{{commit}}", check=False)
        if found.returncode == 0:
            return name, found.stdout.strip()
        if named:
            break
    tried = named or "this branch, origin/main, main, freebuff-base"
    print(f"error: no base to compare against (tried {tried})", file=sys.stderr)
    raise SystemExit(2)


def branches():
    """Every branch ref as (aliases, tip), minus the remotes' symbolic HEAD."""
    out = git(
        "for-each-ref", "--format=%(refname:short)%09%(objectname)", "refs/heads", "refs/remotes"
    ).stdout
    by_sha = {}
    for line in out.splitlines():
        name, _, sha = line.partition("\t")
        if not name or name.endswith("/HEAD"):
            continue
        # A local branch and its remote-tracking copy are one fact, not two, so
        # they report as one line: the local name first, because that is the one
        # a merge here would name and the one a worktree has checked out.
        by_sha.setdefault(sha, []).append(name)
    return list(by_sha.items())


def worktrees():
    """branch name -> the worktree that has it checked out."""
    out = git("worktree", "list", "--porcelain").stdout
    path, held = None, {}
    for line in out.splitlines():
        if line.startswith("worktree "):
            path = line[len("worktree "):]
        elif line.startswith("branch refs/heads/"):
            held[line[len("branch refs/heads/"):]] = path
        elif line == "":
            path = None
    return held


def base_blobs(base):
    """Every blob in the base tree, so an identical file can be found by content.

    `ls-tree` prints `<mode> <type> <object>\t<path>`; `--format` would be
    tidier but it only exists from git 2.36, and this has to run on whatever git
    a machine has.
    """
    out = git("-c", "core.quotePath=false", "ls-tree", "-r", base).stdout
    blobs = {}
    for line in out.splitlines():
        meta, _, path = line.partition("\t")
        parts = meta.split()
        if len(parts) >= 3 and path:
            blobs.setdefault(parts[2], []).append(path)
    return blobs


def patches_upstream(base, tip):
    """How many of the branch's own commits git can match to one already in base."""
    out = git("cherry", base, tip).stdout
    new = sum(1 for line in out.splitlines() if line.startswith("+"))
    matched = sum(1 for line in out.splitlines() if line.startswith("-"))
    return new, matched


def content(base, tip, blobs):
    """What the branch itself changed, split by whether the base already has it.

    Only the branch's own side is judged: the diff starts at the fork point, so
    files the base has edited since are not counted as the branch's work.
    """
    merge_base = git("merge-base", base, tip, check=False).stdout.strip()
    if not merge_base:
        return None
    out = git("diff", "--name-status", "-M", merge_base, tip).stdout
    same, new, deleted = [], [], []
    for line in out.splitlines():
        fields = line.split("\t")
        if len(fields) < 2:
            continue
        status = fields[0]
        # A rename reports both paths; the destination is the one the branch has.
        path = fields[2] if status[0] in "RC" and len(fields) > 2 else fields[1]
        if status[0] == "D":
            deleted.append(path)
            continue
        found = git("rev-parse", "--verify", "--quiet", f"{tip}:{path}", check=False)
        blob = found.stdout.strip()
        if blob and blob in blobs:
            same.append((path, blobs[blob][0]))
        else:
            new.append(path)
    return same, new, deleted


def missing_folders(base, paths):
    """Top-level folders the branch would bring back that the base no longer has."""
    tops = []
    for path in paths:
        top = path.split("/")[0]
        if top not in tops and top != path:
            tops.append(top)
    absent = []
    for top in tops:
        if git("rev-parse", "--verify", "--quiet", f"{base}:{top}", check=False).returncode != 0:
            absent.append(top)
    return absent


def main():
    parser = argparse.ArgumentParser(
        description="List branches holding work the base branch does not have."
    )
    parser.add_argument("--base", default=None, help="branch to compare against (default: the branch checked out here)")
    parser.add_argument("--fetch", action="store_true", help="fetch the remotes before comparing")
    parser.add_argument("--all", action="store_true", help="also report this worktree's own branch")
    parser.add_argument("--commits", type=int, default=5, metavar="N", help="commits to name per branch (default: 5)")
    parser.add_argument(
        "--commits-only",
        action="store_true",
        help="count commits without checking whether their content is already in the base",
    )
    args = parser.parse_args()

    if args.fetch:
        fetched = git("fetch", "--quiet", "--all", "--prune", check=False)
        if fetched.returncode != 0:
            print("warning: fetch failed, comparing the refs already here", file=sys.stderr)

    base, base_sha = base_branch(args.base)
    held = worktrees()
    mine = git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    blobs = {} if args.commits_only else base_blobs(base)

    pending, folded = [], []
    for sha, names in branches():
        if sha == base_sha or base in names or (not args.all and mine in names):
            continue
        count = git("rev-list", "--count", f"{base}..{sha}").stdout.strip()
        if not count.isdigit() or int(count) == 0:
            continue
        behind = int(git("rev-list", "--count", f"{sha}..{base}").stdout.strip() or 0)
        branch = {
            "names": ", ".join(names),
            "sha": sha,
            "ahead": int(count),
            "behind": behind,
            "ledger": held.get(names[0]),
        }
        if not args.commits_only:
            fresh, matched = patches_upstream(base, sha)
            played = content(base, sha, blobs)
            if fresh == 0 and matched > 0:
                why = (
                    f"an equivalent patch is already in {base}"
                    if matched == 1
                    else f"all {matched} of its commits are upstream as equivalent patches"
                )
                folded.append((branch, why))
                continue
            if played is not None and not played[1] and not played[2]:
                changed = len(played[0])
                why = (
                    f"its only changed file is the same bytes, already in {base}"
                    if changed == 1
                    else f"its {changed} changed files are the same bytes, already in {base}"
                )
                folded.append((branch, why))
                continue
            if played is not None:
                same, fresh_paths, deleted = played
                branch["same"], branch["new"], branch["deleted"] = same, fresh_paths, deleted
                branch["resurrect"] = missing_folders(base, fresh_paths)
        pending.append(branch)

    for branch in pending:
        line = f"{branch['names']}: {branch['ahead']:,} commit{'s' if branch['ahead'] != 1 else ''} on top of {base}"
        if branch["behind"]:
            line += f", {branch['behind']:,} behind it, so it needs a merge"
        if args.commits_only:
            line += "  (content not checked)"
        print(line)
        if branch["ledger"]:
            print(f"  checked out in {branch['ledger']}")
        if "new" in branch:
            same, new, deleted = branch["same"], branch["new"], branch["deleted"]
            bits = [f"{len(new)} path{'s' if len(new) != 1 else ''} {base} has no bytes for"]
            if same:
                bits.append(f"{len(same)} already there under another name")
            if deleted:
                bits.append(f"{len(deleted)} it deletes")
            print("  content: " + ", ".join(bits))
            for path in new[:3]:
                print(f"    adds {path}")
            if len(new) > 3:
                print(f"    … and {len(new) - 3} more")
            if same:
                path, twin = same[0]
                print(f"    {path} is already {twin}")
            if branch["resurrect"]:
                names = ", ".join(f"{top}/" for top in branch["resurrect"])
                print(f"    {base} has no {names}: merging this would bring it back")
        log = git("log", "--format=  %h %s", f"-n{args.commits}", f"{base}..{branch['sha']}").stdout
        print(log.rstrip("\n"))
        if branch["ahead"] > args.commits:
            print(f"  … and {branch['ahead'] - args.commits:,} more")
        print()

    if folded:
        print(f"Nothing to fold in from {len(folded)} branch{'es' if len(folded) != 1 else ''}:")
        for branch, why in folded:
            commits = f"{branch['ahead']:,} commit{'s' if branch['ahead'] != 1 else ''}"
            print(f"  {branch['names']}: {commits} ahead — {why}")
        print()

    if not pending:
        print(f"nothing is pending against {base} ({base_sha[:7]}): no branch adds work it does not have")
        return 0

    held_by = "branch holds" if len(pending) == 1 else "branches hold"
    print(f"{len(pending)} {held_by} work {base} does not have.")
    print("Fold one in with: git merge <branch>  (then re-run the gate on the merged sketch)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
