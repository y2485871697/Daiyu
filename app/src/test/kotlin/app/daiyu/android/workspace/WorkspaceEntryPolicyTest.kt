package app.daiyu.android.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceEntryPolicyTest {
    @Test fun rootAndLinksAreAlwaysProtected() {
        for (rootfs in listOf(false, true)) {
            assertFalse(WorkspaceEntryPolicy.mutable("", rootfs))
            assertFalse(WorkspaceEntryPolicy.mutable("link", rootfs, symbolicLink = true))
        }
    }
    @Test fun systemTreesAndTheirContentsAreProtected() {
        for (name in listOf("bin", "boot", "dev", "etc", "lib", "lib32", "lib64", "libx32", "proc", "run", "sbin", "sys", "usr", "var", "mnt", "media")) {
            assertFalse(name, WorkspaceEntryPolicy.mutable(name, true))
            assertFalse(name, WorkspaceEntryPolicy.mutable("$name/sub", true))
            assertFalse(name, WorkspaceEntryPolicy.mutable("$name/sub/file", true))
        }
    }
    @Test fun userTreesAllowChildrenButKeepSystemParent() {
        for (name in listOf("home", "root", "tmp", "opt", "srv")) {
            assertFalse(WorkspaceEntryPolicy.mutable(name, true))
            assertTrue(WorkspaceEntryPolicy.mutable("$name/project", true))
        }
    }
    @Test fun ordinaryNamesAndPrivateSystemNamesRemainEditable() {
        for (name in listOf("project", "usr2", "etc-backup", "汉字 文件.txt")) assertTrue(WorkspaceEntryPolicy.mutable(name, true))
        assertTrue(WorkspaceEntryPolicy.mutable("usr/local", false))
    }
    @Test fun invalidPathsAndNamesAreRejected() {
        for (name in listOf("", " ", ".", "..", "a/b", "a\\b", "a\u0000b", "a\nb", ".workspace-import-owned", "汉".repeat(86))) {
            try { WorkspaceEntryPolicy.requireName(name); fail(name) } catch (_: IllegalArgumentException) { }
        }
        for (path in listOf("../escape", "a/../escape", "./x", "/abs", "a//b", "a\\b")) assertFalse(WorkspaceEntryPolicy.mutable(path, true))
    }
    @Test fun validNamesRemainExactAndRespectUtf8Limit() {
        for (name in listOf("文件夹 甲", " file ", "a.txt", ".config", "汉".repeat(85))) WorkspaceEntryPolicy.requireName(name)
    }
}
