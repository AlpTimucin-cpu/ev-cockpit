#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tests/architecture_checks.py
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
src=app/src/main/java/com/evcockpit/mediahub
check() {
  local name=$1; shift
  echo "--- $name ---"
  kotlinc "$@" "tests/${name}.kt" -include-runtime -d "$work/${name}.jar"
  java -jar "$work/${name}.jar"
}
check M3uParserTest "$src/M3uParser.kt"
check M3uExporterTest "$src/M3uParser.kt" "$src/M3uExporter.kt"
check RadioQueuePlannerTest "$src/RadioQueuePlanner.kt"
check StreamProbeTest "$src/M3uParser.kt" "$src/StreamProbe.kt"
check TrustedSourcesTest "$src/M3uParser.kt" "$src/TrustedSources.kt"
check LibraryPageTest "$src/LibraryPage.kt"
echo 'PASS: All source smoke and offline Kotlin tests passed. APK build NOT tested.'
