#!/usr/bin/env python3
"""Retrace an R8/ProGuard stack trace with the mapping.txt of the build that produced it.

Usage:
    python3 tools/retrace.py app/build/outputs/mapping/release/mapping.txt trace.txt
    python3 tools/retrace.py mapping.txt --dump J.j J.k w2.A4

--dump prints the *reverse* mapping of the given obfuscated classes: the original class and every
member it has, obfuscated name first. That is what you need when a crash names a class and you have
to decide which of its methods is at fault.

Why this exists: the artifact that holds mapping.txt cannot be downloaded in every environment, but
the file can be printed where it is generated. This script is the reader for that output.
"""
import sys
import re

CLASS_RE = re.compile(r'^(\S+) -> (\S+):$')
MEMBER_RE = re.compile(r'^\s+(?:(\d+):(\d+):)?(\S+) (\S+)\(([^)]*)\)(?::(\d+):(\d+))? -> (\S+)$')
FIELD_RE = re.compile(r'^\s+(\S+) (\S+) -> (\S+)$')


def parse(path):
    """obfuscated class -> (original class, [member dicts])."""
    classes = {}
    current = None
    with open(path, encoding='utf-8', errors='replace') as handle:
        for raw in handle:
            line = raw.rstrip('\n')
            if not line.strip() or line.startswith('#'):
                continue
            match = CLASS_RE.match(line)
            if match:
                original, obfuscated = match.group(1), match.group(2)
                current = {'original': original, 'members': []}
                classes[obfuscated] = current
                continue
            if current is None:
                continue
            member = MEMBER_RE.match(line)
            if member:
                current['members'].append({
                    'from_line': member.group(1),
                    'to_line': member.group(2),
                    'type': member.group(3),
                    'name': member.group(4),
                    'args': member.group(5),
                    'obfuscated': member.group(8),
                    'original_from': member.group(6),
                    'original_to': member.group(7),
                })
                continue
            field = FIELD_RE.match(line)
            if field:
                current['members'].append({
                    'type': field.group(1),
                    'name': field.group(2),
                    'args': '',
                    'obfuscated': field.group(3),
                    'from_line': None,
                    'to_line': None,
                    'original_from': None,
                    'original_to': None,
                })
    return classes


FRAME_RE = re.compile(r'^(\s*)at ([\w$.]+)\.([\w$<>]+)\((.*)$')
EXCEPTION_RE = re.compile(r'^(\s*)([\w$.]+)(:\s*(.*))?$')


def retrace_line(classes, line):
    frame = FRAME_RE.match(line)
    if not frame:
        exception = EXCEPTION_RE.match(line)
        if exception and not line.startswith('\t'):
            name = exception.group(2)
            message = exception.group(4)
            original = classes.get(name)
            if original:
                return f"{original['original']}" + (f": {message}" if message else '')
            # An obfuscated exception usually looks like `J.j: message`; the class alone is a frame
            # in the same trace.
            first = classes.get(name.split('.')[0] + '.' + name.split('.')[1]) if '.' in name else None
            if first:
                return f"{first['original']}" + (f": {message}" if message else '')
        return line
    indent, cls, method, rest = frame.groups()
    entry = classes.get(cls)
    if not entry:
        return line
    candidates = [m for m in entry['members'] if m['obfuscated'] == method]
    if not candidates:
        return f"{indent}at {entry['original']}.{method}({rest} (unmapped member))"
    member = candidates[0]
    args = member['args']
    message = f"{indent}at {entry['original']}.{member['name']}({args})({rest}"
    if len(candidates) > 1:
        others = ' | '.join(f"{m['name']}({m['args']})" for m in candidates[1:])
        message += f"  [also: {others}]"
    return message


def retrace(classes, lines):
    return '\n'.join(retrace_line(classes, line) for line in lines)


def dump(classes, names):
    out = []
    for name in names:
        entry = classes.get(name)
        if not entry:
            out.append(f"{name}: not in this mapping")
            continue
        out.append(f"{name} -> {entry['original']}")
        for member in entry['members']:
            span = ''
            if member['original_from']:
                span = f" (source lines {member['from_line']}-{member['to_line']})"
            out.append(f"    {member['obfuscated']} -> {member['type']} {member['name']}({member['args']}){span}")
    return '\n'.join(out)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    classes = parse(argv[1])
    if '--dump' in argv:
        names = argv[argv.index('--dump') + 1:]
        print(dump(classes, names))
        return 0
    text = open(argv[2], encoding='utf-8', errors='replace').read().splitlines() if len(argv) > 2 else sys.stdin.read().splitlines()
    print(retrace(classes, text))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
