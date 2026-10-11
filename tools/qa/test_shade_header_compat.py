"""Static guard for the compact QS header in SystemUI.

Does not assert correct physical DPI or replace Android resource compilation.
"""
from pathlib import Path
from xml.etree import ElementTree as ET
import unittest

ROOT = Path(__file__).resolve().parents[2]
HEADER = ROOT / "packages/SystemUI/res/layout/combined_qs_header.xml"
A = "{http://schemas.android.com/apk/res/android}"


class HeaderLayoutTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = ET.parse(HEADER).getroot()

    def test_direction_aware_header_padding(self):
        self.assertEqual(cls_attr(self.root, "paddingStart"), "@dimen/qs_panel_padding")
        self.assertEqual(cls_attr(self.root, "paddingEnd"), "@dimen/qs_panel_padding")
        self.assertNotIn(A + "paddingLeft", self.root.attrib)
        self.assertNotIn(A + "paddingRight", self.root.attrib)

    def test_date_does_not_overflow_compact_header(self):
        date = next(x for x in self.root.iter() if cls_attr(x, "id") == "@+id/date")
        self.assertEqual(cls_attr(date, "singleLine"), "true")
        self.assertEqual(cls_attr(date, "ellipsize"), "end")
        self.assertEqual(cls_attr(date, "textAppearance"), "@style/TextAppearance.QS.Status")

    def test_clock_remains_single_line(self):
        clock = next(x for x in self.root.iter() if cls_attr(x, "id") == "@+id/clock")
        self.assertEqual(cls_attr(clock, "singleLine"), "true")
        self.assertEqual(cls_attr(clock, "textAppearance"), "@style/TextAppearance.QS.Status.Clock")


def cls_attr(element, name):
    return element.attrib.get(A + name)


if __name__ == "__main__":
    unittest.main()
