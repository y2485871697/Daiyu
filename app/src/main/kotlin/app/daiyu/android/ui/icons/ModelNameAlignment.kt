package app.daiyu.android.ui.icons

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Match the visible name to its icon without changing the row's measured size or touch target.
internal fun Modifier.modelNameOpticalAlignment(): Modifier = offset(y = (-1).dp)
