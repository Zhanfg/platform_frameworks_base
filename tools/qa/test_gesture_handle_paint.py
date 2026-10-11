"""Guard the SystemUI pill against stale cached NAVIGATION_MODE values."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
SRC=ROOT/"packages/SystemUI/src/com/android/systemui/navigationbar/gestural/NavigationHandle.java"
HOME=ROOT/"packages/SystemUI/res/layout/home_handle.xml"
class GesturePillTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source=SRC.read_text(encoding="utf-8")
    def test_render_switch_uses_current_user_secure_setting(self):
        self.assertIn("Settings.Secure.UWU_HIDE_GESTURE_HANDLE",self.source)
        self.assertIn("Settings.Secure.getIntForUser(",self.source)
        self.assertIn("mHideHandle = shouldHideHandle(hideHandle);",self.source)
        self.assertIn("return hideHandle == 1;",self.source)
    def test_async_navigation_mode_mirror_not_gating_visibility(self):
        method=self.source.split("private void updateHandleVisibility()",1)[1].split("protected boolean isHandleHidden",1)[0]
        self.assertNotIn("Settings.Secure.NAVIGATION_MODE,",method)
    def test_gesture_touch_target_kept(self):
        self.assertIn("if (isHandleHidden()) return;",self.source)
        self.assertNotIn("setVisibility(GONE)",self.source)
        root=ET.parse(HOME).getroot()
        self.assertTrue(root.tag.endswith(".NavigationHandle"))
        self.assertEqual(root.attrib["{http://schemas.android.com/apk/res/android}id"],"@+id/home_handle")
if __name__=="__main__": unittest.main()
