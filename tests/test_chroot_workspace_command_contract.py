"""Production wiring only; real mounts and Android execution need device tests."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class ChrootWorkspaceCommandContract(unittest.TestCase):
    def source(self, name):
        return (MAIN / name).read_text()

    def test_selected_chroot_reaches_both_command_entries(self):
        controller = self.source('ChatController.kt')
        branch = controller.split('val commandLinux =', 1)[1].split('val launch =', 1)[0]
        self.assertNotIn('LinuxExecutionMode.PROOT', branch)
        self.assertNotIn('executionModes[reference] != LinuxExecutionMode.PROOT', self.source('terminal/LinuxSettingsApplication.kt'))
        self.assertNotIn('linux_chroot_command_jobs_not_supported', self.source('integration/tools/CatalogTerminalBinding.kt'))
        self.assertIn('else digest("linux-chroot-root-scope-v2", argv, environment, root.binding().toString())', self.source('integration/tools/CatalogTerminalBinding.kt'))

    def test_command_transport_never_falls_back_to_plain_builder(self):
        registry = self.source('terminal/TerminalRegistry.kt')
        self.assertIn('if (b.rootScope != null) startRootProcess(b, command, id)', registry)
        self.assertIn('if(mode=="daemon" && b.rootScope == null)', registry)
        self.assertIn('job.startupCleanup = failure.cleanup', registry)
        self.assertIn('cleanup.awaitTermination(remaining.coerceAtMost(30_000))', registry)
        self.assertIn('process.exitCodeOrNull()', registry)
        self.assertNotIn('descendants drained', registry)

    def test_daemon_releases_both_pins_only_after_original_job_drain(self):
        session = self.source('integration/tools/TerminalJobSession.kt')
        observer = session.split('while (!owned.awaitJobTermination(daemon, 30_000))', 1)[1].split('catch', 1)[0]
        self.assertIn('TerminalPtyLaunch.Linux)?.release()', observer)
        self.assertIn('plan.releaseWorkspace()', observer)
        for source in ['integration/tools/TerminalJobSession.kt', 'integration/tools/RunCommandTools.kt', 'terminal/TerminalRegistry.kt']:
            self.assertIn('.binding()', self.source(source))

    def test_native_private_mount_and_command_eof_use_pinned_root(self):
        native = self.source('terminal/native/daiyu_pty.c')
        header = self.source('terminal/native/daiyu_root_scope.h')
        self.assertLess(native.index('if(root_enter()<0)'), native.index('root_mount_workspace(rootfd,'))
        self.assertLess(native.index('root_mount_workspace(rootfd,'), native.index('root_mount_proc(rootfd)'))
        self.assertIn('fchdir(rootfd)', native)
        self.assertIn('argv[command_at+1]="."', native)
        self.assertIn('dup2(input,STDIN_FILENO)', native)
        self.assertIn('identity.st_dev!=device', header)
        self.assertIn('identity.st_ino!=inode', header)
        self.assertIn('mount(from,to,NULL,MS_BIND,NULL)', header)

if __name__ == '__main__':
    unittest.main()
