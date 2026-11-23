#!/usr/bin/env python3
"""Check repository Markdown file links and heading anchors without network access."""
import html
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent


def anchors(path):
    found = set()
    fenced = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if re.match(r"^\s*(```|~~~)", line):
            fenced = not fenced
        if fenced:
            continue
        match = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if match:
            title = html.unescape(re.sub(r"<[^>]+>", "", match[1])).lower()
            slug = re.sub(r"[^\w\- ]", "", title).replace(" ", "-")
            base, suffix = slug, 0
            while slug in found:
                suffix += 1
                slug = f"{base}-{suffix}"
            found.add(slug)
        found.update(re.findall(r'<a\s+(?:id|name)=["\']([^"\']+)', line))
    return found


def main():
    files = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "*.md"],
        cwd=ROOT,
    ).decode().split("\0")
    errors, checked = [], 0
    for name in sorted(set(filter(None, files))):
        path = ROOT / name
        if not path.is_file():
            continue
        fenced = False
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if re.match(r"^\s*(```|~~~)", line):
                fenced = not fenced
            if fenced:
                continue
            for match in re.finditer(r'\[[^\]]*\]\(<?([^\s)>]+)>?(?:\s+"[^"]*")?\)', line):
                url = urlsplit(match[1])
                if url.scheme or url.netloc:
                    continue
                target = (path.parent / unquote(url.path)).resolve() if url.path else path
                checked += 1
                if not target.exists():
                    errors.append(f"{name}:{number}: missing {match[1]}")
                elif url.fragment and target.is_file() and target.suffix == ".md":
                    if unquote(url.fragment) not in anchors(target):
                        errors.append(f"{name}:{number}: unknown heading {match[1]}")
    for error in errors:
        print(error)
    print(f"Checked {checked} local Markdown links; {len(errors)} errors")
    return bool(errors)


if __name__ == "__main__":
    raise SystemExit(main())
