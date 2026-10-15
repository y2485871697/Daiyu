package app.daiyu.android.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

/** Keep a gesture started in an editor local, including its release fling. */
class EditorScrollIsolation {
    var editorOwnsGesture by mutableStateOf(false)
        private set

    fun pageGestureStarted() { editorOwnsGesture = false }
    fun editorGestureStarted() { editorOwnsGesture = true }

    fun gate(connection: NestedScrollConnection): NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (editorOwnsGesture) Offset.Zero else connection.onPreScroll(available, source)

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (editorOwnsGesture) Offset.Zero else connection.onPostScroll(consumed, available, source)

        override suspend fun onPreFling(available: Velocity): Velocity =
            if (editorOwnsGesture) Velocity.Zero else connection.onPreFling(available)

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            if (editorOwnsGesture) Velocity.Zero else connection.onPostFling(consumed, available)
    }
}

internal fun Modifier.editorPageGestures(isolation: EditorScrollIsolation): Modifier =
    pointerInput(isolation) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            isolation.pageGestureStarted()
        }
    }

internal fun Modifier.isolateEditorScroll(isolation: EditorScrollIsolation): Modifier =
    pointerInput(isolation) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Initial visits the page before this editor. Observe without consuming selection taps.
            isolation.editorGestureStarted()
            var verticalDrag = 0f
            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                // TextField handles Main first. Claim only leftover movement, including short text.
                event.changes.forEach { change ->
                    verticalDrag += change.position.y - change.previousPosition.y
                    if (change.pressed && change.previousPressed &&
                        kotlin.math.abs(verticalDrag) > viewConfiguration.touchSlop &&
                        change.position != change.previousPosition && !change.isConsumed
                    ) change.consume()
                }
            } while (event.changes.any { it.pressed })
        }
    }.nestedScroll(EditorScrollRemainder)

internal object EditorScrollRemainder : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y)
}
