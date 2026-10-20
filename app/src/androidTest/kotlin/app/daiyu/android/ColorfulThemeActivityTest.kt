package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceAccentColor
import app.daiyu.android.data.model.AppearancePaletteStyle
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.icons.DaiyuFeatureColor
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.icons.featureColorFor
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenState
import app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi
import app.daiyu.android.ui.screens.chat.conversation.ComposerUi
import app.daiyu.android.ui.screens.chat.conversation.DaiyuChatScreen
import app.daiyu.android.ui.components.ConversationSidePaneScaffold
import app.daiyu.android.ui.model.ConversationPaneUiState
import app.daiyu.android.ui.model.ConversationSummaryUi
import app.daiyu.android.ui.model.ConversationModeUi
import app.daiyu.android.ui.screens.chat.conversation.ChatOverflowMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

class ColorfulThemeActivityTest : WiredActivityTestCase() {
    private fun show(dark: Boolean, pureBlack: Boolean = false,
        appearance: AppearanceSettings = AppearanceSettings(), content: @Composable () -> Unit) {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    AgentAppTheme(appearance.copy(
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                        pureBlackEnabled = pureBlack,
                    ), applyInterfaceScale = false) {
                        Box(Modifier.requiredWidth(360.dp).fillMaxHeight()) { content() }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun contrast(foreground: Color, background: Color): Double =
        ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb())

    fun testNeutralSurfacesAndReadableColorAccentsInBothThemes() {
        for (dark in listOf(false, true)) for (pureBlack in listOf(false, true)) {
            var background = Color.Unspecified
            var surface = Color.Unspecified
            var body = Color.Unspecified
            var secondary = Color.Unspecified
            var primary = Color.Unspecified
            var onPrimary = Color.Unspecified
            show(dark, pureBlack) {
                val colors = MiuixTheme.colorScheme
                background = colors.background
                surface = colors.surface
                body = colors.onSurface
                secondary = colors.onSurfaceVariantSummary
                primary = colors.primary
                onPrimary = colors.onPrimary
            }
            assertTrue(maxOf(background.red, background.green, background.blue) -
                minOf(background.red, background.green, background.blue) < 0.04f)
            assertTrue(contrast(body, background) >= 7.0)
            assertTrue(contrast(secondary, surface) >= 4.5)
            assertTrue(contrast(onPrimary, primary) >= 4.5)
            for (accent in DaiyuFeatureColor.entries) {
                assertTrue("$accent icon contrast; dark=$dark black=$pureBlack",
                    contrast(if (dark) accent.dark else accent.light, surface) >= 3.0)
            }
            if (dark && pureBlack) assertEquals(Color.Black, background)
        }
    }

    fun testRetainedColorPreferencesDoNotOverrideFixedPalette() {
        val savedOptions = listOf(
            AppearanceSettings(),
            AppearanceSettings(monetEnabled = true),
            AppearanceSettings(paletteStyle = AppearancePaletteStyle.EXPRESSIVE,
                accentColor = AppearanceAccentColor.RED),
            AppearanceSettings(monetEnabled = true, paletteStyle = AppearancePaletteStyle.FIDELITY,
                accentColor = AppearanceAccentColor.TEAL),
        )
        for (dark in listOf(false, true)) for (pureBlack in listOf(false, true)) {
            val palette = if (dark) DaiyuPalette.dark else DaiyuPalette.light
            val expected = listOf(palette.primary, palette.onPrimary, palette.secondary,
                if (dark && pureBlack) Color.Black else palette.background,
                if (dark && pureBlack) Color.Black else palette.surface,
                palette.onSurface, palette.onSurfaceVariantSummary)
            for (appearance in savedOptions) {
                var actual = emptyList<Color>()
                show(dark, pureBlack, appearance) {
                    val colors = MiuixTheme.colorScheme
                    actual = listOf(colors.primary, colors.onPrimary, colors.secondary,
                        colors.background, colors.surface, colors.onSurface, colors.onSurfaceVariantSummary)
                }
                assertEquals("dark=$dark pureBlack=$pureBlack appearance=$appearance", expected, actual)
            }
        }
    }

    fun testFeatureColorsAreStableAcrossScreensAndNotSingleHue() {
        val icons = listOf(DaiyuIcons.Provider, DaiyuIcons.Assistant, DaiyuIcons.Palette,
            DaiyuIcons.Folder, DaiyuIcons.Voice)
        assertEquals(5, icons.map(::featureColorFor).distinct().size)
        assertEquals(featureColorFor(DaiyuIcons.Assistant), featureColorFor(DaiyuIcons.SubAgent))
        assertEquals(featureColorFor(DaiyuIcons.Voice), featureColorFor(DaiyuIcons.Speak))
        assertEquals(featureColorFor(DaiyuIcons.Folder), featureColorFor(DaiyuIcons.Backup))
    }

    fun testCaptureChatInBothThemesWithoutCallingBusinessActions() {
        for (dark in listOf(false, true)) {
            show(dark) {
                DaiyuChatScreen(
                    state = ChatScreenState(
                        title = "界面调整",
                        turns = listOf(
                            ChatTurnUi.User("preview-user", "列出此次修改的页面"),
                            ChatTurnUi.Assistant("preview-assistant",
                                finalText = "本次调整了聊天页、设置页、侧边栏和菜单。\n\n原有功能入口与操作保持不变。"),
                        ),
                        composer = ComposerUi(modelLabel = "模型", assistantLabel = "默认助手"),
                    ),
                    actions = ChatScreenActions(),
                )
            }
            capture(if (dark) "chat-colorful-dark" else "chat-colorful-light")
        }
    }

    private fun capture(name: String, tag: String? = null) {
        val bitmap = (if (tag == null) ui.onRoot() else ui.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
        val directory = File(checkNotNull(activity.getExternalFilesDir(null)), "settings-ui")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    fun testCaptureSidebarAndMenuWithSharedFeatureColors() {
        for (dark in listOf(false, true)) {
            show(dark) {
                ConversationSidePaneScaffold(
                    state = ConversationPaneUiState(
                        conversations = listOf(ConversationSummaryUi("preview", "界面调整", "检查设置与聊天页面", "", mode = ConversationModeUi.Chat, isToday = true)),
                        selectedConversationId = "preview", searchQuery = "",
                    ),
                    visible = true, backHandlerEnabled = true,
                    onOpen = {}, onDismiss = {}, onSearchChange = {},
                    onConversationSelected = {}, onConversationRename = {}, onConversationDelete = {},
                    onNewConversation = {}, onSelectAssistant = {}, onEditAssistant = {},
                    onOpenAssistants = {}, onOpenSettings = {}, onOpenModelProviders = {},
                    onOpenUsageStats = {}, onOpenSkills = {}, onOpenPermissions = {},
                ) {}
            }
            capture(if (dark) "sidebar-colorful-dark" else "sidebar-colorful-light")
            show(dark) { ChatOverflowMenu(expanded = true, onDismiss = {}, onAction = {}) }
            capture(if (dark) "menu-colorful-dark" else "menu-colorful-light", "chat-overflow-menu")
        }
    }
}
