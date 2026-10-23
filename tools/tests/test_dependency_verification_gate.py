"""Offline regression tests; no repository/network/Gradle dependencies."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dependency_verification_gate import InvalidMetadata, compare, load_metadata, render

NS = 'https://schema.gradle.org/dependency-verification'
A = 'a' * 64
B = 'b' * 64


def fixture(sha=A, extra='', verify='true', group='org.example', artifact='sample-1.0.pom'):
    return f'''<?xml version="1.0"?>
<verification-metadata xmlns="{NS}">
  <configuration><verify-metadata>{verify}</verify-metadata><verify-signatures>false</verify-signatures></configuration>
  <components>
    <component group="{group}" name="sample" version="1.0">
      <artifact name="{artifact}"><sha256 value="{sha}"/>{extra}</artifact>
    </component>
  </components>
</verification-metadata>'''


class GateTests(unittest.TestCase):
    def read(self, xml):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'verification-metadata.xml'
            path.write_text(xml, encoding='utf-8')
            return load_metadata(path)

    def test_identical_artifacts_pass(self):
        old = self.read(fixture(A))
        new = self.read(fixture(A))
        self.assertEqual([], compare(old, new))
        self.assertIn('PASS', render([], len(old.entries)))

    def test_new_pom_is_missing_checksum_not_auto_approved(self):
        old = self.read(fixture(A))
        new = self.read(fixture(A).replace('</components>',
            f'''  <component group="com.example" name="new" version="2.0">
              <artifact name="new-2.0.pom"><sha256 value="{B}"/></artifact>
              </component></components>'''))
        findings = compare(old, new)
        self.assertEqual('MISSING_CHECKSUM', findings[0][0])
        self.assertEqual('com.example', findings[0][1][0])

    def test_changed_digest_is_mismatch(self):
        findings = compare(self.read(fixture(A)), self.read(fixture(B)))
        self.assertEqual('CHECKSUM_MISMATCH', findings[0][0])
        self.assertIn(A, findings[0][2])
        self.assertIn(B, findings[0][2])

    def test_extra_also_trust_is_mismatch(self):
        findings = compare(self.read(fixture()), self.read(fixture(extra=f'<also-trust value="{B}"/>')))
        self.assertEqual('CHECKSUM_MISMATCH', findings[0][0])

    def test_bad_digest_rejected(self):
        with self.assertRaisesRegex(InvalidMetadata, 'Invalid SHA-256'):
            self.read(fixture('bogus'))

    def test_disabled_metadata_verification_rejected(self):
        with self.assertRaisesRegex(InvalidMetadata, 'must remain true'):
            self.read(fixture(verify='false'))

    def test_duplicate_artifact_rejected(self):
        with self.assertRaisesRegex(InvalidMetadata, 'Duplicate artifact'):
            self.read(fixture(extra=f'</artifact><artifact name="sample-1.0.pom"><sha256 value="{B}"/>'))

    def test_candidate_cannot_weaken_policy(self):
        old = self.read(fixture())
        with self.assertRaises(InvalidMetadata):
            self.read(fixture(verify='false'))
        new = self.read(fixture().replace('<verify-signatures>false</verify-signatures>',
                                         '<verify-signatures>true</verify-signatures>'))
        self.assertEqual('POLICY_CHANGE', compare(old, new)[0][0])

    def test_android_unit_test_graph_preflight_avoids_ambiguous_artifact_selection(self):
        # AGP unit test variants require attributes assigned by Android test tasks.
        script = (Path(__file__).resolve().parents[1] / 'gradle-verification-preflight.init.gradle').read_text(encoding='utf-8')
        self.assertIn('graphOnlyConfigurations', script)
        self.assertIn("'debugUnitTestCompileClasspath', 'debugUnitTestRuntimeClasspath'", script)
        self.assertIn('configuration.incoming.resolutionResult.allComponents.size()', script)
        self.assertIn('if (graphOnlyConfigurations.contains(configName))', script)
        self.assertIn('configuration.files.size()', script)
        self.assertIn('testDebugUnitTest', script)

    def test_candidate_removal_is_detected(self):
        old = self.read(fixture())
        new = self.read(fixture(group='elsewhere'))
        kinds = [f[0] for f in compare(old, new)]
        self.assertIn('MISSING_CHECKSUM', kinds)
        self.assertIn('POLICY_CHANGE', kinds)


if __name__ == '__main__':
    unittest.main()
