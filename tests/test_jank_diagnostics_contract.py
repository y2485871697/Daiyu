from pathlib import Path
import sys
import re
import unittest
import subprocess
import tempfile
import os
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_release_package as package
from test_release_package import APP as XML, TEST

class JankDiagnosticsContract(unittest.TestCase):
    def test_diagnostic_manifest_cannot_pass_as_ordinary_release(self):
        xml = XML.replace('</application>', '<profileable android:shell="true"/><meta-data android:name="app.daiyu.android.JANK_DIAGNOSTICS" android:value="true"/></application>')
        package.verify_manifests(xml, TEST, diagnostic=True)
        with self.assertRaises(ValueError): package.verify_manifests(xml, TEST)
        with self.assertRaises(ValueError): package.verify_manifests(XML, TEST, diagnostic=True)
        with self.assertRaises(ValueError): package.verify_manifests(xml.replace('shell="true"', 'shell="false"'), TEST, diagnostic=True)
        with self.assertRaises(ValueError): package.verify_manifests(xml.replace('value="true"', 'value="false"'), TEST, diagnostic=True)

    def test_build_is_opt_in_release_not_debug_or_synthetic(self):
        s=(ROOT/'app/build.gradle.kts').read_text()
        self.assertIn('getOrElse(false)', s.split('val daiyuJankDiagnostics',1)[1])
        self.assertIn('isDebuggable = false', s)
        self.assertIn('isProfileable = daiyuPhysicalPerformance || daiyuJankDiagnostics',s)
        self.assertIn('!daiyuJankDiagnostics || (!daiyuSyntheticApplication && !daiyuPhysicalPerformance)',s)
        workflow=(ROOT/'.github/workflows/build.yml').read_text()
        self.assertIn("test \"$JANK_DIAGNOSTICS\" != true || test \"$SMOKE_MODE\" = compile",workflow)
        self.assertIn('-PdaiyuJankDiagnostics=true :app:assembleRelease',workflow)
        self.assertIn("'代鱼诊断Release包'",workflow)
        self.assertIn('production_delivery=not diagnostic',(ROOT/'scripts/verify_release_package.py').read_text())

    def test_one_existing_window_collector_and_independent_diagnostic_gate(self):
        s=(APP/'diagnostics/WindowFramePerformance.kt').read_text()
        self.assertEqual(1,s.count('addOnFrameMetricsAvailableListener(session.listener, target)'))
        self.assertIn('if (epoch == null && !diagnostic)',s)
        self.assertIn('if (diagnostic) incident.add(page, frame)',s)
        self.assertIn('host.diagnostic || (state.loaded && state.enabled && !state.unavailable)',s)
        self.assertIn('session.incident.flush()',s)
        self.assertIn('removeOnFrameMetricsAvailableListener',s)

    def test_interaction_observer_reads_only_discrete_phases(self):
        s=(APP/'diagnostics/WindowFramePerformance.kt').read_text().split('internal fun ObserveDiagnosticInteraction',1)[1]
        self.assertIn('snapshotFlow { current.value() }',s)
        self.assertIn('BuildConfig.JANK_DIAGNOSTICS) return',s)
        self.assertIn('finally',s)
        self.assertIn('JankDiagnostics.endPhase',s)
        self.assertIn('JankDiagnostics.beginPhase',s)
        self.assertIn('incident.checkpoint()', (APP/'diagnostics/WindowFramePerformance.kt').read_text())
        for path,kind in [('ui/screens/chat/conversation/ChatMenuLayer.kt','menu'),('ui/screens/chat/conversation/ChatMessageList.kt','chat-scroll'),('ui/navigation/PageBackGestures.kt','navigation'),('ui/components/DaiyuDropdownMenu.kt','popup'),('ui/components/ConversationSidePaneScaffold.kt','drawer')]:
            source=(APP/path).read_text()
            self.assertIn('ObserveDiagnosticInteraction("'+kind+'")',source)
            marker='ObserveDiagnosticInteraction("'+kind+'")'
            prefix,tail=source.split(marker,1)
            indent=re.match(r'[ \t]*',prefix.rsplit('\n',1)[-1]).group()
            block=re.split(r'(?m)^'+re.escape(indent)+r'\}',tail,maxsplit=1)[0]
            self.assertNotIn('fraction.value',block)
            self.assertNotIn('firstVisibleItemScrollOffset',block)

    def test_incident_is_bounded_and_off_callback_disk_io(self):
        s=(APP/'diagnostics/JankDiagnostics.kt').read_text()
        self.assertIn('ArrayBlockingQueue(4)',s)
        self.assertIn('sequence % 6',s)
        self.assertLess(s.index('writer.execute'),s.index('temp.writeText'))
        for token in ('saved_monotonic_ns','saved_boottime_ns','postroll_complete','stages_ns'): self.assertIn(token,s)
        for token in ('conversationId','messageId','window.title','exception.message','getStackTrace'): self.assertNotIn(token,s)
        ring=(APP/'diagnostics/JankIncidentBuffer.kt').read_text()
        for token in ('history.size > 480','rows.size >= 960','15_000_000_000L','!frame.firstDraw'): self.assertIn(token,ring)

    def test_system_trace_is_root_owned_bounded_and_triggered_after_slow_frame(self):
        shell=(ROOT/'scripts/diagnostics/record-ui-jank.sh').read_text()
        config=(ROOT/'scripts/diagnostics/ui-jank.pbtxt').read_text()
        self.assertNotIn('while ! grep -q DAIYU_JANK_READY',shell)
        self.assertIn('timeout -s TERM -k 5 900',shell)
        self.assertIn("trap 'exit 143' TERM",shell)
        self.assertIn('perfetto --background-wait',shell)
        self.assertLess(shell.index('METADATA_BASELINE=$(metadata_signature)'), shell.index('perfetto --background-wait'))
        self.assertIn('test \"$METADATA_NOW\" != \"$METADATA_BASELINE\"',shell)
        self.assertIn('PERFETTO_START_EARLY_EXIT',shell)
        self.assertIn('PERFETTO_SESSION_NOT_STARTED',shell)
        self.assertLess(shell.index('SESSION_STATE=$(perfetto --query'),shell.index('echo \"TRACE_ARMED'))
        self.assertIn('trigger_perfetto daiyu_ui_jank_v1',shell)
        self.assertLess(shell.index('trigger_perfetto daiyu_ui_jank_v1'),shell.index('copy_metadata \"trigger-$INDEX\"'))
        self.assertLess(shell.index('target-pid-start-$INDEX'),shell.index('echo \"TRACE_ARMED'))
        self.assertIn('target-pid-end-$INDEX',shell)
        self.assertIn('"$INDEX" -lt 6',shell)
        self.assertIn(' -lt 900',shell)
        self.assertNotIn('input tap',shell)
        self.assertNotIn('am start',shell)
        self.assertNotRegex(config,r'(?m)^\s*duration_ms:')
        self.assertIn('trigger_timeout_ms: 900000',config)
        self.assertIn('fill_policy: RING_BUFFER',config)
        self.assertIn('stop_delay_ms: 2000',config)
        self.assertIn('clear_period_ms: 5000',config)
        self.assertIn('scan_all_processes_on_start: true',config)

    def test_metadata_signature_detects_final_writes_without_logs_and_ignores_tmp(self):
        source=(ROOT/'scripts/diagnostics/record-ui-jank.sh').read_text()
        function=source.split('metadata_signature() {',1)[1].split('\n}',1)[0]
        with tempfile.TemporaryDirectory() as directory:
            def signature():
                return subprocess.run(['sh','-c','DIR="$1";'+function,'sh',directory],
                                      check=True,text=True,capture_output=True).stdout
            self.assertEqual('',signature())
            temporary=Path(directory)/'incident-1.tmp'
            temporary.write_text('{}')
            self.assertEqual('',signature())
            target=Path(directory)/'incident-1.json'
            temporary.replace(target)
            first=signature()
            self.assertTrue(first)
            self.assertEqual(first,signature())
            original=target.stat()
            temporary.write_text('{}')
            os.utime(temporary,ns=(original.st_atime_ns,original.st_mtime_ns))
            temporary.replace(target)
            self.assertNotEqual(first,signature())
            first=signature()
            target.write_text('{"complete":true}')
            self.assertNotEqual(first,signature())
            second=signature()
            stat=target.stat()
            os.utime(target,(stat.st_atime,stat.st_mtime+15))
            self.assertNotEqual(second,signature())

    def test_session_match_requires_exact_name_and_started_state(self):
        source=(ROOT/'scripts/diagnostics/record-ui-jank.sh').read_text()
        rule=re.search(r"awk -v name=\"\$SESSION\" '([^']+)'",source).group(1)
        for row,expected in [
            ('3 0 STARTED (2) 69632 900 4 16:31:31 daiyu-jank-10-1',True),
            ('3 0 STARTED (2) 69632 900 4 16:31:31 daiyu-jank-10-10',False),
            ('3 0 CONFIGURED (2) 69632 900 4 16:31:31 daiyu-jank-10-1',False),
            ('Tracing sessions: 1 (started: 0)',False),
            ('',False),
        ]:
            with self.subTest(row=row):
                result=subprocess.run(['awk','-v','name=daiyu-jank-10-1',rule],input=row+'\n',
                                      text=True,capture_output=True,check=True)
                self.assertEqual(expected,bool(result.stdout.strip()))
