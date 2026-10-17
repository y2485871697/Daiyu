package app.daiyu.android

import org.junit.Assert.assertEquals
import org.junit.Test

class TestJvmConfigurationTest {
    @Test fun robolectricRuntimeUsesCanonicalCentralEndpointInTestJvm() {
        assertEquals("https://repo.maven.apache.org/maven2", System.getProperty("robolectric.dependency.repo.url"))
    }
}
