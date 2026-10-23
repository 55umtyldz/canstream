#!/usr/bin/env bash
# CI-only: isolated Gradle home; proposed hashes are *never* committed or trusted.
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
report_dir="$repo_root/dependency-verification-report"
mkdir -p "$report_dir"

# On Actions this is already supplied by the caller. Other environments get
# a separate disposable Gradle cache without altering the regular user cache.
if [[ -z "${GRADLE_USER_HOME:-}" ]]; then
    export GRADLE_USER_HOME="$(mktemp -d)"
fi
baseline="$(mktemp)"
cp gradle/verification-metadata.xml "$baseline"
restore_metadata() { cp "$baseline" gradle/verification-metadata.xml; rm -f "$baseline"; }
trap restore_metadata EXIT

# Fail closed if metadata verification was disabled or checksum values are bad.
python3 tools/dependency_verification_gate.py \
    --approved "$baseline" --report "$report_dir/static-audit.md"

# Gradle 8.12 resolves all resolvable configurations in bootstrap mode.
# This writes ONLY an untrusted candidate, which we compare and then remove.
set +e
bash ./gradlew --no-daemon --console=plain --write-verification-metadata sha256 help \
    > "$report_dir/gradle-discovery.log" 2>&1
bootstrap_status=$?
set -e
if (( bootstrap_status != 0 )); then
    cat "$report_dir/gradle-discovery.log"
    printf '%s\n' 'FAIL: Gradle dependency discovery could not complete.' | tee "$report_dir/discovery-status.md"
    exit "$bootstrap_status"
fi

# A clean new dependency SHA-256 is not auto-approved. CI intentionally fails.
python3 tools/dependency_verification_gate.py \
    --approved "$baseline" \
    --candidate gradle/verification-metadata.xml \
    --report "$report_dir/metadata-diff.md"

# Restore committed checksums BEFORE running strict verification.
cp "$baseline" gradle/verification-metadata.xml

set +e
bash ./gradlew --no-daemon --console=plain \
    -Dorg.gradle.dependency.verification.console=verbose \
    --dependency-verification=strict \
    --init-script tools/gradle-verification-preflight.init.gradle \
    verifyDependencyMetadataPreflight \
    > "$report_dir/gradle-strict.log" 2>&1
strict_status=$?
set -e
if (( strict_status != 0 )); then
    tail -n 160 "$report_dir/gradle-strict.log"
    echo 'FAIL: Strict Gradle preflight could not resolve all selected dependency configurations. Inspect gradle-strict.log for nested causes and any Gradle verification report.' | tee "$report_dir/strict-status.md"
    exit "$strict_status"
fi

# Safeguard: even the CI temporary candidate must not leak into the source tree.
cmp -s "$baseline" gradle/verification-metadata.xml || {
    echo 'FAIL: Approved verification metadata changed during preflight.'
    exit 1
}
echo 'PASS: Dependency Verification Gate (discovery + committed SHA-256 comparison + strict artifact check).'
