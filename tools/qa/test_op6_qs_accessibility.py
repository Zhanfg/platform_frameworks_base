from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]/"packages/SystemUI"
class QSDetailsA11y(unittest.TestCase):
    def test_localized_english_and_chinese(self):
        for folder in ("values","values-zh-rCN"):
            root=ET.parse(ROOT/"res"/folder/"uwu_hotfix_accessibility.xml").getroot()
            values={x.get("name"):x.text for x in root}
            self.assertTrue(values.get("uwu_qs_details_back_accessibility"))
            self.assertTrue(values.get("uwu_qs_details_settings_accessibility"))
    def test_source_never_hardcodes_english_actions(self):
        s=(ROOT/"src/com/android/systemui/qs/panels/ui/compose/TileDetails.kt").read_text()
        self.assertIn("stringResource(R.string.uwu_qs_details_back_accessibility)",s)
        self.assertIn("stringResource(R.string.uwu_qs_details_settings_accessibility)",s)
        self.assertNotIn('"Back to QS panel"',s)
        self.assertNotIn('"Go to Settings"',s)
if __name__=="__main__":unittest.main()
