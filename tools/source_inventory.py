from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


EXCLUDED_PARTS = {
    ".cache",
    ".git",
    ".gradle",
    ".idea",
    ".pytest_cache",
    ".ruff_cache",
    ".upstream",
    ".venv",
    ".vscode",
    "__pycache__",
    "build",
    "dist",
    "node_modules",
}
EXCLUDED_SUFFIXES = {".class", ".dmp", ".log", ".pdb", ".zip"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output", type=Path, default=Path("docs/SOURCE_FILE_INVENTORY.txt")
    )
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = (
        (root / args.output).resolve()
        if not args.output.is_absolute()
        else args.output.resolve()
    )
    output.relative_to(root)
    files = sorted(
        path
        for path in root.rglob("*")
        if path.is_file()
        and path.resolve() != output
        and not EXCLUDED_PARTS.intersection(path.relative_to(root).parts)
        and path.suffix.lower() not in EXCLUDED_SUFFIXES
    )
    lines = [
        "OniLink/OniBridge/OniBridge-Geyser source-file inventory (SHA-256, relative path)",
        "This generated inventory file is itself part of the workspace and is intentionally not self-hashed.",
        f"files={len(files)}",
        "",
    ]
    lines.extend(
        f"{sha256(path)}  {path.relative_to(root).as_posix()}" for path in files
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {output} with {len(files)} entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
