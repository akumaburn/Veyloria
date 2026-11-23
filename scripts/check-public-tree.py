#!/usr/bin/env python3
"""Reject tracked hidden directories and private runtime files before publication."""
from pathlib import Path, PurePosixPath
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def main():
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    errors = []
    checked = 0
    for name in filter(None, paths):
        checked += 1
        path = PurePosixPath(name)
        if any(part.startswith(".") for part in path.parts[:-1]):
            errors.append(f"Hidden directory is tracked: {name}")
        if path.name == ".env" or (path.name.startswith(".env.") and path.name != ".env.example"):
            errors.append(f"Private environment file is tracked: {name}")
        if path.name.endswith(".bundle") or "node_modules" in path.parts:
            errors.append(f"Private history backup or dependency directory is tracked: {name}")
    for error in errors:
        print(error)
    print(f"Checked {checked} tracked paths; {len(errors)} publication errors")
    return bool(errors)


if __name__ == "__main__":
    raise SystemExit(main())
