#!/usr/bin/env python3
"""Print which selftest branch pins which refusal, and keep the pairs honest.

Every checker passes on this tree, so its failure paths are only ever run by the
selftest cases that name them; reading eight files to learn whether a refusal has
a case is how a gap survives a refactor. This prints the map - per selftest, the
checker it exercises and how many branches assert a message - and refuses the two
pairings the map rests on: a checker with no selftest has refusals nothing runs,
and a selftest naming a checker that is gone is a fixture for a file nobody has.

It does not try to decide coverage from the text. A `want` string is a substring
of a formatted message, so a branch that stops refusing is caught by running it,
which `./check.sh --selftest` does; which refusal it pinned is what this adds.

  python3 check_map.py       exit 0 when every checker has a selftest

It also lists, per checker, the messages no branch repeats: every string the
checker appends to a problem list or prints as an error, and whether a branch
asserts it, matching word for word with placeholders and numbers standing in
for any one word. That list is a report, not a refusal - a phrase can match by
coincidence, and a message built by a helper is invisible to it - but it is
where an untested refusal shows up first.
"""
import ast
import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def shown(path):
    try:
        return Path(path).resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def load(path):
    """A selftest module, read for its CASES; nothing in one runs at import."""
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# A message's `%s`/`%d`/`{...}` parts, which a branch cannot repeat: it asserts the
# text the checker prints, and these are the values filled in at run time.
PLACEHOLDER = re.compile(r"%[-+ 0-9.#]*[sdifxXoeEgGcr]|\{[^{}]*\}")
# The shortest asserted phrase worth matching: shorter is a word or two
# and would make any message look covered.
MIN_PHRASE = 8


def _message(node):
    """The literal text of one append/print argument, or None when it is built."""
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Mod):
        node = node.left
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.JoinedStr):
        return "".join(
            part.value for part in node.values
            if isinstance(part, ast.Constant) and isinstance(part.value, str)
        )
    return None


def _sink(node):
    """A name a checker collects its refusals in: `problems`, `found`, ..."""
    return isinstance(node, ast.Name) and (
        "problem" in node.id or node.id in {"found", "stale"}
    )


def messages(path):
    """Every message a Python checker appends to a problem list or prints as an error."""
    try:
        tree = ast.parse(Path(path).read_text(encoding="utf-8"))
    except (OSError, SyntaxError, ValueError):
        return []
    found = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        attr = getattr(node.func, "attr", "")
        text = None
        if attr == "append" and _sink(node.func.value):
            text = _message(node.args[0]) if node.args else None
        elif attr == "print" and any(kw.arg == "file" for kw in node.keywords):
            text = next((t for t in (_message(arg) for arg in node.args) if t), None)
        if text and text not in found:
            found.append(text)
    return found


def squash(text):
    """A message or phrase with the values run time fills in stood in for one mark.

    A branch asserts the text the checker prints, so a size, an md5 or a path is
    the same placeholder on both sides; without that, "records md5 00000000"
    would look unasserted because the message says `%s` where the branch says the
    digits. A literal percent arrives doubled in a format string, so it is halved
    first, and a number inside a word (`md5`) is not a value.
    """
    text = text.replace("%%", "%")
    text = PLACEHOLDER.sub("\u2026", text)
    text = re.sub(r"\b\d+\s*x\s*\d+\b", "\u2026x\u2026", text)
    text = re.sub(r"(?<!\w)\d[\d,.]*", "\u2026", text)
    return re.sub(r"\s+", " ", text).strip()


def tokens(text):
    """Words, with a placeholder as the one token `*` and punctuation dropped."""
    out = []
    for word in squash(text).split():
        word = word.strip(".,;:()[]")
        if not word:
            continue
        if set(word) <= set("\u2026:"):
            # `…`, `……` and a `%s:%d` line reference are all just a value.
            out.append("*")
        else:
            out.append(word)
    return out


def _contains(needle, haystack):
    """A wildcard on either side matches any one word."""
    for start in range(len(haystack) - len(needle) + 1):
        for offset, token in enumerate(needle):
            other = haystack[start + offset]
            if token != "*" and other != "*" and token != other:
                break
        else:
            return True
    return False


def repeated(text, phrases):
    """True when a branch asserts this message, or the part of it that carries.

    Both directions are asked because a check prints one line per problem and a
    branch may assert several of them together: "build failed; flash OVER by ..."
    covers two messages, and "records md5 00000000" covers the front of one.
    """
    said = tokens(text)
    if len([word for word in said if word != "*"]) < 2:
        return True         # nothing but placeholders: there is no text to assert
    for phrase in phrases:
        wanted = tokens(phrase)
        if len([word for word in wanted if word != "*"]) < 2:
            continue
        if _contains(said, wanted) or _contains(wanted, said):
            return True
    return False


def gaps(path, phrases):
    """The messages this checker can print that no branch's `want` repeats."""
    return [m for m in messages(path) if not repeated(m, phrases)]


def checkers():
    """Every gate that has a fixture, the shell gate included."""
    found = sorted(ROOT.glob("check_*.py"))
    shell = ROOT / "check.sh"
    if shell.is_file():
        found.append(shell)
    return found


def main():
    files = sorted(ROOT.glob("selftest_*.py"))
    if not files:
        print("error: no selftest_*.py found", file=sys.stderr)
        return 2

    problems = []
    covered = {}
    spoken = {}
    rows = []
    total = asserting = silent = 0
    for path in files:
        module = load(path)
        cases = module.CASES
        checker = getattr(module, "CHECK", None)
        if checker is None:
            problems.append("%s names no checker, so its branches pin no gate"
                            % shown(path))
            name = "-"
        else:
            name = shown(checker)
            if not Path(checker).is_file():
                problems.append("%s names %s, which this tree does not hold"
                                % (shown(path), name))
            else:
                covered.setdefault(Path(checker).resolve(), shown(path))
        phrases = set()
        for case in cases:
            phrases.update(case.get("want", ()))
        if checker is not None:
            spoken.setdefault(Path(checker), set()).update(phrases)
        pins = sum(1 for case in cases if case.get("want") or case.get("forbids"))
        total += len(cases)
        asserting += pins
        silent += len(cases) - pins
        rows.append((shown(path), name, len(cases), pins))

    for checker in checkers():
        if checker.resolve() not in covered:
            problems.append("%s has no selftest, so its refusals have no branch"
                            % shown(checker))

    print("%-24s %-26s %8s %8s" % ("selftest", "checker", "branches", "asserting"))
    for row in rows:
        print("%-24s %-26s %8d %8d" % row)
    print()
    print("%d branches across %d selftests: %d assert a message, %d assert only an "
          "exit code" % (total, len(files), asserting, silent))
    print()
    for path, phrases in sorted(spoken.items(), key=lambda pair: pair[0].name):
        if Path(path).suffix != ".py" or not Path(path).is_file():
            continue
        missing = gaps(path, phrases)
        if not missing:
            print("%s: every appended message is repeated by a branch" % shown(path))
            continue
        print("%s: %d message(s) no branch repeats" % (shown(path), len(missing)))
        for text in missing[:10]:
            print("  ? %s" % re.sub(r"\s+", " ", PLACEHOLDER.sub("\u2026", text)))
        if len(missing) > 10:
            print("  ? ... and %d more" % (len(missing) - 10))
    for line in problems:
        print("  - %s" % line)
    if problems:
        print("\n%d pairing problem(s): a checker nothing exercises is a rule "
              "nobody runs" % len(problems), file=sys.stderr)
        return 1
    print("every checker has a selftest (%d checked)" % len(checkers()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
