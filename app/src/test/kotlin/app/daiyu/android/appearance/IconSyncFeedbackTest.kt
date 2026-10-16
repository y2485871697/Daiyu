package app.daiyu.android.appearance

import app.daiyu.android.domain.appearance.IconSyncFailure
import app.daiyu.android.domain.appearance.IconSyncResult
import app.daiyu.android.domain.appearance.Task17IconCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconSyncFeedbackTest {
    @Test fun exceptionWithoutMessageIsReported() {
        assertEquals("RuntimeException", iconSyncFailureMessage(Result.failure(RuntimeException())))
    }

    @Test fun failureDetailsAreReported() {
        val receipt = IconSyncResult(true, Task17IconCatalog.defaultEntry,
            listOf(IconSyncFailure("persist", null, "保存失败")))
        assertEquals("保存失败", iconSyncFailureMessage(Result.success(receipt)))
    }

    @Test fun incompleteReceiptWithoutDetailsIsReported() {
        assertEquals("桌面图标尚未应用", iconSyncFailureMessage(Result.success(
            IconSyncResult(false, Task17IconCatalog.defaultEntry))))
    }

    @Test fun completeReceiptHasNoFailure() {
        assertNull(iconSyncFailureMessage(Result.success(IconSyncResult(true, Task17IconCatalog.defaultEntry))))
    }
}
