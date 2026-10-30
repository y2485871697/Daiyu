package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

/** A completed block's budget stays unchanged while later blocks reveal. */
internal fun markdownVisibility(parent: State<Int>, start: Int, length: Int): State<Int> =
    derivedStateOf { (parent.value - start).coerceIn(0, length) }

@Composable
internal fun rememberMarkdownVisibility(parent: State<Int>, start: Int, length: Int): State<Int> =
    remember(parent, start, length) { markdownVisibility(parent, start, length) }
