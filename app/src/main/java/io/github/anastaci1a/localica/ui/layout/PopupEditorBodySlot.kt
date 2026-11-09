package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.spec.PopupEditorBodySpacing

/** The caller supplies the existing shared scroll chain for this owner's ScrollState. */
@Composable
internal fun PopupEditorBodySlot(focus: PopupEditorFocus, scrollModifier: Modifier,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    PopupEditorFocusPolicy(focus) {
        Column(modifier.then(focus.beforeScroll).then(scrollModifier).then(focus.afterScroll),
            verticalArrangement = Arrangement.spacedBy(PopupEditorBodySpacing.Regular.gapDp.dp)) {
            val column = this
            focus.Children { column.content() }
        }
    }
}
