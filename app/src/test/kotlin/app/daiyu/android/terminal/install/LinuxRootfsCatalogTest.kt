package app.daiyu.android.terminal.install

import junit.framework.TestCase

/** Fixed supplied-source values only; these assertions are not download/hash verification. */
class LinuxRootfsCatalogTest : TestCase() {
    fun testExactFourPinnedArtifacts() {
        val rows = listOf(
            listOf("alpine", "3.24.1", "arm64-v8a", "aarch64", "4023732",
                "f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259"),
            listOf("alpine", "3.24.1", "x86_64", "x86_64", "3698422",
                "41f73e3cf5fa919b8aa5ca6b30dc48f0da2720776d7423e2a7748211456fe081"),
            listOf("debian", "13", "arm64-v8a", "aarch64", "35409704",
                "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"),
            listOf("debian", "13", "x86_64", "x86_64", "36728936",
                "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716")
        )
        assertEquals(4, LinuxRootfsCatalog.artifacts.size)
        for (row in rows) {
            val artifact = checkNotNull(LinuxRootfsCatalog.find(row[0], row[2]))
            assertEquals(row[1], artifact.version)
            assertEquals(row[4].toLong(), artifact.compressedBytes)
            assertEquals(row[5], artifact.sha256)
            val alpine = row[0] == "alpine"
            val path = if (alpine) "alpine/v3.24/releases/${row[3]}/alpine-minirootfs-3.24.1-${row[3]}.tar.gz"
                else "termux/proot-distro/releases/download/v4.29.0/debian-trixie-${row[3]}-pd-v4.29.0.tar.xz"
            val primary = if (alpine) "https://dl-cdn.alpinelinux.org/$path" else "https://github.com/$path"
            val alternate = if (alpine) "https://mirrors.aliyun.com/$path" else "https://gh-proxy.com/$primary"
            assertEquals(listOf(primary, alternate), artifact.mirrors.map { it.uri.toASCIIString() })
            assertEquals(if (alpine) listOf("primary", "aliyun") else listOf("primary", "gh-proxy"),
                artifact.mirrors.map { it.id })
            assertEquals(if (alpine) RootfsArchive.TAR_GZIP else RootfsArchive.TAR_XZ, artifact.archive)
            assertEquals(if (alpine) 0 else 1, artifact.stripComponents)
        }
    }

    fun testNo32BitOrUnknownFallbackAndListsAreImmutable() {
        for (distribution in listOf("alpine", "debian")) {
            assertNull(LinuxRootfsCatalog.find(distribution, "armeabi-v7a"))
            assertNull(LinuxRootfsCatalog.find(distribution, "x86"))
            assertNull(LinuxRootfsCatalog.find(distribution, "aarch64"))
        }
        assertNull(LinuxRootfsCatalog.find("unknown", "arm64-v8a"))
        try {
            (LinuxRootfsCatalog.artifacts as MutableList).clear()
            fail("mutable catalog")
        } catch (_: UnsupportedOperationException) { }
        try {
            (LinuxRootfsCatalog.artifacts.first().mirrors as MutableList).clear()
            fail("mutable mirrors")
        } catch (_: UnsupportedOperationException) { }
    }

    fun testLimitsAreFixedAndCannotBeRaisedThroughTestSeam() {
        assertEquals(3L * 1024 * 1024 * 1024, TarLinuxRootfsExtraction.MAX_EXPANDED_BYTES)
        assertEquals(200000, TarLinuxRootfsExtraction.MAX_ENTRIES)
        assertEquals(128 * 1024, TarLinuxRootfsExtraction.XZ_MEMORY_LIMIT_KIB)
        try {
            TarLinuxRootfsExtraction(maxEntries = 200001)
            fail("raised entry limit")
        } catch (_: IllegalArgumentException) { }
        try {
            TarLinuxRootfsExtraction(maxExpandedBytes = TarLinuxRootfsExtraction.MAX_EXPANDED_BYTES + 1)
            fail("raised size limit")
        } catch (_: IllegalArgumentException) { }
    }
}
