package app.daiyu.android.pet

import org.junit.Assert.*
import org.junit.Test

class PetErrorLogTest {
    @Test fun normalStatesAndRepeatedSnapshotsDoNotWriteErrors() {
        val lines = mutableListOf<String>()
        val log = PetErrorLog { lines.add(it) }
        log.update(null); log.update(null)
        assertTrue(lines.isEmpty())
        log.update("存储失败"); log.update("存储失败")
        assertEquals(listOf("存储失败"), lines)
    }

    @Test fun recoveryAllowsTheSameRealFailureToBeLoggedAgain() {
        val lines = mutableListOf<String>()
        val log = PetErrorLog { lines.add(it) }
        log.update("动作失败"); log.update(null); log.update("动作失败")
        assertEquals(listOf("动作失败", "动作失败"), lines)
    }

    @Test fun differentFailuresAreLoggedAndOwnersDoNotShareDeduplication() {
        val lines = mutableListOf<String>()
        val first = PetErrorLog { lines.add(it) }
        val second = PetErrorLog { lines.add(it) }
        first.update("模型失败"); first.update("存储失败"); second.update("存储失败")
        assertEquals(listOf("模型失败", "存储失败", "存储失败"), lines)
    }
}
