"""Regression guards for compact Android 17 SystemUI shade headers.

Only checks source invariants; this is not a replacement for compiling SystemUI
or measuring rendered content under a physical display cutout.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "packages/SystemUI/compose/features/src/com/android/systemui/shade/ui/composable/ShadeHeader.kt"

def block(contents, method, next_marker):
    return contents.split(method, 1)[1].split(next_marker, 1)[0]


class CompactHeaderQA(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = SOURCE.read_text(encoding="utf-8")

    def test_expanded_clock_responds_to_dp_width_and_font_scale(self):
        expanded = block(
            self.text, "fun ContentScope.ExpandedShadeHeader(",
            "fun ContentScope.OverlayShadeHeader(",
        )
        self.assertIn("displayConfig.screenWidthDp < 360", expanded)
        self.assertIn("displayConfig.screenWidthDp < 400", expanded)
        self.assertIn("displayConfig.fontScale", expanded)
        self.assertIn("scale = expandedClockScale", expanded)
        self.assertNotIn("scale = 2.57f,", expanded)

    def test_clock_scale_stays_bounded(self):
        expanded = block(
            self.text, "fun ContentScope.ExpandedShadeHeader(",
            "fun ContentScope.OverlayShadeHeader(",
        )
        self.assertIn("coerceAtLeast(1.4f)", expanded)
        self.assertIn("maxOf(1f, displayConfig.fontScale)", expanded)
        self.assertIn("else -> 2.57f", expanded)

    def test_quick_settings_header_gives_battery_its_own_space(self):
        header = block(
            self.text, "fun QuickSettingsOverlayHeader(",
            "fun ContentScope.QuickSettingsOverlayPrivacyChip(",
        )
        self.assertIn("modifier = Modifier.weight(1f)", header)
        self.assertIn("Spacer(Modifier.width(8.dp))", header)
        self.assertIn("BatteryInfo(viewModel = viewModel", header)

    def test_existing_notch_layout_is_preserved(self):
        self.assertIn("CutoutAwareShadeHeader(", self.text)
        self.assertIn("LocalDisplayCutout.current", self.text)


if __name__ == "__main__":
    unittest.main()
