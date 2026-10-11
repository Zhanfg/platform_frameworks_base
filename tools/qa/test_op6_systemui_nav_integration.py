"""Source contract for the *actual integrated* OnePlus 6 SystemUI implementation.
No Android JVM/ART compilation is performed by this test.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
GESTURAL = ROOT / "packages/SystemUI/src/com/android/systemui/navigationbar/gestural"
MOMENT = ROOT / "packages/SystemUI/src/com/android/systemui/moment/arc"
CLOCK = ROOT / "packages/SystemUI/src/com/android/systemui/statusbar/policy/Clock.java"


class OnePlus6NavigationContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.handle = (GESTURAL / "NavigationHandle.java").read_text()
        cls.rotation = (GESTURAL / "QuickswitchOrientedNavHandle.java").read_text()
        cls.controller = (MOMENT / "MomentArcController.kt").read_text()
        cls.startable = (MOMENT / "MomentArcStartable.kt").read_text()
        cls.view = (MOMENT / "MomentArcView.kt").read_text()
        cls.clock = CLOCK.read_text()

    def test_pill_hides_only_drawing_not_touches(self):
        self.assertIn("mHideHandle = shouldHideHandle(hideHandle);", self.handle)
        self.assertIn("return hideHandle == 1;", self.handle)
        self.assertIn("if (isHandleHidden()) return;", self.handle)
        self.assertIn("if (isHandleHidden()) return;", self.rotation)
        self.assertNotIn("setVisibility(GONE)", self.handle)
        self.assertNotIn("Settings.Secure.getUriFor(Settings.Secure.NAVIGATION_MODE)",
                         self.handle)

    def test_moment_layer_has_touch_passthrough(self):
        self.assertIn("WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE", self.view)
        self.assertIn("WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL", self.view)
        self.assertIn("fun dispatchTouchCoordinates(", self.view)

    def test_moment_icon_ipc_never_runs_inline_on_gesture(self):
        code = self.controller
        self.assertIn("private val iconExecutor = ThreadPoolExecutor(", code)
        self.assertIn("ArrayBlockingQueue<Runnable>(16)", code)
        self.assertIn("iconExecutor.execute {", code)
        method = code.split("private fun createIconView(", 1)[1].split(
            "private fun loadIcon(", 1
        )[0]
        self.assertNotIn("loadIcon(target)", method.split("iconExecutor.execute {", 1)[0])
        self.assertIn("loadIcon(target)", method.split("iconExecutor.execute {", 1)[1])
        self.assertIn("overlayGeneration.get() == generation", method)
        self.assertIn("mainHandler.post {", method)

    def test_gesture_coordinate_hot_path_has_no_setting_ipc(self):
        s = self.controller
        event = s.split("fun onTouchCoordinates(", 1)[1].split("fun hide()", 1)[0]
        self.assertNotIn("Settings.Secure.getIntForUser", event)
        self.assertIn("fun isMomentArcEnabled(): Boolean = gestureEnabled", s)
        self.assertIn("controller.refreshEnabledState()", self.startable)
        self.assertIn("onUserChanged(newUser: Int, userContext: Context)", self.startable)

    def test_expired_or_cancelled_animation_is_cleaned(self):
        s = self.controller
        self.assertIn("mainHandler.removeCallbacks(maxLifetimeRunnable)", s)
        self.assertIn("MAX_LIFETIME_MS = 10_000L", s)
        self.assertIn("windowManager.removeViewImmediate(view)", s)
        self.assertIn("MAX_EXIT_CLEANUP_MS = 500L", s)
        self.assertIn("overlayGeneration.incrementAndGet()", s)

    def test_gesture_broadcast_crashes_contained(self):
        s = self.startable
        self.assertIn('Log.e(TAG, "MomentArc gesture dispatch failed", e)', s)
        self.assertIn("catch (e: RuntimeException)", s)

    def test_clock_seconds_lifecycle_safe(self):
        s = self.clock
        self.assertIn("mSecondsHandler == null || !mAttached", s)
        self.assertIn("handler.removeCallbacks(mSecondTick)", s)
        self.assertIn("mAttached && mShowSeconds && isShown()", s)
        self.assertIn("getDisplay().getState() == Display.STATE_ON", s)

if __name__ == "__main__":
    unittest.main()
