"""Convert `gradle dependencies` tree output into a gradle.lockfile that OSV-Scanner can read.

Aurora has no Gradle lockfiles, so CI resolves the real dependency graph and scans that.
Usage: python scripts/gradle-deps-to-lockfile.py OUT.lockfile DEPS.txt [DEPS.txt ...]
"""
import re
import sys

out_path, inputs = sys.argv[1], sys.argv[2:]
dep = re.compile(r'[+\\]--- ([\w.\-]+):([\w.\-]+)(?::([^\s]+))?')
found = set()
for path in inputs:
    for line in open(path, encoding='utf-8', errors='ignore'):
        m = dep.search(line)
        if not m:
            continue
        group, artifact, version = m.groups()
        if '->' in line:                       # conflict resolution: the resolved version wins
            version = line.split('->')[1].split()[0]
        version = (version or '').strip('() *')
        if group == 'project' or not version or version.startswith('{'):
            continue
        found.add(f'{group}:{artifact}:{version}=runtimeClasspath')
with open(out_path, 'w') as f:
    f.write('\n'.join(sorted(found)) + '\nempty=\n')
print(len(found), 'resolved artifacts')
