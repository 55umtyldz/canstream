# Dependency Verification Gate

This project keeps Gradle checksum verification **strict**, including POM and Gradle Module Metadata (`<verify-metadata>true</verify-metadata>`). `gradle/verification-metadata.xml` is the reviewed source of truth; a newly downloaded checksum is **never auto-approved**.

## Execution order

Both **Validate** (pull requests) and **Build and Publish** (`main`) run this gate **before** provider unit tests, `.cs3` packaging and any `builds` branch publishing. A failed step stops normal steps in the same job. The failure diagnostic artifact is uploaded using `if: failure()`; no publishing step runs.

1. **Static audit** (`tools/dependency_verification_gate.py`): XML parses, SHA-256 values have 64 hex characters, metadata verification stays enabled, and duplicate artifact entries are rejected.
2. **Clean Linux discovery:** an independent `${RUNNER_TEMP}` `GRADLE_USER_HOME` uses Gradle 8.12 `--write-verification-metadata sha256 help`. According to Gradle 8.12 documentation, bootstrap resolves **all resolvable configurations** for root, subprojects, builds and plugins. This generates **untrusted candidate metadata**. It is a discovery mechanism, not approval.
3. **Semantic comparison:** compare recorded `(group, module, version, artifact)` SHA-256 values with generated candidates, ignoring XML order and informational `origin` attributes. `MISSING_CHECKSUM`, `CHECKSUM_MISMATCH`, and `POLICY_CHANGE` are reported separately. Any difference fails CI and needs human review.
4. **Strict artifact verification:** restore approved metadata before resolving Android/Kotlin debug, release, test, compiler and Android API classpaths using `--dependency-verification=strict`. This ensures that already approved hashes are still correct for the actual bytes delivered to CI.
5. The generated verification metadata is restored **on every exit**, including failure. Generated checksums are not committed, merged, uploaded as trusted metadata, or used to bypass strict verification.

Dependency failures in some Gradle configurations initialized only during compilation may still surface in the subsequent mandatory unit-test/build stages. Passing the early gate is not a substitute for the full build.

## Output

When the gate fails, open the **Dependency Verification Diagnostics** artifact in the Actions run. It includes:

- `metadata-diff.md` — list of missing vs mismatched artifacts and proposed digests, **not approved**.
- `static-audit.md` — policy and metadata format validation.
- `gradle-discovery.log` / `gradle-strict.log` — detailed Gradle failures.
- Original Gradle `dependency-verification` HTML report when generated.

## Safe remediation

1. Investigate each failed `(group, module, version, filename)` coordinate and the actual repository. Distinguish a **missing** artifact record from a **mismatch** with existing, trusted checksums.
2. Get the intended dependency metadata from the **official publisher/repository** over HTTPS; verify independent publisher-provided signatures or attestations where available. Maven repository `.sha1` alone is not strong evidence of authenticity. A checksum from the same potentially compromised mirror is not an independent trust anchor.
3. Add or update **only reviewed** SHA-256 records in `gradle/verification-metadata.xml`, never run unattended automatic checksum acceptance into Git.
4. Test locally and then allow the clean CI gate plus provider build and publication checks to run.
5. Do not set `<verify-metadata>false</verify-metadata>`, `--dependency-verification off/lenient`, skip the gate, or blindly append `also-trust` to silence mismatches.

## Local tests and usage

```powershell
python -m unittest discover -s tools/tests -p 'test_dependency_verification_gate.py'
python tools/dependency_verification_gate.py --approved gradle/verification-metadata.xml
# In Git Bash, WSL or Linux (requires the full Android SDK/Gradle build environment):
bash tools/run_dependency_verification_gate.sh
```

The gate is read-only regarding the **approved repository state**; the bootstrap step temporarily updates the metadata file in the working directory and restores it on exit. Run it with a clean local working tree if you need clear diffs. Do not run while actively editing the same XML file. For the strictest clean-environment reproducibility, run the gate in GitHub Actions (JDK 17, SDK, no reused Gradle caches).
