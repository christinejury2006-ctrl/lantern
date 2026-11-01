#!/usr/bin/env python3
"""Print a GitHub Actions annotation whose body is a file's contents.

Usage:
    python3 tools/annotate.py notice "Title" file.txt
    python3 tools/annotate.py error  "Title" file.txt

Why a script: annotations are the only run output that is readable from the API and the run page
without downloading an artifact, so the startup smoke test reports its retraced crash this way. The
escaping rules (`%` -> `%25`, newline -> `%0A`) are easy to get subtly wrong inline in YAML.
"""
import pathlib
import sys

LIMIT = 6000


def main(argv):
    if len(argv) != 4:
        print(__doc__)
        return 2
    level, title, path = argv[1], argv[2], argv[3]
    text = pathlib.Path(path).read_text(errors='replace')[:LIMIT]
    text = text.replace('%', '%25').replace('\r', '').replace('\n', '%0A')
    print(f"::{level} title={title}::{text}")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
