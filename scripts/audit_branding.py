#!/usr/bin/env python3
"""Audit working-tree user-facing source/README brand remnants without rewriting IDs."""
import subprocess

cmd = [
    "git", "grep", "-niE", r"ID4|ID[.]4|Mola",
    "--", "*.kt", "*.xml", "README", "README.*", "**/README", "**/README.*",
]
scan = subprocess.run(cmd, capture_output=True, text=True, check=False)
if scan.returncode not in (0, 1):
    raise SystemExit(scan.stderr.strip() or "Unable to inspect repository")
matches = scan.stdout.splitlines()
print(f"Brand term source/README matches: {len(matches)}")
for match in matches[:80]:
    # Public repository only; never modify upstream IDs or legal attribution blindly.
    print(match[:280])
if matches:
    print("NOTE: audit-only. Remove visible labels, but preserve functional package IDs, licenses and git history.")
