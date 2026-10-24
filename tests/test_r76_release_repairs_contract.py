"""R76 real-entry repair contracts; device acceptance remains a cloud gate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class R76ReleaseRepairsContractTest(unittest.TestCase):
    def test_offline_entry_uses_one_instance_readiness_and_factory(self):
        main = (APP / 'MainActivity.kt').read_text()
        host = (APP / 'voice/offline/OfflineComposerHost.kt').read_text()
        self.assertIn('ready = { offlineSpeechReady() }', main)
        self.assertIn('sessionFactory = { owner, allowed, events -> offlineSpeechSessionFactory(owner, allowed, events) }', main)
        self.assertIn('if (offlineSpeechReady()) startOfflineDictation()', main)
        self.assertIn('if (mode == ChatMenuAction.VOICE_INPUT) offlineSpeechReady()', main)
        self.assertIn('ready: () -> Boolean = { OfflineSpeechPack.state.value.ready }', host)
        self.assertIn('sessionFactory(owner, { inputEnabled() && ready() }, events)', host)
        self.assertIn('enabled = { inputEnabled() && ready() }', host)
        self.assertIn('OfflineSpeechSession(audioGate, owner, allowed,', main)
        self.assertIn('AndroidOfflineSpeechDevice(applicationContext)', main)

    def test_permission_result_consumes_original_owner_without_opening_audio(self):
        main = (APP / 'MainActivity.kt').read_text()
        host = (APP / 'voice/offline/OfflineComposerHost.kt').read_text()
        callback = main.split('private val offlineMicrophonePermission =', 1)[1].split('private val offlineHost:', 1)[0]
        self.assertIn('val original = offlineMicrophonePermissionOwner', callback)
        self.assertLess(callback.index('offlineMicrophonePermissionOwner = null'), callback.index('original?.permissionResult(granted)'))
        self.assertNotIn('offlineHost.', callback)
        result = host.split('fun permissionResult(granted: Boolean)', 1)[1].split('fun invalidate()', 1)[0]
        self.assertLess(result.index('pendingPermission = null'), result.index('pending.serial != attemptSerial'))
        for forbidden in ('factory(', '.start(', 'start(', 'applyDraft('):
            self.assertNotIn(forbidden, result)
        start = host.split('fun start(target:', 1)[1].split('val draft = SpeechDraft', 1)[0]
        self.assertLess(start.index('pendingPermission != null'), start.index('if (!permission())'))
        self.assertLess(start.index('pendingPermission = pending'), start.index('requestPermission()'))
        invalidate = host.split('fun invalidate()', 1)[1]
        self.assertIn('attemptSerial++', invalidate)
        self.assertNotIn('pendingPermission = null', invalidate)

    def test_offline_permission_regressions_keep_explicit_retry_and_late_result_fences(self):
        source = (ROOT / 'app/src/test/kotlin/app/daiyu/android/voice/offline/OfflineSpeechContractsTest.kt').read_text()
        for method in ('permissionNeverAutoStartsAndInvalidatedOrOtherEditorResultsCannotCommit',
                       'pendingPermissionCannotRelaunchOrBorrowAnotherEditorsGrant',
                       'permissionLaunchFailureReleasesPendingAndGrantStillRequiresExplicitRetry',
                       'latePermissionAfterInvalidationOrCloseDoesNotPublishOrStart'):
            self.assertIn('@Test fun ' + method + '()', source)
        self.assertIn('host.permissionResult(true)', source)
        self.assertIn('host.permissionResult(false)', source)
        self.assertIn('assertTrue(attempts.isEmpty())', source)

    def test_primary_button_reuses_assistant_palette_without_changing_accent(self):
        source = (APP / 'ui/components/subagent/ConversationAgentStyle.kt').read_text()
        self.assertIn('primaryColor: Color = assistantPreviewPalette().primary', source)
        self.assertNotIn('primaryColor: Color = conversationAgentColors().accent', source)
        self.assertIn('defaultMinSize(minHeight = 48.dp)', source)
        self.assertIn('.alpha(if (enabled) 1f else .4f)', source)


    def test_device_dictation_uses_real_offline_session_and_preserves_business_oracles(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/DictationActivityTest.kt').read_text()
        for token in ('OfflineSpeechSession(gate, owner, allowed, device,',
                      'object : OfflineSpeechSession.Events by events',
                      'events.captureStopped()', 'stoppedDelivered.countDown()',
                      'field(activity, "offlineSpeechSessionFactory", factory)',
                      'field(activity, "offlineSpeechReady", ready)',
                      'field(activity, "offlineMicrophonePermissionOwner", host)',
                      'activity_rq#4', 'activity.onRequestPermissionsResult',
                      'assertEquals(0, platform.creates)', 'assertEquals(0, fixture.opens.get())',
                      'assertDraft("leftmiddletail")', 'assertSelection(10, 10)',
                      'assertSame(processGate, gateField.get(activity))',
                      'assertFalse(speech.speak(speech.owner, "answer", "must not play"))'):
            self.assertIn(token, source)
        self.assertNotIn('SystemDictationPlatform', source)
        self.assertNotIn('getDictationHost', source)

    def test_fill_samples_same_row_without_relaxing_color_or_scrolling_assertions(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ConversationPaneVisualTest.kt').read_text()
        method = source.split('fun testSearchUsesTheSameGrayPurpleFillAsActionsBeforeAndAfterEditing()', 1)[1].split('\n    fun ', 1)[0]
        for token in ('pixels[leftX, y]', 'pixels[rightX, y]', 'pixels[x, y]',
                      'fill.compositeOver(background)', 'expected.red, actual.red, .015f',
                      'expected.green, actual.green, .015f', 'expected.blue, actual.blue, .015f'):
            self.assertIn(token, method)
        self.assertNotIn('top - 3 *', method)
        sizing = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatAttachmentSizingActivityTest.kt').read_text()
        self.assertNotIn('.performScrollTo()', sizing)
        self.assertIn('.scrollToVisible().assertIsDisplayed().assertHasClickAction()', sizing)
        self.assertIn('assertFalse("Text overflows:', sizing)
