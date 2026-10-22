package app.daiyu.android.ui.screens.search

import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.haptics.miuix.TextButton
import app.daiyu.android.ui.screens.settings.SettingsTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SearchQueryField(query: String, hint: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SettingsTextField(
            label = hint,
            value = query,
            onValueChange = { onChange(it.take(1000)) },
            hint = hint,
            tag = "search:query",
        )
        if (query.isNotEmpty()) {
            TextButton(
                text = stringResource(R.string.search_clear),
                onClick = { onChange("") },
                modifier = Modifier.padding(horizontal = 16.dp).testTag("search:clear"),
            )
        }
    }
}

@Composable
internal fun SearchNotice(text: String, tag: String = "search:notice") {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag(tag),
    )
}

@Composable
internal fun SearchLoading(enabled: Boolean = true, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp).then(if (enabled) Modifier.testTag("search:loading") else Modifier))
        Text(
            text = stringResource(R.string.search_loading),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            style = MiuixTheme.textStyles.body2,
        )
        TextButton(text = stringResource(R.string.search_cancel), enabled = enabled, onClick = onCancel)
    }
}

@Composable
internal fun SearchReadError(enabled: Boolean = true, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().then(if (enabled) Modifier.testTag("search:error") else Modifier)) {
        SearchNotice(stringResource(R.string.search_read_error))
        TextButton(
            text = stringResource(R.string.search_retry),
            enabled = enabled,
            onClick = onRetry,
            modifier = Modifier.padding(horizontal = 24.dp).then(if (enabled) Modifier.testTag("search:retry") else Modifier),
        )
    }
}

@Composable
internal fun SearchResultRow(title: String, detail: String, label: String, tag: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp).testTag(tag),
    ) {
        Text(text = title, style = MiuixTheme.textStyles.body1, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
}
