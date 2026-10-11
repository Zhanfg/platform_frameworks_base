"""Source-level regression checks for Dual Shade educational tooltip placement.

This does not substitute for Android Compose compilation or real notch geometry tests.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "packages/SystemUI/compose/features/src/com/android/systemui/scene/ui/composable/DualShadeEducationalTooltips.kt"


class DualShadeTooltipBoundsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = SOURCE.read_text(encoding="utf-8")
        cls.positioner = cls.text.split("override fun calculatePosition(", 1)[1].split(
            "return IntOffset(", 1
        )[0]
        cls.return_value = cls.text.split("return IntOffset(", 1)[1].split(")", 1)[0]

    def test_cutout_moves_lower_bound_down_not_popup_up(self):
        self.assertIn("val safeTop = displayCutoutTop + screenEdgePadding", cls_text(self))
        self.assertNotIn("anchorBounds.bottom - displayCutoutTop", cls_text(self))

    def test_position_is_clamped_on_both_axes(self):
        self.assertIn("preferredX.coerceIn(screenEdgePadding, maxX)", cls_text(self))
        self.assertIn("coerceIn(safeTop, maxY)", cls_text(self))
        self.assertIn("windowSize.width - popupContentSize.width", cls_text(self))
        self.assertIn("windowSize.height - popupContentSize.height", cls_text(self))

    def test_anchor_gap_is_preserved(self):
        self.assertIn("anchorBounds.bottom + gapBetweenPopupAndAnchor", cls_text(self))
        self.assertIn("WindowInsets.displayCutout.getTop(density)", cls_text(self))


def cls_text(test_case):
    return test_case.text


if __name__ == "__main__":
    unittest.main()
