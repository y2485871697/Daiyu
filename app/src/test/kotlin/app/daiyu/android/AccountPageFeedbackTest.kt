package app.daiyu.android

import app.daiyu.android.voice.account.VoiceCatalogFailure
import org.junit.Assert.*
import org.junit.Test

class AccountPageFeedbackTest {
    @Test fun currentVisitDisplaysRealReadFailure() {
        val page = AccountPageFeedback()
        assertEquals("名额读取失败", page.report(page.visit, "名额读取失败").text)
    }
    @Test fun enteringEveryOtherPageClearsErrors() {
        for (target in listOf("LIST", "CREATE:0", "CREATE:1", "CREATE:2", "IMPORT", "SYNC", "ACCOUNT")) {
            val page = AccountPageFeedback(text = "来自同步页的错误")
            assertEquals(target, "", page.nextVisit().text)
        }
    }
    @Test fun returningToSamePageDoesNotReviveLateErrorOrSuccess() {
        val old = AccountPageFeedback()
        val returned = old.nextVisit().nextVisit()
        assertSame(returned, returned.report(old.visit, "晚到的读取错误"))
        assertSame(returned, returned.report(old.visit, "晚到的同步成功"))
    }
    @Test fun oldOperationCannotOverwriteNewSuccess() {
        val first = AccountPageFeedback()
        val next = first.nextVisit().report(first.visit + 1, "已同步")
        assertEquals("已同步", next.report(first.visit, "旧失败").text)
    }
    @Test fun clearingTheCurrentNoticeKeepsItsCurrentOwner() {
        val page = AccountPageFeedback(12, "请选择项目")
        assertEquals(AccountPageFeedback(12), page.clear())
        assertEquals("保存成功", page.clear().report(12, "保存成功").text)
    }
    @Test fun onlyControlledCatalogFailuresExposeTheirSpecificReason() {
        val safe = VoiceCatalogFailure("没有查询权限 HTTP 403")
        assertEquals(safe.safeMessage, voiceAccountFailure(safe, "名额读取失败"))
        val arbitrary = IllegalStateException("secret-ak secret-sk response body")
        assertEquals("名额读取失败", voiceAccountFailure(arbitrary, "名额读取失败"))
        assertFalse(voiceAccountFailure(arbitrary, null).contains("secret"))
        assertFalse(voiceAccountFailure(arbitrary, null).contains("制作"))
    }
    @Test fun controlledNetworkFailureIsNotMisreportedAsAnIamProblem() {
        val failure = app.daiyu.android.voice.account.AccountTransportException("账户响应读取失败，请手动查询状态")
        assertEquals(failure.safeMessage, voiceAccountFailure(failure, "请核对 AK／SK 和 IAM 权限"))
        assertFalse(voiceAccountFailure(failure, null).contains("IAM"))
    }
    @Test fun controlledVoiceQueryReasonReplacesGenericAccountFallback() {
        val failure = app.daiyu.android.voice.account.PersonalVoiceQueryFailure("音色查询响应格式无效，请重新查询。")
        assertEquals(failure.safeMessage, voiceAccountFailure(failure, "已复刻名额查询失败，请检查账户与项目后重试。"))
    }
    @Test fun trainingKeepsItsOwnUnknownSubmissionWarning() {
        assertEquals("制作结果未确认，请查询原音色，不要重复提交。",
            voiceAccountFailure(IllegalStateException("secret"), "制作结果未确认，请查询原音色，不要重复提交。"))
    }
}
