"""Source-level guard for Dual Shade pull-down invocation geometry."""
from pathlib import Path
from xml.etree import ElementTree as ET
import unittest

ROOT = Path(__file__).resolve().parents[2]
XML = ROOT / "packages/SystemUI/res/values/shade_config.xml"


class DualShadeGestureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = ET.parse(XML).getroot()
        cls.items = {x.attrib["name"]: x for x in root if "name" in x.attrib}

    def test_two_shades_remain_available_by_default(self):
        self.assertEqual(self.items["config_dualShadeEnabledByDefault"].text.strip(), "true")

    def test_qs_has_at_least_one_third_of_top_gesture(self):
        item = self.items["config_invocationGestureSplitRatio"]
        self.assertEqual(item.attrib["format"], "float")
        self.assertEqual(item.attrib["type"], "dimen")
        threshold = float(item.text.strip())
        self.assertGreaterEqual(threshold, 0.55)
        self.assertLessEqual(threshold, 0.67)

    def test_full_width_phone_shades_unchanged(self):
        self.assertEqual(self.items["config_isFullWidthShade"].text.strip(), "true")


if __name__ == "__main__":
    unittest.main()
