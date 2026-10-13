package app.daiyu.android.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectIdentityTest {
    @Test fun productNameIsChinese() { assertEquals("代鱼", ProjectIdentity.displayName) }
}
