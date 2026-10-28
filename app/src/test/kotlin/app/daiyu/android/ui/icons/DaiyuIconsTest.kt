package app.daiyu.android.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DaiyuIconsTest {
    // Explicit references make an omitted or renamed API member a compile-time failure.
    private val icons: List<Pair<String, ImageVector>> = listOf(
        "Menu" to DaiyuIcons.Menu,
        "More" to DaiyuIcons.More,
        "Add" to DaiyuIcons.Add,
        "Attach" to DaiyuIcons.Attach,
        "Image" to DaiyuIcons.Image,
        "Video" to DaiyuIcons.Video,
        "File" to DaiyuIcons.File,
        "Send" to DaiyuIcons.Send,
        "Pause" to DaiyuIcons.Pause,
        "Resume" to DaiyuIcons.Resume,
        "Stop" to DaiyuIcons.Stop,
        "Steer" to DaiyuIcons.Steer,
        "Copy" to DaiyuIcons.Copy,
        "Edit" to DaiyuIcons.Edit,
        "Branch" to DaiyuIcons.Branch,
        "Delete" to DaiyuIcons.Delete,
        "Regenerate" to DaiyuIcons.Regenerate,
        "Speak" to DaiyuIcons.Speak,
        "Share" to DaiyuIcons.Share,
        "Check" to DaiyuIcons.Check,
        "Error" to DaiyuIcons.Error,
        "ChevronRight" to DaiyuIcons.ChevronRight,
        "ChevronDown" to DaiyuIcons.ChevronDown,
        "Back" to DaiyuIcons.Back,
        "Close" to DaiyuIcons.Close,
        "Search" to DaiyuIcons.Search,
        "Settings" to DaiyuIcons.Settings,
        "Model" to DaiyuIcons.Model,
        "Reasoning" to DaiyuIcons.Reasoning,
        "Assistant" to DaiyuIcons.Assistant,
        "Context" to DaiyuIcons.Context,
        "SubAgent" to DaiyuIcons.SubAgent,
        "Tool" to DaiyuIcons.Tool,
        "Retry" to DaiyuIcons.Retry,
        "JumpUp" to DaiyuIcons.JumpUp,
        "Lightning" to DaiyuIcons.Lightning,
        "Compress" to DaiyuIcons.Compress,
        "Id" to DaiyuIcons.Id,
        "Export" to DaiyuIcons.Export,
        "Rename" to DaiyuIcons.Rename,
        "NewChat" to DaiyuIcons.NewChat,
        "Interactive" to DaiyuIcons.Interactive,
        "Paint" to DaiyuIcons.Paint,
        "Movie" to DaiyuIcons.Movie,
        "Terminal" to DaiyuIcons.Terminal,
        "Browser" to DaiyuIcons.Browser,
        "Device" to DaiyuIcons.Device,
        "Accessibility" to DaiyuIcons.Accessibility,
        "VirtualScreen" to DaiyuIcons.VirtualScreen,
        "Backup" to DaiyuIcons.Backup,
        "Skills" to DaiyuIcons.Skills,
        "Voice" to DaiyuIcons.Voice,
        "Palette" to DaiyuIcons.Palette,
        "Vibration" to DaiyuIcons.Vibration,
        "Bell" to DaiyuIcons.Bell,
        "Folder" to DaiyuIcons.Folder,
        "Pin" to DaiyuIcons.Pin,
        "Info" to DaiyuIcons.Info,
        "Key" to DaiyuIcons.Key,
        "Globe" to DaiyuIcons.Globe,
        "Shield" to DaiyuIcons.Shield,
        "Chart" to DaiyuIcons.Chart,
        "Provider" to DaiyuIcons.Provider,
        "Sponsor" to DaiyuIcons.Sponsor,
        "Balance" to DaiyuIcons.Balance,
        "Gateway" to DaiyuIcons.Gateway,
        "Vision" to DaiyuIcons.Vision,
        "Reconnect" to DaiyuIcons.Reconnect,
        "Title" to DaiyuIcons.Title,
        "Pet" to DaiyuIcons.Pet,
        "AppIcon" to DaiyuIcons.AppIcon,
        "Timestamp" to DaiyuIcons.Timestamp,
        "Refresh" to DaiyuIcons.Refresh,
        "Locate" to DaiyuIcons.Locate,
        "Import" to DaiyuIcons.Import,
        "Help" to DaiyuIcons.Help,
    )

    @Test
    fun everyRequiredIconBuildsWithAUniqueName() {
        assertEquals(76, icons.size)
        assertEquals(icons.size, icons.map { it.second.name }.toSet().size)
        icons.forEach { (name, vector) ->
            assertEquals("Daiyu.$name", vector.name)
            assertEquals(24.dp, vector.defaultWidth)
            assertEquals(24.dp, vector.defaultHeight)
            assertEquals(24f, vector.viewportWidth, 0f)
            assertEquals(24f, vector.viewportHeight, 0f)
            assertTrue("$name must contain artwork", vector.root.size > 0)
        }
    }

    @Test
    fun exposedImageVectorMembersExactlyMatchTheRequiredSet() {
        val exposedNames = DaiyuIcons::class.java.declaredMethods
            .filter {
                !it.isSynthetic && it.name.startsWith("get") &&
                    it.parameterCount == 0 && it.returnType == ImageVector::class.java
            }
            .map { it.name.removePrefix("get") }
            .toSet()
        assertEquals(icons.map { it.first }.toSet(), exposedNames)
    }
}
