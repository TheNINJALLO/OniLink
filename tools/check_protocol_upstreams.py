"""Compare checked-out upstream source with reviewed commits, without executing it.

The daily workflow fetches upstream main; this command itself is offline. A changed
file is a request for protocol review, never proof that a new client is compatible.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

MANIFEST = Path(__file__).with_name("protocol_upstreams.json")


def git(checkout: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(checkout), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
        timeout=60,
    )
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "Git inspection failed")
    return result.stdout.strip()


def inspect(checkout: Path, source: dict) -> dict:
    reviewed = source["reviewedCommit"]
    head = git(checkout, "rev-parse", "--verify", "HEAD^{commit}")
    git(checkout, "rev-parse", "--verify", f"{reviewed}^{{commit}}")
    # Name/status output with NUL delimiters handles spaces and tabs in paths.
    diff = git(
        checkout,
        "diff",
        "--name-status",
        "--no-renames",
        "--no-ext-diff",
        "--no-textconv",
        "-z",
        reviewed,
        head,
        "--",
        *source["paths"],
    )
    fields = diff.rstrip("\0").split("\0") if diff else []
    changes = [
        {"status": fields[index], "path": fields[index + 1]}
        for index in range(0, len(fields), 2)
    ]
    return {
        "status": "review-required" if changes else "current",
        "reviewedCommit": reviewed,
        "observedCommit": head,
        "compareUrl": f"https://github.com/{source['repository']}/compare/{reviewed}...{head}",
        "changes": changes,
    }


def audit(manifest: dict, checkouts: dict[str, Path]) -> dict:
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported upstream manifest version")
    results = {}
    for name, source in manifest["sources"].items():
        try:
            results[name] = inspect(checkouts[name], source)
        except (KeyError, OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            results[name] = {"status": "error", "error": str(error)}
    return {
        "targetClient": manifest["targetClient"],
        "sources": results,
        "reviewRequired": any(item["status"] != "current" for item in results.values()),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument(
        "--source", action="append", default=[], metavar="NAME=CHECKOUT"
    )
    parser.add_argument("--output", type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    checkouts = {
        name: MANIFEST.parent.parent / ".cache" / "protocol-audit" / name
        for name in manifest["sources"]
    }
    for value in args.source:
        name, separator, directory = value.partition("=")
        if not separator or name not in checkouts or not directory:
            parser.error("--source must name a manifest source and a checkout")
        checkouts[name] = Path(directory)
    report = audit(manifest, checkouts)
    rendered = json.dumps(report, indent=2) + "\n"
    print(rendered, end="")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as summary:
            summary.write(
                "## Protocol upstream review\n\n```json\n" + rendered + "```\n"
            )
    if any(item["status"] == "error" for item in report["sources"].values()):
        return 2
    return 1 if report["reviewRequired"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
