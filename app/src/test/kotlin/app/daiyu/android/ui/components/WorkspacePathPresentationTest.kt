package app.daiyu.android.ui.components

import org.junit.Assert.*
import org.junit.Test

class WorkspacePathPresentationTest {
    private val supplied = "/data/user/0/app.daiyu.android/files/workspace"
    private val canonical = "/data/data/app.daiyu.android/files/workspace"
    private val roots = listOf(supplied, canonical)

    @Test fun rootHasOneShortNameWithoutRedundantLocation() {
        for (path in roots) assertEquals(WorkspacePathPresentation("/workspace", null), workspacePathPresentation(path, roots))
    }

    @Test fun childUsesItsNameAndWorkspaceRelativeLocation() {
        for (root in roots) assertEquals(WorkspacePathPresentation("项目名称", "/workspace / 项目名称"),
            workspacePathPresentation("$root/项目名称", roots))
    }

    @Test fun shortHierarchyKeepsAllDirectoryComponents() {
        assertEquals(WorkspacePathPresentation("src", "/workspace / 开发 / 项目 / src"),
            workspacePathPresentation("$supplied/开发/项目/src", roots))
    }

    @Test fun deepHierarchyKeepsRootAndLastTwoDirectories() {
        assertEquals(WorkspacePathPresentation("src", "/workspace / … / 项目 / src"),
            workspacePathPresentation("$supplied/开发/客户端/项目/src", roots))
    }

    @Test fun unicodeQuotesAndLeadingTrailingWhitespaceAreNotTrimmed() {
        val name = " 项目 '🐟' 空格 "
        assertEquals(WorkspacePathPresentation(name, "/workspace / $name"), workspacePathPresentation("$supplied/$name", roots))
    }

    @Test fun sameNamedLeavesRetainDifferentParentContext() {
        val first = workspacePathPresentation("$supplied/开发/A/src", roots)
        val second = workspacePathPresentation("$supplied/开发/B/src", roots)
        assertEquals(first.name, second.name)
        assertNotEquals(first.location, second.location)
    }

    @Test fun componentBoundaryDoesNotTreatPrefixSiblingAsWorkspace() {
        val path = "$supplied-other/secret"
        assertEquals(WorkspacePathPresentation("secret", path), workspacePathPresentation(path, roots))
    }

    @Test fun workspaceNamedDescendantDoesNotHideItsAncestors() {
        assertEquals(WorkspacePathPresentation("src", "/workspace / 项目 / workspace / src"),
            workspacePathPresentation("$supplied/项目/workspace/src", roots))
    }

    @Test fun unknownPathsKeepOriginalLocationInsteadOfPretendingToBeWorkspace() {
        for (path in listOf("/other/项目/src", "environment Rootfs · /", "", "/")) {
            val display = workspacePathPresentation(path, roots)
            assertEquals(path, display.location)
            assertTrue(display.name.isNotEmpty() || path.isEmpty())
        }
    }

    @Test fun emptyRootMetadataDoesNotStripApplicationPrefix() {
        assertEquals(WorkspacePathPresentation("workspace", supplied), workspacePathPresentation(supplied, emptyList()))
    }

    @Test fun longestExplicitRootWins() {
        val path = "$supplied/project/src"
        assertEquals(WorkspacePathPresentation("src", "/project / src"),
            workspacePathPresentation(path, roots + "$supplied/project"))
    }

    @Test fun actualRootNameSuppliesDisplayPrefixInsteadOfFixedWorkspaceLabel() {
        for (name in listOf("projects", " 项目 '🐟' ", "工作目录")) {
            val root = "/private/files/$name"
            val actualRoots = listOf(root)
            assertEquals(WorkspacePathPresentation("/$name", null), workspacePathPresentation(root, actualRoots))
            assertEquals("/$name", workspaceDirectoryDisplayPath(root, actualRoots))
            val path = "$root/客户/src"
            assertEquals(WorkspacePathPresentation("src", "/$name / 客户 / src"), workspacePathPresentation(path, actualRoots))
            assertEquals("/$name/客户/src", workspaceDirectoryDisplayPath(path, actualRoots))
            val sibling = "$root-other/src"
            assertEquals(WorkspacePathPresentation("src", sibling), workspacePathPresentation(sibling, actualRoots))
            assertEquals(sibling, workspaceDirectoryDisplayPath(sibling, actualRoots))
        }
    }

    @Test fun nestedActualRootKeepsItsNameAndLongestMatchRegardlessOfInputOrder() {
        val nested = "$supplied/Client A"
        for (actualRoots in listOf(listOf(supplied, nested), listOf(nested, supplied))) {
            assertEquals(WorkspacePathPresentation("src", "/Client A / src"), workspacePathPresentation("$nested/src", actualRoots))
            assertEquals("/Client A/src", workspaceDirectoryDisplayPath("$nested/src", actualRoots))
        }
    }

    @Test fun directoryHeaderUsesCompleteVirtualPathWithoutChangingUnknownPaths() {
        for (root in roots) {
            assertEquals("/workspace", workspaceDirectoryDisplayPath(root, roots))
            val suffix = "/开发/很长目录/项目/ src 🐟 "
            assertEquals("/workspace$suffix", workspaceDirectoryDisplayPath(root + suffix, roots))
        }
        for (path in listOf("$supplied-other/src", "/other/workspace", "", "/"))
            assertEquals(path, workspaceDirectoryDisplayPath(path, roots))
        assertEquals(supplied, workspaceDirectoryDisplayPath(supplied, emptyList()))
        assertEquals("/project/src", workspaceDirectoryDisplayPath("$supplied/project/src", roots + "$supplied/project"))
    }
}
