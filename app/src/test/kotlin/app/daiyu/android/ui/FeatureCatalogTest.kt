package app.daiyu.android.ui

import org.junit.Assert.*
import org.junit.Test

class FeatureCatalogTest {
    private val entries get() = FeatureCatalog.groups.flatMap { it.entries }

    @Test fun coversEveryRetainedFeatureExactlyOnce() {
        val expected = (1..43).map { "P%02d".format(it) }.toSet()
        assertEquals(expected, entries.map { it.id }.filter { it.matches(Regex("P\\d{2}")) }.toSet())
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
    }
    @Test fun hasEightStableNonemptyGroups() {
        assertEquals(52, entries.size)
        assertEquals(listOf("conversation", "model", "input", "context", "collaboration", "voice", "workspace", "appearance"),
            FeatureCatalog.groups.map { it.id })
        assertTrue(FeatureCatalog.groups.all { it.title.isNotBlank() && it.entries.isNotEmpty() })
    }
    @Test fun labelsAreNormalNamesWithoutAvailabilityBadges() {
        assertTrue(entries.all { entry -> entry.title.isNotBlank() &&
            listOf("尚未接入", "未实现", "占位", "开发中").none { it in entry.title } })
        assertTrue(FeatureCatalog.groups.all { group -> group.entries.size == group.entries.map { it.title }.toSet().size })
    }
    @Test fun onlyExistingNavigationHasRoutes() {
        assertEquals(mapOf(
            "P07" to FeatureRoute.SIDEBAR,
            "P21" to FeatureRoute.USAGE_STATS,
            "P22" to FeatureRoute.MANAGE_CHATS,
            "P28" to FeatureRoute.CONVERSATION_SEARCH,
            "P28.folders" to FeatureRoute.FOLDERS,
            "R03" to FeatureRoute.MODEL_SETTINGS,
            "P02" to FeatureRoute.MODEL_SETTINGS,
            "P04" to FeatureRoute.MODEL_SETTINGS,
            "P06" to FeatureRoute.MODEL_SETTINGS,
            "P11" to FeatureRoute.CHAT,
            "P12" to FeatureRoute.CHAT,
            "P13" to FeatureRoute.CHAT,
            "P23" to FeatureRoute.CHAT,
            "P33" to FeatureRoute.SKILLS,
            "R12" to FeatureRoute.BACKUP,
            "R09" to FeatureRoute.TERMINAL,
            "P34" to FeatureRoute.BROWSER_SETTINGS,
            "P09" to FeatureRoute.COMPRESSION_SETTINGS,
            "P41" to FeatureRoute.USER_MESSAGE
        ), entries.mapNotNull { entry -> entry.route?.let { entry.id to it } }.toMap())
    }
    @Test fun unwiredMediaAndAssistantHaveNoReplacementAction() {
        for (id in listOf("P01", "P14", "P15", "P16", "P17", "P18", "P19", "P20", "P32", "R06", "R07", "R08"))
            assertNull(id, entries.single { it.id == id }.route)
    }
    @Test fun chatControlSlotsNavigateWithoutExecutingACommand() {
        for (id in listOf("P11", "P12", "P13", "P23"))
            assertEquals(id, FeatureRoute.CHAT, entries.single { it.id == id }.route)
    }
    @Test fun balanceWindowAndUsageRemainSeparateSlots() {
        assertEquals(listOf("供应商余额", "上下文统计", "用量统计"),
            listOf("P05", "P10", "P21").map { id -> entries.single { it.id == id }.title })
        assertTrue(entries.filter { it.id in setOf("P35", "P36", "P37", "P39", "P40", "P42", "P43") }.all { it.route == null })
    }

    @Test fun detailsCoverEveryEntryWithStableUniqueControls() {
        assertEquals(entries.map { it.id }.toSet(), FeatureDetails.pages.keys)
        val controls = FeatureDetails.pages.values.flatten().flatMap { it.controls }
        assertEquals(259, controls.size)
        assertEquals(FeatureControlKind.entries.toSet(), controls.map { it.kind }.toSet())
        FeatureDetails.pages.forEach { (id, sections) ->
            assertTrue(id, sections.isNotEmpty())
            assertEquals(id, sections.size, sections.map { it.id }.toSet().size)
            val rows = sections.flatMap { it.controls }
            assertEquals(id, rows.size, rows.map { it.id }.toSet().size)
            sections.forEach { section ->
                assertTrue(section.id.isNotBlank() && section.title.isNotBlank() && section.controls.isNotEmpty())
                section.controls.forEach { control ->
                    assertTrue(control.id.isNotBlank() && control.title.isNotBlank())
                    assertTrue(control.route == null || control.kind == FeatureControlKind.ACTION)
                    assertFalse(listOf("尚未接入", "未实现", "占位", "开发中").any { it in control.title })
                }
            }
        }
    }
    @Test fun onlyExplicitSecondaryOpenActionsNavigate() {
        val expected = mapOf(
            "SIDEBAR" to "P07", "MANAGE_CHATS" to "P22", "USAGE_STATS" to "P21",
            "CONVERSATION_SEARCH" to "P28", "FOLDERS" to "P28.folders",
            "MODEL_SETTINGS" to "R03 P02 P04 P06", "CHAT" to "P11 P12 P13 P23",
            "SKILLS" to "P33", "TERMINAL" to "R09", "BROWSER_SETTINGS" to "P34", "COMPRESSION_SETTINGS" to "P09", "BACKUP" to "P38.files R12", "USER_MESSAGE" to "P41"
        ).flatMap { (route, ids) -> ids.split(' ').map { "$it:open" to FeatureRoute.valueOf(route) } }.toMap()
        val actual = FeatureDetails.pages.flatMap { (id, sections) -> sections.flatMap { it.controls }.mapNotNull {
            control -> control.route?.let { "$id:${control.id}" to it }
        } }.toMap()
        assertEquals(expected, actual)
    }
}
