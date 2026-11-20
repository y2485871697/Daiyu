"""New instrumentation may reuse an unchanged production app, never rebuild it."""
from pathlib import Path
import hashlib
import io
import json
import tempfile
import zipfile
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from verify_test_refresh import validate_changes, validate_graph, verify_archive, validate_build_group_only, VALIDATION_FILES


class TestRefreshContract(unittest.TestCase):
    def test_single_method_refresh_reuses_diagnostic_selector_and_exact_runner(self):
        workflow = (ROOT / '.github/workflows/test-refresh-regression.yml').read_text()
        self.assertIn("      test_method:\n", workflow)
        self.assertIn("  SMOKE_TEST_METHOD: ${{ inputs.test_method }}", workflow)
        self.assertIn("  SMOKE_DIAGNOSTIC: ${{ inputs.test_method != '' && 'true' || '' }}", workflow)
        self.assertIn("shard: ${{ fromJSON(inputs.test_method != '' && '[0]' ||", workflow)
        self.assertIn("SMOKE_SHARD_COUNT: ${{ inputs.test_method != '' && '1' ||", workflow)
        self.assertEqual(2, workflow.count('test "$SELECTED_API" = 26 || test "$SELECTED_API" = 37.2'))
        self.assertEqual(2, workflow.count('test "$SELECTED_SHARD" = all || test "$SELECTED_SHARD" = 0'))
        self.assertEqual(2, workflow.count("selected = diagnostic_selection(Path('.'), os.environ)"))
        self.assertEqual(2, workflow.count("assert selected and selected['method'] and selected['expected_tests'] == 1"))
        self.assertIn("formal_acceptance=False, full_regression_passed=False", workflow)
        self.assertIn("if: always() && inputs.test_method != ''", workflow)
        self.assertEqual(2, workflow.count("if: always() && inputs.test_method != ''"))
        self.assertIn("phase='test-package'", workflow)
        self.assertIn("shutil.copy2(selection, root / selection.name)", workflow)
        self.assertIn("SMOKE_TEST_METHOD: ''", workflow.split('  test-package:', 1)[0])
        script = (ROOT / 'scripts/smoke_boot.sh').read_text()
        self.assertIn('diagnostic_selection(Path("."), os.environ)', script)
        self.assertIn('result_options=(--expected-target "$test_classes")', script)
        self.assertIn("python3 scripts/verify_test_refresh.py", workflow)
        self.assertIn(":app:assembleReleaseAndroidTest", workflow)
        self.assertNotIn(":app:assembleRelease\n", workflow)

    def test_inactive_append_failure_probe_preserves_original_wait_and_tick(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatResumeScrollTest.kt').read_text()
        method = source.split('fun testInactiveTerminalAppendIsPresentationReadyBeforeReveal()', 1)[1].split('\n    fun ', 1)[0]
        self.assertEqual(4, method.count('ui.waitUntil(5_000)'))
        self.assertEqual(2, method.count('ui.mainClock.advanceTimeByFrame()'))
        self.assertIn('val appended = first + "后".repeat(80)', method)
        self.assertIn('paintProbe.Capture(replyId)', method)
        self.assertIn('ticksStarted - startTicks', method)
        self.assertIn('ticksFinished - finishTicks', method)
        self.assertIn('failure.addSuppressed(AssertionError("W3 original wait failed:', method)
        self.assertIn('throw failure', method)
        self.assertIn('current in previous..minOf(previous + 1, appended.length)', method)
        self.assertIn('current == appended.length && sample.hasCopy && completed.get() > 0', method)
        sample = method.split('fun presentationSample(): PresentationSample {', 1)[1].split('fun assertNotCompleted', 1)[0]
        self.assertEqual(1, sample.count('.fetchSemanticsNodes()'))
        self.assertIn('useUnmergedTree = true', sample)
        self.assertIn('.maxOf { it.length }', sample)
        self.assertIn('nodes.any(copyMatcher::matches)', sample)
        self.assertIn('nodes.any(appendedMatcher::matches)', sample)
        self.assertIn('ui.waitForIdle()', method.split('fun tick()', 1)[1].split('fun hasActions()', 1)[0])
        self.assertIn('assertNotCompleted(sample)', method)
        self.assertIn('sample.hasAppended', method)
        tick = method.split('fun tick()', 1)[1].split('fun hasActions()', 1)[0]
        self.assertEqual(1, tick.count('ui.mainClock.advanceTimeByFrame()'))
        sampled = method.split('fun sampledTick(): PresentationSample {', 1)[1].split('fun assertNotCompleted', 1)[0]
        self.assertEqual(1, sampled.count('ui.mainClock.advanceTimeByFrame()'))
        self.assertEqual(1, sampled.count('presentationSample()'))
        self.assertNotIn('waitForIdle', sampled)
        order = [sampled.index(value) for value in ('ticksStarted++', 'advanceTimeByFrame()',
                 'val sample = presentationSample()', 'ticksFinished++', 'return sample')]
        self.assertEqual(sorted(order), order)
        before_w3 = method.split('val startTicks = ticksStarted', 1)[0].split('        try {', 1)[1]
        self.assertEqual(3, before_w3.count('tick()'))
        self.assertNotIn('sampledTick()', before_w3)
        w3 = method.split('val startTicks = ticksStarted', 1)[1].split('} catch (failure:', 1)[0]
        self.assertEqual(1, w3.count('sampledTick()'))
        self.assertNotIn('tick()', w3)
        self.assertNotIn('presentationSample()', w3)
        self.assertIn('onPresentationReady = { presentationReady = it }', method)
        self.assertIn('!awaitingAppend || releaseReady && presentationReady', method)
        self.assertIn('ui.onNodeWithText(appended).assertExists()', method)


    def test_video_picker_failed_shards_have_independent_non_cancelling_queues(self):
        workflow = (ROOT / '.github/workflows/test-refresh-regression.yml').read_text()
        concurrency = workflow.split('\nconcurrency:\n', 1)[1].split('\nenv:', 1)[0]
        lines = concurrency.splitlines()
        group = [line for line in lines if line.startswith('  group: ')]
        self.assertEqual(1, len(group))
        self.assertTrue(group[0].startswith('  group: daiyu-test-refresh-${{ github.ref }}'))
        self.assertEqual(1, group[0].count("${{ inputs.smoke_classes == 'app.daiyu.android.TextSendActivityTest,app.daiyu.android.ChatReferenceEntryActivityTest,app.daiyu.android.ui.screens.chat.attachments.VideoAttachmentsUiTest' && format('-video-picker-{0}', inputs.smoke_shard) || '' }}"))
        self.assertEqual(['  cancel-in-progress: false'],
                         [line for line in lines if line.startswith('  cancel-in-progress:')])

    def test_user_bubble_refresh_has_its_own_non_cancelling_queue(self):
        workflow = (ROOT / '.github/workflows/test-refresh-regression.yml').read_text()
        concurrency = workflow.split('\nconcurrency:\n', 1)[1].split('\nenv:', 1)[0]
        lines = concurrency.splitlines()
        group = [line for line in lines if line.startswith('  group: ')]
        self.assertEqual(1, len(group))
        self.assertTrue(group[0].startswith('  group: daiyu-test-refresh-${{ github.ref }}'))
        self.assertEqual(1, group[0].count("${{ inputs.smoke_classes == 'app.daiyu.android.ui.screens.chat.conversation.UserMessageEntranceTest,app.daiyu.android.TextSendActivityTest,app.daiyu.android.ChatSubmissionActivityTest,app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest' && '-user-bubble' || '' }}"))
        self.assertEqual(['  cancel-in-progress: false'],
                         [line for line in lines if line.startswith('  cancel-in-progress:')])

    def test_doubao_motion_refresh_has_its_own_non_cancelling_queue(self):
        workflow = (ROOT / '.github/workflows/test-refresh-regression.yml').read_text()
        concurrency = workflow.split('\nconcurrency:\n', 1)[1].split('\nenv:', 1)[0]
        lines = concurrency.splitlines()
        group = [line for line in lines if line.startswith('  group: ')]
        self.assertEqual(1, len(group))
        self.assertTrue(group[0].startswith('  group: daiyu-test-refresh-${{ github.ref }}'))
        self.assertEqual(1, group[0].count("${{ inputs.smoke_classes == 'app.daiyu.android.VoiceCallFloatingWindowTest,app.daiyu.android.ChatComposerImeActivityTest' && '-doubao-motion' || '' }}"))
        self.assertEqual(['  cancel-in-progress: false'],
                         [line for line in lines if line.startswith('  cancel-in-progress:')])

    def test_workflow_collapse_refresh_has_its_own_non_cancelling_queue(self):
        workflow = (ROOT / '.github/workflows/test-refresh-regression.yml').read_text()
        concurrency = workflow.split('\nconcurrency:\n', 1)[1].split('\nenv:', 1)[0]
        lines = concurrency.splitlines()
        group = [line for line in lines if line.startswith('  group: ')]
        self.assertEqual(1, len(group))
        self.assertTrue(group[0].startswith('  group: daiyu-test-refresh-${{ github.ref }}'))
        self.assertEqual(1, group[0].count("${{ inputs.smoke_classes == 'app.daiyu.android.ui.screens.chat.conversation.WorkflowDisclosureDirectionTest,app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest' && '-workflow-collapse' || '' }}"))
        self.assertEqual(['  cancel-in-progress: false'],
                         [line for line in lines if line.startswith('  cancel-in-progress:')])

    def test_task_scroll_complement_refresh_has_its_own_non_cancelling_queue(self):
        workflow = (ROOT / '.github/workflows/test-refresh-regression.yml').read_text()
        concurrency = workflow.split('\nconcurrency:\n', 1)[1].split('\nenv:', 1)[0]
        lines = concurrency.splitlines()
        group = [line for line in lines if line.startswith('  group: ')]
        self.assertEqual(1, len(group))
        self.assertTrue(group[0].startswith('  group: daiyu-test-refresh-${{ github.ref }}'))
        self.assertEqual(1, group[0].count("${{ inputs.smoke_classes == 'app.daiyu.android.ui.screens.chat.conversation.ChatMessageSpacingTest,app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningTypingTest,app.daiyu.android.ui.screens.chat.conversation.MarkdownRevealTraversalTest' && '-task-scroll-complement' || '' }}"))
        self.assertIn('  cancel-in-progress: false', lines)

    def test_workspace_gesture_refresh_has_its_own_non_cancelling_queue(self):
        workflow = (ROOT / '.github/workflows/test-refresh-regression.yml').read_text()
        concurrency = workflow.split('\nconcurrency:\n', 1)[1].split('\nenv:', 1)[0]
        lines = concurrency.splitlines()
        group = [line for line in lines if line.startswith('  group: ')]
        self.assertEqual(1, len(group))
        self.assertTrue(group[0].startswith('  group: daiyu-test-refresh-${{ github.ref }}'))
        self.assertIn("${{ inputs.smoke_classes == 'app.daiyu.android.ConversationWorkspaceActivityTest' && '-workspace-gesture' || '' }}", group[0])
        self.assertIn('  cancel-in-progress: false', lines)

    def test_video_controls_refresh_uses_only_shard_zero(self):
        workflow = (ROOT / '.github/workflows/test-refresh-regression.yml').read_text()
        smoke = workflow.split('\n  smoke:\n', 1)[1]
        matrix = smoke.split('\n      matrix:\n', 1)[1].split('\n    steps:\n', 1)[0]
        self.assertIn(
            "        shard: ${{ fromJSON(inputs.test_method != '' && '[0]' || inputs.smoke_classes == "
            "'app.daiyu.android.ChatMediaPreviewVideoControlsUiTest' && '[0]' || "
            "inputs.smoke_shard != 'all' && format('[{0}]', inputs.smoke_shard) || "
            "'[0,1,2,3]') }}",
            matrix)
        self.assertIn(
            "        api: ${{ fromJSON(inputs.smoke_api == '26' && '[\"26\"]' || "
            "inputs.smoke_api == '37.2' && '[\"37.2\"]' || '[\"26\",\"37.2\"]') }}",
            matrix)
        emulator = smoke.split('      - name: 安装并检查启动\n', 1)[1]
        count = next(line.strip() for line in emulator.splitlines()
                     if line.strip().startswith('SMOKE_SHARD_COUNT: ${{'))
        self.assertTrue(count.startswith(
            "SMOKE_SHARD_COUNT: ${{ inputs.test_method != '' && '1' || (inputs.smoke_classes == "
            "'app.daiyu.android.ChatMediaPreviewVideoControlsUiTest' || "))
        self.assertTrue(count.endswith("&& '1' || '4' }}"))

    def build_group_fixture(self):
        before = (b'name: build\nconcurrency:\n'
                  b'  group: daiyu-${{ inputs.mode }}-${{ github.ref }}\n'
                  b'  cancel-in-progress: false\njobs:\n  build:\n'
                  b'    env: {SIGNING: fixed}\n    steps:\n      - run: ./gradlew :app:assembleRelease\n')
        after = before.replace(b'${{ github.ref }}\n',
                               b"${{ github.ref }}${{ inputs.mode == 'smoke' && '-conversation-date' || '' }}\n")
        return before, after

    def test_build_workflow_only_accepts_a_reviewed_queue_expression_append(self):
        before, after = self.build_group_fixture()
        validate_build_group_only(before, after)
        validate_changes(['.github/workflows/build.yml'], build_pair=(before, after))
        self.assertNotIn('.github/workflows/build.yml', VALIDATION_FILES)
        with self.assertRaises(ValueError):
            validate_changes(['.github/workflows/build.yml'])

    def test_build_workflow_accepts_only_the_reviewed_smoke_tool_route_append(self):
        line = b'          script: bash -c \'set +e; smoke_script=scripts/smoke_boot.sh; test -s smoke/prelaunch-ready.txt && bash "$smoke_script"; code=$?; printf "%s\\n" "$code" > smoke/exit-code.txt; exit "$code"\'\n'
        route = b' if test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "app.daiyu.android.ui.screens.chat.conversation.ChatMessageSpacingTest,app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningTypingTest,app.daiyu.android.ui.screens.chat.conversation.MarkdownRevealTraversalTest"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi;'
        before, grouped = self.build_group_fixture()
        before += line
        route_only = before.replace(line, line.replace(b' test -s ', route + b' test -s ', 1))
        validate_changes(['.github/workflows/build.yml'], build_pair=(before, route_only))
        grouped += line.replace(b' test -s ', route + b' test -s ', 1)
        validate_changes(['.github/workflows/build.yml'], build_pair=(before, grouped))
        for invalid in (
                grouped.replace(b'= smoke &&', b'= compile &&'),
                grouped.replace(b'ChatMessageSpacingTest,', b'OtherTest,'),
                grouped.replace(b'.smoke-validation-tools/scripts/smoke_boot.sh', b'scripts/other.sh'),
                grouped.replace(b'code=$?;', b'code=0;'),
                grouped.replace(b'test -s smoke/prelaunch-ready.txt', b'true'),
                grouped.replace(b'exit "$code"', b'exit 0'),
                grouped.replace(b'SIGNING: fixed', b'SIGNING: changed'),
                grouped.replace(route, route + route),
                grouped.replace(b'          script: bash -c ', b'          # script: bash -c '),
                route_only.replace(route, b'').replace(b' code=$?;', route + b' code=$?;', 1)):
            with self.subTest(workflow=invalid), self.assertRaises(ValueError):
                validate_changes(['.github/workflows/build.yml'], build_pair=(before, invalid))
        comment_before = before.replace(b'          script: bash -c ', b'          # script: bash -c ')
        comment_after = grouped.replace(b'          script: bash -c ', b'          # script: bash -c ')
        with self.assertRaises(ValueError):
            validate_changes(['.github/workflows/build.yml'], build_pair=(comment_before, comment_after))
        inline_before = before.replace(b' test -s ', b' # retained test -s ', 1)
        inline_after = inline_before.replace(b' test -s ', route + b' test -s ', 1)
        with self.assertRaises(ValueError):
            validate_changes(['.github/workflows/build.yml'], build_pair=(inline_before, inline_after))
        with self.assertRaises(ValueError):
            validate_changes(['.github/workflows/build.yml'],
                             build_pair=(before + line, route_only + line.replace(b' test -s ', route + b' test -s ', 1)))

    def test_build_workflow_accepts_only_exact_core_route_and_cumulative_reviewed_routes(self):
        from verify_test_refresh import validate_build_validation_changes
        line = b'          script: bash -c \'set +e; smoke_script=scripts/smoke_boot.sh; test -s smoke/prelaunch-ready.txt && bash "$smoke_script"; code=$?; printf "%s\\n" "$code" > smoke/exit-code.txt; exit "$code"\'\n'
        previous = b' if test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "app.daiyu.android.ui.screens.chat.conversation.ChatMessageSpacingTest,app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningTypingTest,app.daiyu.android.ui.screens.chat.conversation.MarkdownRevealTraversalTest"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi;'
        core = b' if test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "app.daiyu.android.QuestionBarrierIntegrationTest,app.daiyu.android.ConversationPaneSwitchActivityTest,app.daiyu.android.ui.screens.chat.conversation.MessageActionBarLayoutTest"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi;'
        before, grouped = self.build_group_fixture()
        before += line
        for base in (before, before.replace(b' test -s ', previous + b' test -s ', 1)):
            after = base.replace(b' test -s ', core + b' test -s ', 1)
            validate_build_validation_changes(base, after)
            validate_changes(['.github/workflows/build.yml'], build_pair=(base, after))
            for invalid in (
                    after.replace(core, core + core),
                    after.replace(core, core.replace(b'= smoke &&', b'= compile &&')),
                    after.replace(core, core.replace(b'QuestionBarrierIntegrationTest,', b'UnknownTest,')),
                    after.replace(core, core.replace(b'.smoke-validation-tools/scripts/smoke_boot.sh', b'scripts/other.sh')),
                    after.replace(b'code=$?;', b'code=0;'),
                    after.replace(b'test -s smoke/prelaunch-ready.txt', b'true'),
                    after.replace(b'SIGNING: fixed', b'SIGNING: changed')):
                with self.subTest(workflow=invalid), self.assertRaises(ValueError):
                    validate_build_validation_changes(base, invalid)
        cumulative = grouped + line.replace(b' test -s ', previous + core + b' test -s ', 1)
        validate_changes(['.github/workflows/build.yml'], build_pair=(before, cumulative))

    def test_build_workflow_accepts_multiple_queue_expression_appends_only(self):
        before, after = self.build_group_fixture()
        tail = b"${{ inputs.mode == 'smoke' && '-timeline-divider' || '' }}"
        group = next(line for line in after.splitlines(keepends=True) if line.startswith(b'  group: '))
        multiple = after.replace(group, group[:-1] + tail + b'\n')
        validate_build_group_only(before, multiple)
        validate_changes(['.github/workflows/build.yml'], build_pair=(before, multiple))
        validate_build_group_only(after, multiple)
        for suffix in (b'UNREVIEWED' + tail, b' ' + tail, b'\n' + tail,
                       b'\r' + tail, b'${{ nested { invalid } }}' + tail):
            damaged = after.replace(group, group[:-1] + suffix + b'\n')
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                validate_build_group_only(before, damaged)
        for damaged in (multiple.replace(b'inputs.mode', b'inputs.changed', 1),
                        multiple.replace(b'assembleRelease', b'assembleDebug'),
                        multiple.replace(b'SIGNING: fixed', b'SIGNING: other'),
                        multiple.replace(b'cancel-in-progress: false', b'cancel-in-progress: true')):
            with self.subTest(damaged=damaged), self.assertRaises(ValueError):
                validate_build_group_only(before, damaged)

    def test_queue_exception_cannot_change_build_commands_signing_or_other_bytes(self):
        before, after = self.build_group_fixture()
        for damaged in (after.replace(b'assembleRelease', b'assembleDebug'),
                        after.replace(b'SIGNING: fixed', b'SIGNING: other'),
                        after.replace(b'cancel-in-progress: false', b'cancel-in-progress: true'),
                        after.replace(b'name: build', b'name: changed'),
                        after.replace(b'jobs:', b'# extra comment\njobs:'),
                        after.replace(b'\n', b'\r\n'), after.rstrip(b'\n')):
            with self.subTest(damaged=damaged), self.assertRaises(ValueError):
                validate_build_group_only(before, damaged)

    def test_queue_exception_rejects_missing_or_ambiguous_group_sections(self):
        before, after = self.build_group_fixture()
        for text in (before, after):
            lines = text.splitlines(keepends=True)
            group = next(line for line in lines if line.startswith(b'  group: '))
            for damaged in (text.replace(group, b''), text.replace(group, group + group),
                            text.replace(b'concurrency:\n', b'concurrency:\nconcurrency:\n'),
                            text.replace(b'concurrency:\n', b'')):
                for pair in ((damaged, after), (before, damaged)):
                    with self.subTest(pair=pair), self.assertRaises(ValueError):
                        validate_build_group_only(*pair)
        for damaged in (after.replace(b'daiyu-', b'other-'), before,
                        before.replace(b'${{ github.ref }}\n',
                                       b'${{ github.ref }}${{ true }}UNREVIEWED${{ false }}\n'),
                        after.replace(b" && '-conversation-date' || '' }}", b' invalid-suffix')):
            with self.subTest(damaged=damaged), self.assertRaises(ValueError):
                validate_build_group_only(before, damaged)

    def test_valid_queue_exception_never_allows_other_application_inputs(self):
        before, after = self.build_group_fixture()
        for forbidden in ('app/src/main/Example.kt', 'app/build.gradle.kts', 'settings.gradle.kts',
                          'signing.properties', '.github/workflows/unknown.yml'):
            for names in (['.github/workflows/build.yml', forbidden], [forbidden, '.github/workflows/build.yml']):
                with self.subTest(names=names), self.assertRaises(ValueError):
                    validate_changes(names, build_pair=(before, after))

    def test_only_reviewed_validation_paths_are_allowed(self):
        validate_changes(['app/src/androidTest/kotlin/app/daiyu/android/ConversationPaneSwitchActivityTest.kt',
                          'app/src/test/kotlin/ExampleTest.kt', 'tests/example.py',
                          'core-domain/src/test/kotlin/app/daiyu/android/domain/delegation/RetainedChildTasksPauseTest.kt',
                          'docs/重构清单.md', '.github/workflows/test-refresh-regression.yml'])

    def test_generation_checks_metadata_is_an_exact_validation_path(self):
        path = '.github/workflows/generation-port-checks.yml'
        validate_changes([path, 'tests/example.py'])
        for forbidden in (path + '.bak', path + '/extra',
                          '.github/workflows/unknown-generation-checks.yml',
                          '.github/workflows/build.yml', 'app/build.gradle.kts',
                          'settings.gradle.kts', 'app/src/main/Example.kt'):
            for changes in ([path, forbidden], [forbidden, path]):
                with self.subTest(changes=changes), self.assertRaises(ValueError):
                    validate_changes(changes)

    def test_chat_checks_metadata_does_not_allow_other_workflows(self):
        validate_changes(['.github/workflows/compression-dialog-checks.yml',
                          '.github/workflows/chat-workflow-checks.yml',
                          '.github/workflows/popup-layout-checks.yml',
                          '.github/workflows/reconnect-details-checks.yml', 'scripts/smoke_boot.sh',
                          'scripts/acceptance/record_tool_tests.py', 'scripts/verify_smoke_source.py'])
        for name in ('.github/workflows/build.yml', '.github/workflows/unknown-checks.yml',
                     '.github/workflows/compression-dialog-checks.yml.bak',
                     '.github/workflows/compression-dialog-checks.yml/extra',
                     '.github/workflows/chat-workflow-checks.yml.bak', '.github/actions/example/action.yml'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_changes([name])
        with self.assertRaises(ValueError):
            validate_changes(['.github/workflows/chat-workflow-checks.yml', '.github/workflows/build.yml'])

    def test_workspace_check_metadata_is_exactly_allowlisted(self):
        allowed = ('.github/workflows/child-task-card-platform.yml',
                   '.github/workflows/workspace-delete-checks.yml',
                   '.github/workflows/workspace-recovery-platform.yml')
        for path in allowed:
            with self.subTest(allowed=path):
                validate_changes([path])
            for invalid in (path + '.bak', path + '/extra',
                            path.replace('/workflows/', '/workflows/../workflows/')):
                with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                    validate_changes([invalid])
            for forbidden in ('.github/workflows/build.yml', '.github/workflows/unknown-checks.yml',
                              'app/src/main/Example.kt', 'app/build.gradle.kts', 'settings.gradle.kts'):
                for changes in ([path, forbidden], [forbidden, path]):
                    with self.subTest(changes=changes), self.assertRaises(ValueError):
                        validate_changes(changes)
        validate_changes([*allowed, 'tests/example.py'])

    def test_smoke_runner_is_an_exact_validation_path_not_a_scripts_wildcard(self):
        path = 'scripts/smoke_boot.sh'
        self.assertIn(path, VALIDATION_FILES)
        validate_changes([path])
        validate_changes([path, 'app/src/androidTest/kotlin/ExampleTest.kt'])
        for invalid in (path + '.bak', path + '/extra', 'scripts/./smoke_boot.sh',
                        'scripts/../scripts/smoke_boot.sh', 'scripts//smoke_boot.sh',
                        'scripts\\smoke_boot.sh', '/scripts/smoke_boot.sh', 'scripts/unknown.sh'):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                validate_changes([invalid])
        for forbidden in ('app/src/main/Example.kt', 'core-domain/src/main/Example.kt',
                          'app/build.gradle.kts', 'settings.gradle.kts',
                          'scripts/prepare-speech-runtime.py', '.github/workflows/build.yml'):
            for changed in ([path, forbidden], [forbidden, path]):
                with self.subTest(changed=changed), self.assertRaises(ValueError):
                    validate_changes(changed)

    def test_application_build_and_ambiguous_paths_fail_closed(self):
        for name in ('app/src/main/Example.kt', 'app/build.gradle.kts', 'settings.gradle.kts',
                     'scripts/prepare-speech-runtime.py', '.github/workflows/build.yml',
                     'scripts/acceptance/unknown.py', 'scripts/verify_smoke_source.py.bak',
                     '.github/workflows/popup-layout-checks.yml.bak',
                     '.github/workflows/reconnect-details-checks.yml.bak', 'scripts/smoke_boot.sh.bak',
                     'scripts/unknown.sh', '.github/workflows/reconnect-details-checks.yml/extra',
                     'app/src/androidTest/../main/Example.kt', 'tests//a', 'tests\\a', '/tests/a',
                     'core-domain/src/main/kotlin/Example.kt', 'core-domain/build.gradle.kts',
                     'core-domain/src/test/../main/Example.kt', 'core-domain/src/testExtra/Example.kt'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_changes([name])

        with self.assertRaises(ValueError):
            validate_changes(['core-domain/src/test/kotlin/ExampleTest.kt',
                              'core-domain/src/main/kotlin/Example.kt'])

    def test_frozen_test_revision_keeps_workflow_and_reuse_identity_separate(self):
        workflow = (ROOT / '.github/workflows/test-refresh-regression.yml').read_text()
        package = workflow.split('  test-package:', 1)[1].split('  smoke:', 1)[0]
        self.assertIn('ref: ${{ inputs.expected_sha }}', package)
        self.assertIn('fetch-depth: 0', package)
        self.assertIn('test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"', package)
        for action in ('source', 'bind'):
            self.assertIn('GITHUB_SHA="$EXPECTED_SHA" python3 scripts/verify_test_refresh.py ' + action, package)
        self.assertIn("Path('test-refresh/execution-source.json')", package)
        self.assertIn("workflow_sha=os.environ['GITHUB_SHA'], checked_sha=os.environ['EXPECTED_SHA']", package)
        smoke = workflow.split('  smoke:', 1)[1]
        self.assertLess(smoke.index("owner = dict(workflow_sha=env['GITHUB_SHA']"),
                        smoke.index("env['GITHUB_SHA'] = env['EXPECTED_SHA']"))
        self.assertLess(smoke.index("env['GITHUB_SHA'] = env['EXPECTED_SHA']"), smoke.index('if source_run:'))
        self.assertIn("assert run['head_sha'] == pair['test_sha']", smoke)
        self.assertIn("assert artifact['workflow_run']['head_sha'] == pair['test_sha']", smoke)
        self.assertIn("env.update(GITHUB_SHA=pair['test_sha'], GITHUB_RUN_ID=source_run, GITHUB_RUN_ATTEMPT=attempt)", smoke)
        self.assertIn('export GITHUB_SHA="$EXPECTED_SHA"', smoke)

    def test_task_graph_and_artifact_boundaries(self):
        workflow = (ROOT / '.github/workflows/test-refresh-regression.yml').read_text()
        self.assertIn(':app:assembleReleaseAndroidTest', workflow)
        self.assertNotIn(':app:assembleRelease ', workflow)
        self.assertIn('test_refresh_only.init.gradle', workflow)
        self.assertIn('verify_test_refresh.py source', workflow)
        self.assertIn('verify_test_refresh.py bind', workflow)
        self.assertIn("subprocess.check_call(['python3', 'scripts/verify_test_refresh.py', 'check'], env=env)", workflow)
        self.assertIn('source-delivery', workflow)
        self.assertIn("SMOKE_SHARD_COUNT: ${{ inputs.test_method != '' && '1' || (inputs.smoke_classes == "
                      "'app.daiyu.android.ChatMediaPreviewVideoControlsUiTest' || inputs.smoke_classes == "
                      "'app.daiyu.android.VoiceCallFloatingWindowTest' || "
                      "(inputs.smoke_classes == 'app.daiyu.android.ui.screens.chat.conversation.ToolOperationCardTest' "
                      "&& inputs.smoke_shard == '0')) && '1' || '4' }}", workflow)
        guard = (ROOT / 'scripts/cooperation/test_refresh_only.init.gradle').read_text()
        self.assertIn("!t.name.toLowerCase().contains('androidtest')", guard)
        self.assertIn('install|uninstall|connected|managedDevice', guard)

    def test_workspace_delete_refreshes_only_test_apk_with_exact_sources(self):
        workflow = (ROOT / '.github/workflows/workspace-delete-checks.yml').read_text()
        self.assertIn('needs: test-package', workflow)
        self.assertIn('ref: ${{ inputs.expected_sha }}', workflow)
        self.assertIn('run-id: ${{ inputs.source_run_id }}', workflow)
        self.assertIn('run-id: ${{ github.run_id }}', workflow)
        self.assertIn('name: 代鱼测试刷新执行包', workflow)
        for action in ('source', 'bind', 'check'):
            self.assertIn('scripts/verify_test_refresh.py ' + action, workflow)
        self.assertIn('-I scripts/cooperation/test_refresh_only.init.gradle', workflow)
        self.assertIn(':app:assembleReleaseAndroidTest', workflow)
        self.assertNotIn(':app:assembleRelease ', workflow)
        self.assertNotIn('test "$SOURCE_SHA" = "$EXPECTED_SHA"', workflow)
        self.assertIn('SMOKE_SHARD_COUNT: \'1\'', workflow)
        self.assertIn('app.daiyu.android.WorkspaceEntryActionsActivityTest,app.daiyu.android.PrivateWorkspacePanelTest', workflow)
        self.assertIn("api: '26'", workflow)
        self.assertIn("api: '37.2'", workflow)

    def test_only_androidtest_packaging_is_allowed(self):
        validate_graph([':app:compileReleaseKotlin', ':app:packageReleaseResources',
                        ':app:packageReleaseAndroidTest', ':app:assembleReleaseAndroidTest'])
        for task in (':app:assembleRelease', ':app:packageRelease', ':app:bundleRelease',
                     ':app:signRelease', ':app:installReleaseAndroidTest', ':app:connectedReleaseAndroidTest'):
            with self.subTest(task=task), self.assertRaises(ValueError):
                validate_graph([':app:assembleReleaseAndroidTest', task])
        with self.assertRaises(ValueError):
            validate_graph([':app:compileReleaseAndroidTestKotlin'])

    def test_archive_binds_old_application_without_rejecting_new_instrumentation(self):
        app = '代鱼-0.1.0-12345678.apk'
        contents = {app: b'application', 'tests/platform-test.apk': b'old test'}
        record = {'sha256': {name: hashlib.sha256(data).hexdigest() for name, data in contents.items()}}
        record_bytes = json.dumps(record).encode()
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as package:
            package.writestr('构建记录.json', record_bytes)
            for name, data in contents.items():
                package.writestr(name, data)
        archive = buffer.getvalue()
        artifact = {'id': 123, 'digest': 'sha256:' + hashlib.sha256(archive).hexdigest()}
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            record_path = directory / '应用原始构建记录.json'
            record_path.write_bytes(record_bytes)
            (directory / app).write_bytes(contents[app])
            (directory / 'tests').mkdir()
            (directory / 'tests/platform-test.apk').write_bytes(b'new test')
            verify_archive(artifact, directory, record_path, record, archive, local_apks={app})
            with self.assertRaises(ValueError):
                verify_archive(artifact, directory, record_path, record, archive)
            (directory / app).write_bytes(b'changed app')
            with self.assertRaises(ValueError):
                verify_archive(artifact, directory, record_path, record, archive, local_apks={app})
            (directory / app).unlink()
            with self.assertRaises(ValueError):
                verify_archive(artifact, directory, record_path, record, archive, local_apks={app})
