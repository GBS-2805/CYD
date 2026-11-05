#!/usr/bin/env python3
"""Fail when a file claims a screen count this tree does not draw.

Files went on saying a count the app had grown past: the design page's lede, its
README, the gate's own header, the capture script, a Gradle comment, a cost table
and the screen baseline page. Each was true the week it was written, none of them
moved when new screens arrived, and a reader found them instead of a check.

What counts as a claim: a number word or digits immediately before *screens*,
*frames* or *captures* — "the N screens", "N frames", "an N-frame strip",
"N captures". The count this tree draws is the number of frames git holds under
`artifacts/renders/`, so the answer comes from the committed pictures rather than
from a number written down here. One or two screens is a part of the app rather
than the app — "scrolling from one screen to another" is not a count of it, and a
cost table's "one capture" is one command — so the scan starts at three.

A file may still describe a past state: a claim is allowed when the paragraph
around it also spells the count this tree draws. That is what makes it a history
("the strip came to show a week-old seven frames while the app drew nine") rather
than a claim, and it reads as one to a person too. The selftests are skipped, because a
fixture's whole job is to say the wrong number out loud.

Only tracked files are read: a clone is what these docs describe, and the gate's
`tracked` step already refuses a source file the index never got.

  ./check_screens.py       exit 0 when every count is this tree's, or names it
"""
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RENDERS = ROOT / "artifacts" / "renders"

NUMBERS = (
    "one two three four five six seven eight nine ten eleven twelve "
    "thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty"
).split()

# A count of the app's screens as prose writes it: a word or digits, then the noun.
CLAIM = re.compile(
    r"\b(%s|\d{1,2})(?:-|\s+)(screens?|frames?|captures?)\b" % "|".join(NUMBERS),
    re.IGNORECASE,
)

# Where prose and comments live. A file with no such suffix cannot carry a claim.
PROSE = {".md", ".html", ".sh", ".py", ".gradle", ".json", ".ino", ".java", ".xml", ".txt"}

# The smallest number that can claim to be the whole app. Below it the words are
# parts: "one screen to another", "two screens behind it", a table's "one capture".
FLOOR = 3


def shown(path):
    try:
        return Path(path).resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def git(*args):
    """This tree's git answer, or None when git cannot be run or refuses.

    A caller's GIT_* environment is dropped rather than trusted: a hook exports
    GIT_INDEX_FILE for its own commit, and the question here is about this tree.
    """
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("GIT_")}
    try:
        done = subprocess.run(["git", "-C", str(ROOT)] + list(args),
                              capture_output=True, text=True, env=env)
    except OSError:
        return None
    return done.stdout if done.returncode == 0 else None


def frames():
    """The frames git holds: the count the app draws, from the pictures themselves."""
    listed = git("ls-files", "--", "artifacts/renders") or ""
    return [line for line in listed.splitlines() if line.endswith(".png")]


def paragraphs(text):
    """(first line number, body) for every paragraph, a blank line separating them."""
    offset = 0
    begin = first = None
    lines = []
    for number, line in enumerate(text.splitlines(True), 1):
        if line.strip():
            if begin is None:
                begin, first = offset, number
            lines.append(line)
        elif begin is not None:
            yield first, "".join(lines)
            begin, lines = None, []
        offset += len(line)
    if begin is not None:
        yield first, "".join(lines)


def spelled(number):
    """The count as prose writes it, or None when it has no word in this file."""
    return NUMBERS[number - 1] if 1 <= number <= len(NUMBERS) else None


def names_count(body, total):
    """True when a paragraph spells this tree's count, in digits or in a word."""
    forms = {str(total)}
    word = spelled(total)
    if word:
        forms.add(word)
    return any(re.search(r"\b%s\b" % re.escape(form), body, re.IGNORECASE)
               for form in forms)


def value(token):
    """The number a claim says, as an int; digits and words read the same."""
    if token.isdigit():
        return int(token)
    return NUMBERS.index(token.lower()) + 1


def main():
    pictures = frames()
    if not pictures:
        print("error: git holds no frame under %s, so there is no count to check"
              % shown(RENDERS), file=sys.stderr)
        return 2
    total = len(pictures)

    problems = []
    claims = checked = 0
    for rel in (git("ls-files") or "").splitlines():
        path = ROOT / rel
        if path.suffix.lower() not in PROSE or path.name.startswith("selftest_"):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        checked += 1
        for first, body in paragraphs(text):
            history = names_count(body, total)
            for found in CLAIM.finditer(body):
                number = value(found.group(1))
                if number < FLOOR:
                    continue
                claims += 1
                if history or number == total:
                    continue
                line = first + body[:found.start()].count("\n")
                problems.append("%s:%d says %r, but this tree draws %d screens"
                                % (rel, line, found.group(0), total))

    for text in problems:
        print(text)
    if problems:
        print("\n%d claim(s) of a screen count this tree does not draw" % len(problems),
              file=sys.stderr)
        return 1
    print("%d claim(s) of a screen count in %d file(s): every one is %d, or names it "
          "in the same paragraph" % (claims, checked, total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
