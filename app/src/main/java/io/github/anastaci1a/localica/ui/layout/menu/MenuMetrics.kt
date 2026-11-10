package io.github.anastaci1a.localica.ui.layout.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.ui.spec.*

/**
 * The join to the accepted `UiSpec` (DESIGN 5). It always goes through [MenuSpec.admit]. On main it supplies the
 * stock base and the stock menu proposal: explicit, temporary, and the only `StockUiSpec` read in menu code. When
 * the accepted `LocalUiSpec` is installed, this function supplies that value and the proposal in force instead.
 */
fun menuSpecBase(): MenuSpecResult = StockJoin
private val StockJoin: MenuSpecResult by lazy { MenuSpec.admit(StockUiSpec, StockMenuSpec) }

/** Resolved menu tokens: the accepted pair plus density, font scale and layout direction. Slots read only this. */
@Immutable class ResolvedMenuMetrics internal constructor(val spec: AcceptedMenuSpec, val density: Density, val direction: LayoutDirection) {
    fun dp(size: MenuSize): Dp = spec.dp(size)
    fun dp(gap: MenuGap): Dp = spec.dp(gap)
    /** One line of a role at the current font scale. */
    fun line(role: MenuTextRole): Dp = with(density) { spec.type(role).size.lineSp.sp.toDp() }
    /** Segment and tab cells follow the measured line, not a fixed height times the font scale. */
    fun segmentCell(role: MenuTextRole): Dp = line(role) + dp(MenuSize.SegmentPad) * 2
    /** Width a loading stat value reserves, and the capacity a ready value may use before the grid drops a column. */
    val statValueCapacity: Dp get() = line(MenuTextRole.StatValue) * 3
    override fun equals(other: Any?) = other is ResolvedMenuMetrics && spec == other.spec && density.density == other.density.density &&
        density.fontScale == other.density.fontScale && direction == other.direction
    override fun hashCode() = 31 * (31 * (31 * spec.hashCode() + density.density.hashCode()) + density.fontScale.hashCode()) + direction.hashCode()
}

/** The one resolver. Null means the token join was rejected; the scaffold then shows its failure state. */
@Composable fun rememberMenuMetrics(): ResolvedMenuMetrics? {
    val join = menuSpecBase()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return remember(join, density.density, density.fontScale, direction) {
        (join as? MenuSpecResult.Accepted)?.let { ResolvedMenuMetrics(it.spec, Density(density.density, density.fontScale), direction) }
    }
}

/** What every slot of one page shares. Provided by the scaffold; there is no per-page or per-row provider. */
@Immutable class MenuEnvironment internal constructor(val metrics: ResolvedMenuMetrics, val reservesLeading: Boolean, val contentWidth: Dp, val compact: Boolean) {
    override fun equals(other: Any?) = other is MenuEnvironment && metrics == other.metrics && reservesLeading == other.reservesLeading &&
        contentWidth == other.contentWidth && compact == other.compact
    override fun hashCode() = 31 * (31 * metrics.hashCode() + reservesLeading.hashCode()) + contentWidth.hashCode()
}
internal val LocalMenuEnvironment = staticCompositionLocalOf<MenuEnvironment> { error("Menu slots are hosted by the menu scaffold") }

/** How a text slot behaves beyond its capacity (DESIGN 7.4). */
enum class MenuTextFlow {
    /** A system label: wraps at word boundaries to at most two lines. One that cannot fit is a copy defect that invariant I4 reports. */
    Static,
    /** User data (record titles, names, paths): wraps at grapheme boundaries over as many lines as it needs and is never clipped. */
    Dynamic,
    /** A value: one line, moved as a whole by its slot, never split or ellipsized. */
    Value,
}
enum class MenuEmphasis { Normal, Disabled, Destructive }

/** Geometry-test handles of the menu system (unmerged semantics tree). */
enum class MenuPart { Row, Surface, Leading, Title, Summary, Helper, Value, Trailing, Heading, Caption, StatLabel, StatValue, Tab, Segment, Placeholder }
val MenuPartKey = SemanticsPropertyKey<MenuPart>("MenuPart")
val MenuTextRoleKey = SemanticsPropertyKey<MenuTextRole>("MenuTextRole")
val MenuTextFlowKey = SemanticsPropertyKey<MenuTextFlow>("MenuTextFlow")
fun Modifier.menuPart(part: MenuPart) = semantics { this[MenuPartKey] = part }

@Composable internal fun menuInk(ink: MenuInk, tone: NoticeTone?, emphasis: MenuEmphasis): Color {
    val base = when {
        emphasis == MenuEmphasis.Destructive -> MaterialTheme.colorScheme.error
        tone == NoticeTone.Error || ink == MenuInk.Error -> MaterialTheme.colorScheme.error
        ink == MenuInk.Primary -> LocalContentColor.current
        ink == MenuInk.Secondary -> MaterialTheme.colorScheme.onSurfaceVariant
        ink == MenuInk.Accent -> MaterialTheme.colorScheme.primary
        tone == NoticeTone.Warning -> LocalContentColor.current
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    return if (emphasis == MenuEmphasis.Disabled) base.copy(alpha = base.alpha * .45f) else base
}

/** The role table: the only place that names a text style in menu code. */
@Composable fun menuTextStyle(role: MenuTextRole): TextStyle {
    val type = LocalMenuEnvironment.current.metrics.spec.type(role)
    val family = MaterialTheme.typography.bodyLarge.fontFamily
    return remember(type, family) { TextStyle(fontFamily = family, fontSize = type.size.sp.sp, lineHeight = type.size.lineSp.sp,
        fontWeight = FontWeight(type.weight.value), letterSpacing = type.size.trackingSp.sp) }
}

/** The one text emitter of the menu system. Nothing else in menu code calls `Text` or names a text style. */
@Composable fun MenuText(role: MenuTextRole, text: String, modifier: Modifier = Modifier, flow: MenuTextFlow = MenuTextFlow.Static,
    emphasis: MenuEmphasis = MenuEmphasis.Normal, tone: NoticeTone? = null) {
    val type = LocalMenuEnvironment.current.metrics.spec.type(role)
    Text(text, modifier.semantics { this[MenuTextRoleKey] = role; this[MenuTextFlowKey] = flow }, color = menuInk(type.ink, tone, emphasis),
        style = menuTextStyle(role), softWrap = flow != MenuTextFlow.Value, overflow = TextOverflow.Visible,
        maxLines = when (flow) { MenuTextFlow.Static -> 2; MenuTextFlow.Dynamic -> Int.MAX_VALUE; MenuTextFlow.Value -> 1 })
}

/** A loadable text: the placeholder reserves exactly [Value.Loading.lines] lines of the role, so both faces share bounds within capacity. */
@Composable fun MenuValueText(role: MenuTextRole, value: Value<String>, modifier: Modifier = Modifier, flow: MenuTextFlow = MenuTextFlow.Value, width: Dp? = null) {
    when (value) {
        is Value.Ready -> MenuText(role, value.value, modifier, flow)
        is Value.Loading -> {
            val metrics = LocalMenuEnvironment.current.metrics
            val line = metrics.line(role)
            Box(modifier.menuPart(MenuPart.Placeholder).width(width ?: (line * 3)).height(line * value.lines)
                .background(LocalContentColor.current.copy(alpha = .12f), RoundedCornerShape(metrics.dp(MenuSize.HelperGap))))
        }
    }
}
