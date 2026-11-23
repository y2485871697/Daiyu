package app.daiyu.android

import org.junit.Assert.assertEquals
import org.junit.Test

class TestJvmConfigurationTest {
    @Test fun robolectricRuntimeUsesPinnedCentralMirrorInTestJvm() {
        assertEquals("https://maven-central.storage-download.googleapis.com/maven2", System.getProperty("robolectric.dependency.repo.url"))
    }
}
