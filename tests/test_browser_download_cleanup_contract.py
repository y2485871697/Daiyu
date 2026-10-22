"""Fixture failure-path wiring checks; real recreation runs in Android regression."""
from pathlib import Path
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/androidTest/kotlin/app/daiyu/android/BrowserDownloadSaveActivityTest.kt'


class BrowserDownloadCleanupContract(unittest.TestCase):
    def test_cleanup_does_not_retry_monitor_handoff_before_restoring_controller(self):
        source = SOURCE.read_text()
        cleanup = source.split('    private fun cleanupFixture() {', 1)[1].split('    private fun clearRecreationMonitor', 1)[0]
        self.assertNotIn('takeRecreatedActivity', cleanup)
        self.assertNotIn('waitForMonitorWithTimeout', cleanup)
        self.assertIn('step { if (::original.isInitialized) mainThread { application.controller = original } }', cleanup)
        self.assertNotIn('if (!destroyed) throw', cleanup)
        self.assertIn('if (cleanupCompleted) return', cleanup)
        self.assertLess(cleanup.index('failure?.let { throw it }'), cleanup.index('cleanupCompleted = true'))

    def test_monitor_finally_and_bounded_fixture_owned_activity_destruction(self):
        source = SOURCE.read_text()
        handoff = source.split('    private fun takeRecreatedActivity(', 1)[1].split('    private fun openListing()', 1)[0]
        self.assertIn('waitForMonitorWithTimeout(monitor, 10_000)', handoff)
        self.assertIn('finally { clearRecreationMonitor(monitor) }', handoff)
        self.assertIn('created.intent.getStringExtra("browser-save-fixture-owner") == activityToken', source)
        self.assertIn('if (cleanupRequested) created.finish()', source)
        self.assertIn('awaitControl("browser-save Activity destroyed") { fixtureActivities.all { it.isDestroyed } }', source)
        self.assertIn('application.unregisterActivityLifecycleCallbacks(activityCallbacks)', source)
        self.assertNotIn('if (recreationMonitor !== monitor)', source)

    def test_real_replacement_handoff_fault_retains_cleanup_assertions(self):
        source = SOURCE.read_text()
        test = source.split('    fun testCleanupOwnsReplacementWhenRecreationHandoffThrows()', 1)[1].split('    fun testObsoleteStatic', 1)[0]
        for assertion in ('recreateMain { throw injected }', 'assertSame(injected, failure)',
                          'assertSame(old, activity)', 'assertNull(recreationMonitor)',
                          'owned.any { it !== old }', 'owned.all { it.isDestroyed }',
                          'assertSame(original, application.controller)', 'assertFalse(root.exists())',
                          'assertEquals(0, modelCalls.get())'):
            self.assertIn(assertion, test)
        self.assertEqual(2, test.count('cleanupFixture()'))


if __name__ == '__main__':
    unittest.main()
