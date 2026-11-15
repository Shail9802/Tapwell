package app.tapwell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Shape feedback gives a deliberate action a tactile response without adding another control. */
@Composable
internal fun ExpressiveAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val radius by animateDpAsState(if (pressed) 14.dp else 28.dp,
        spring(dampingRatio = .8f, stiffness = 500f), label = "action shape")
    val scale by animateFloatAsState(if (pressed) .97f else 1f,
        spring(dampingRatio = .75f, stiffness = 500f), label = "action press")
    Button(onClick, modifier.heightIn(min = 56.dp).graphicsLayer { scaleX = scale; scaleY = scale }, enabled = enabled,
        shape = RoundedCornerShape(radius), interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), content = content)
}
