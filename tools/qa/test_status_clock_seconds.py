"""Source-level tests: second clock defaults, cleanup and low-wake scheduling.

A real SystemUI build/instrumentation run is still required.
"""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
SRC=ROOT/"packages/SystemUI/src/com/android/systemui/statusbar/policy/Clock.java"

class ClockSecondsLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src=SRC.read_text()

    def test_seconds_default_and_opt_out(self):
        self.assertIn("private boolean mShowSeconds = true;",self.src)
        self.assertIn("TunerService.parseIntegerSwitch(newValue, true)",self.src)
        self.assertIn('bundle.getBoolean(SHOW_SECONDS, true)',self.src)
        self.assertIn('is24 ? "Hms" : "hms"',self.src)

    def test_tick_scheduling_requires_attached_visible_display_on(self):
        schedule=self.src.split("private void scheduleSecondTick()",1)[1].split("private CharSequence getSmallTime()",1)[0]
        for term in ["handler.removeCallbacks(mSecondTick)", "mAttached", "mShowSeconds",
                     "isShown()", "getDisplay() != null",
                     "getDisplay().getState() == Display.STATE_ON"]:
            self.assertIn(term,schedule)

    def test_tick_never_dereferences_null_handler(self):
        tick=self.src.split("private final Runnable mSecondTick",1)[1]
        self.assertIn("mSecondsHandler == null || !mAttached",tick)
        self.assertIn("scheduleSecondTick();",tick)
        self.assertNotIn("mSecondsHandler.postAtTime(this,",tick)

    def test_screen_off_and_visibility_cancel(self):
        self.assertIn("protected void onVisibilityChanged(View changedView, int visibility)",self.src)
        self.assertIn("Intent.ACTION_SCREEN_OFF.equals(intent.getAction())",self.src)
        self.assertIn("mBroadcastDispatcher.unregisterReceiver(mScreenReceiver)",self.src)
        self.assertIn("mSecondsHandler.removeCallbacks(mSecondTick)",self.src)
        self.assertIn('new Handler(Looper.getMainLooper())',self.src)

if __name__=="__main__":unittest.main()
