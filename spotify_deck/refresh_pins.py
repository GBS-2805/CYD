#!/usr/bin/env python3
"""Re-check the Spotify chains and refresh the roots pinned in spotify_deck.ino.

    python3 spotify_deck/refresh_pins.py            rewrite the sketch if a root moved
    python3 spotify_deck/refresh_pins.py --check    report only; exit 1 when stale
    python3 spotify_deck/refresh_pins.py -v         also print the chain resolved

The deck pins *roots* with setCACert(), one per hostname, because
api.spotify.com and accounts.spotify.com do not share a certificate authority. A
root is only ever wrong if Spotify changes authority: rare, silent, and it looks
exactly like an outage (SPOTIFY UNREACHABLE, `[poll] status 0` in the log). This
script is how the two are told apart in half a minute.

It does not guess. A root is accepted only when `openssl s_client` validates the
host against it with -verify_return_error and -verify_hostname, which is the same
check the board makes, and the sketch is rewritten only when the SHA-256 of that
root differs from the PEM already in it. Needs `openssl` and nothing else.
"""

import argparse
import hashlib
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SKETCH = os.path.join(HERE, "spotify_deck.ino")

# hostname, C array name in the sketch
PINS = [
    ("api.spotify.com", "SPOTIFY_API_CA"),
    ("accounts.spotify.com", "SPOTIFY_TOKEN_CA"),
]

# Where the roots live. The first is the fill; the bundle is for systems that
# keep one concatenated file instead of a hashed directory.
TRUST_DIRS = [
    "/etc/ssl/certs",
    "/etc/pki/tls/certs",
    "/usr/local/share/ca-certificates",
    "/usr/share/ca-certificates",
]
TRUST_BUNDLES = ["/etc/ssl/cert.pem", "/etc/pki/tls/cert.pem"]

PEM_RE = re.compile(
    r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", re.S)


class Fail(Exception):
    pass


def openssl(args, stdin=None, timeout=45):
    try:
        return subprocess.run(["openssl"] + args, input=stdin,
                              capture_output=True, timeout=timeout)
    except FileNotFoundError:
        raise Fail("openssl is not installed; this script rides on it")
    except subprocess.TimeoutExpired:
        raise Fail("openssl timed out running " + " ".join(args[:3]))


def x509_info(pem):
    """subject/issuer/enddate/sha256, or None when the bytes are not a cert."""
    r = openssl(["x509", "-noout", "-subject", "-issuer", "-enddate",
                 "-fingerprint", "-sha256", "-nameopt", "RFC2253"],
                stdin=pem.encode())
    if r.returncode != 0:
        return None

    fields = {}
    for line in r.stdout.decode(errors="replace").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            # "sha256 Fingerprint=AB:CD:.." and "subject=CN=..,O=..,C=US" both
            # land here; the first word is the key.
            fields[key.split()[-1].lower()] = value.strip()
    if "subject" not in fields:
        return None
    return {
        "subject": fields["subject"],
        "issuer": fields["issuer"],
        # -enddate prints "notAfter=Nov 15 12:00:00 2038 GMT", not "enddate=".
        "enddate": fields.get("notafter", "?"),
        "fingerprint": fields.get("fingerprint", ""),
    }


def fetch_chain(host):
    """Every certificate the server sends, leaf first."""
    r = openssl(["s_client", "-connect", host + ":443", "-servername", host,
                 "-showcerts"], stdin=b"")
    text = r.stdout.decode(errors="replace") + r.stderr.decode(errors="replace")
    blocks = PEM_RE.findall(text)
    if not blocks:
        raise Fail("%s: no certificate came back (no network, or a TLS-inspecting "
                   "proxy answered instead of Spotify)" % host)
    return blocks


def trust_store():
    """subject -> (pem, info) for every root on this machine."""
    store = {}
    sources = []
    for directory in TRUST_DIRS:
        if os.path.isdir(directory):
            for name in sorted(os.listdir(directory)):
                path = os.path.join(directory, name)
                if os.path.isfile(path):
                    sources.append(path)
    sources.extend(p for p in TRUST_BUNDLES if os.path.isfile(p))

    seen = set()
    for path in sources:
        try:
            with open(path, "r", errors="ignore") as handle:
                body = handle.read()
        except OSError:
            continue
        for pem in PEM_RE.findall(body):
            digest = hashlib.sha256(pem.encode()).hexdigest()
            if digest in seen:
                continue
            seen.add(digest)
            info = x509_info(pem)
            if info:
                store.setdefault(info["subject"], (pem, info))
    if not store:
        raise Fail("no certificates found in %s; point openssl at a trust store"
                   % ", ".join(TRUST_DIRS))
    return store


def find_root(host, chain, store, verbose):
    """Walk the presented chain up to a self-signed root in the local store."""
    current = None
    for pem in chain:
        info = x509_info(pem)
        if info is None:
            continue
        if verbose:
            print("    %s" % info["subject"])
        current = (pem, info)
    if current is None:
        raise Fail("%s: none of the returned certificates parsed" % host)

    used = set()
    for _ in range(6):
        pem, info = current
        if info["subject"] == info["issuer"]:
            return pem, info
        hit = store.get(info["issuer"])
        if hit is None:
            raise Fail("%s: issued by %s, which is not in the local trust store, "
                       "and the server did not send it either. Update the machine's "
                       "ca-certificates and try again." % (host, info["issuer"]))
        if hit[1]["fingerprint"] in used:
            raise Fail("%s: the chain loops at %s" % (host, hit[1]["subject"]))
        used.add(hit[1]["fingerprint"])
        current = hit
    raise Fail("%s: the chain did not reach a self-signed root" % host)


def verifies(host, root_pem):
    """Exactly what the board asks of the root before it will talk."""
    handle, path = tempfile.mkstemp(suffix=".pem")
    try:
        with os.fdopen(handle, "w") as out:
            out.write(root_pem + "\n")
        r = openssl(["s_client", "-connect", host + ":443", "-servername", host,
                     "-CAfile", path, "-verify_return_error",
                     "-verify_hostname", host], stdin=b"")
    finally:
        os.unlink(path)
    text = (r.stdout + r.stderr).decode(errors="replace")
    return "Verify return code: 0 (ok)" in text, text


def array_pattern(name):
    # The exact shape the sketch uses, so a rewrite cannot reformat the file.
    return re.compile(r'(static const char ' + name +
                      r'\[\] = R"CA\(\n)(.*?)(\n\)CA";)', re.S)


def main():
    parser = argparse.ArgumentParser(
        description="Refresh the Spotify roots pinned in spotify_deck.ino.")
    parser.add_argument("--check", action="store_true",
                        help="report only; exit 1 when a pin is stale")
    parser.add_argument("-v", "--verbose", action="store_true",
                        help="print every certificate in the chain")
    args = parser.parse_args()

    if not os.path.isfile(SKETCH):
        raise Fail("no sketch at " + SKETCH)
    with open(SKETCH, encoding="utf-8", newline="") as handle:
        sketch = handle.read()

    store = trust_store()
    stale = {}
    for host, name in PINS:
        print("%s" % host)
        chain = fetch_chain(host)
        root_pem, info = find_root(host, chain, store, args.verbose)

        good, detail = verifies(host, root_pem)
        if not good:
            raise Fail("%s: the %s root from the local store does not validate it, "
                       "so it is not the right pin:\n%s"
                       % (host, info["subject"], detail.strip()[-600:]))

        match = array_pattern(name).search(sketch)
        if match is None:
            raise Fail("%s is not in %s any more; this script expects "
                       'static const char %s[] = R"CA( ... )CA";'
                       % (name, SKETCH, name))
        pinned = x509_info(match.group(2) + "\n")
        same = (pinned is not None
                and pinned["fingerprint"] == info["fingerprint"])

        print("    root    %s" % info["subject"])
        print("    expires %s" % info["enddate"])
        if same:
            print("    pin     in sync")
        else:
            was = pinned["subject"] if pinned else "unreadable bytes"
            print("    pin     %s\n    wanted  %s" % (was, info["subject"]))
            stale[name] = root_pem

    if not stale:
        print("\npins in sync; nothing written")
        return 0

    if args.check:
        print("\n%d pin(s) stale. Run without --check to rewrite %s, then reflash."
              % (len(stale), os.path.basename(SKETCH)))
        return 1

    for name, root_pem in stale.items():
        sketch = array_pattern(name).sub(
            lambda m: m.group(1) + root_pem.strip() + m.group(3), sketch, count=1)
    # Write beside the original and rename, so an interrupted run cannot leave a
    # half-written sketch on disk.
    temporary = SKETCH + ".tmp"
    with open(temporary, "w", encoding="utf-8", newline="") as handle:
        handle.write(sketch)
    os.replace(temporary, SKETCH)
    print("\nrewrote %d pin(s) in %s; rebuild and reflash the deck"
          % (len(stale), os.path.basename(SKETCH)))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Fail as error:
        print("refresh_pins: %s" % error, file=sys.stderr)
        sys.exit(2)
