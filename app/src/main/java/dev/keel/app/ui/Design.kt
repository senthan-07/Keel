package dev.keel.app.ui

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.keel.app.R

/**
 * Keel's colour roles. Measured contrast decided the rules, so keep to them:
 * - [Accent] (Cerulean) is for fills, outlines, icons and progress, never small text (3.3-4.1:1).
 * - [Danger] (Neon Rose) only means danger, overdue or over budget, and is readable on page and surface.
 * - Reading text only uses [Text] and [TextSecondary] (6.2:1 or better on every surface).
 */
object KeelColor {
    val Page = Color(0xFF0A0A0A)
    val Surface = Color(0xFF141414)
    val Elevated = Color(0xFF222222)
    val Text = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFA1A1AA)
    val Accent = Color(0xFF007BA7)
    val Danger = Color(0xFFFF2950)

    /** An input's edge at rest. Half-strength secondary keeps it visible without shouting. */
    val InputEdge = TextSecondary.copy(alpha = 0.5f)
}

/** Spacing on a 4 dp grid. */
object KeelSpace {
    val Gutter = 20.dp
    val Section = 28.dp
    val Item = 12.dp
    val CardPadding = 18.dp
}

/** Motion: fast, ease-out, nothing over 300 ms, and every press answers at once. */
object KeelMotion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    const val PRESS_SCALE = 0.96f
    const val PRESS_MS = 150
    const val STATE_MS = 150
    const val PUSH_MS = 220
    const val PROGRESS_MS = 300
}

/** True when the phone's animations are on. Movement and scale are skipped when they're off. */
@Composable
fun motionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: FontWeight) = Font(
    R.font.inter_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val InterFamily = FontFamily(
    inter(FontWeight.Normal),
    inter(FontWeight.Medium),
    inter(FontWeight.SemiBold),
    inter(FontWeight.Bold),
)

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = InterFamily,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
)

val KeelTypography = Typography(
    displaySmall = style(44, 48, FontWeight.SemiBold, -0.025),
    headlineLarge = style(32, 38, FontWeight.SemiBold, -0.02),
    headlineMedium = style(32, 38, FontWeight.SemiBold, -0.02),
    headlineSmall = style(26, 32, FontWeight.SemiBold, -0.015),
    titleLarge = style(20, 26, FontWeight.SemiBold, -0.01),
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24),
    bodyMedium = style(14, 21),
    bodySmall = style(13, 18),
    labelLarge = style(16, 20, FontWeight.SemiBold),
    labelMedium = style(14, 18, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium, 0.05),
)

/** For numbers that change, so digits keep one width and nothing shifts. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

val KeelColorScheme: ColorScheme = darkColorScheme(
    primary = KeelColor.Accent,
    onPrimary = KeelColor.Text,
    primaryContainer = KeelColor.Elevated,
    onPrimaryContainer = KeelColor.Text,
    secondaryContainer = KeelColor.Elevated,
    onSecondaryContainer = KeelColor.Text,
    background = KeelColor.Page,
    onBackground = KeelColor.Text,
    surface = KeelColor.Surface,
    onSurface = KeelColor.Text,
    surfaceVariant = KeelColor.Surface,
    onSurfaceVariant = KeelColor.TextSecondary,
    // Dialogs sit on Surface, where Neon Rose text stays readable.
    surfaceContainer = KeelColor.Surface,
    surfaceContainerLow = KeelColor.Surface,
    surfaceContainerLowest = KeelColor.Surface,
    surfaceContainerHigh = KeelColor.Surface,
    surfaceContainerHighest = KeelColor.Elevated,
    surfaceBright = KeelColor.Elevated,
    surfaceDim = KeelColor.Page,
    outline = KeelColor.InputEdge,
    outlineVariant = KeelColor.Elevated,
    error = KeelColor.Danger,
    onError = KeelColor.Page,
    scrim = Color.Black,
)

val KeelShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun KeelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KeelColorScheme, typography = KeelTypography, shapes = KeelShapes, content = content)
}

/** The press response every tappable thing shares. Disabled controls never scale. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, enabled: Boolean = true): Modifier {
    val pressed by source.collectIsPressedAsState()
    val animate = motionEnabled()
    val scale by animateFloatAsState(
        if (pressed && enabled && animate) KeelMotion.PRESS_SCALE else 1f,
        tween(KeelMotion.PRESS_MS, easing = KeelMotion.EaseOut),
        label = "press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

private enum class ButtonTone { Primary, Secondary, Danger, Quiet }

@Composable
private fun KeelButtonBase(
    tone: ButtonTone,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.medium
    val background = when {
        tone == ButtonTone.Quiet -> Color.Transparent
        !enabled -> KeelColor.Elevated
        tone == ButtonTone.Primary -> KeelColor.Accent
        tone == ButtonTone.Danger -> KeelColor.Danger
        else -> KeelColor.Elevated
    }
    val foreground = when {
        !enabled -> KeelColor.TextSecondary
        tone == ButtonTone.Danger -> KeelColor.Page
        else -> KeelColor.Text
    }
    CompositionLocalProvider(LocalContentColor provides foreground) {
        Row(
            modifier
                .pressScale(source, enabled)
                .heightIn(min = if (tone == ButtonTone.Quiet) 44.dp else 52.dp)
                .widthIn(min = 48.dp)
                .clip(shape)
                .background(background)
                .clickable(
                    interactionSource = source,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                    indication = androidx.compose.material3.ripple(color = foreground),
                )
                .padding(horizontal = if (tone == ButtonTone.Quiet) 10.dp else 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val labelStyle = MaterialTheme.typography.labelLarge
            androidx.compose.material3.ProvideTextStyle(if (tone == ButtonTone.Quiet) labelStyle.copy(fontSize = 15.sp) else labelStyle) {
                content()
            }
        }
    }
}

/** The one filled action on a view. */
@Composable
fun KeelButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    KeelButtonBase(ButtonTone.Primary, onClick, modifier, enabled, content)

@Composable
fun KeelSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    KeelButtonBase(ButtonTone.Secondary, onClick, modifier, enabled, content)

@Composable
fun KeelTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    KeelButtonBase(ButtonTone.Quiet, onClick, modifier, enabled, content)

@Composable
fun KeelDangerButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    KeelButtonBase(ButtonTone.Danger, onClick, modifier, enabled, content)

/** A choice. Selection shows as an outline and a heavier label, not by colour alone. */
@Composable
fun KeelChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val edge by animateColorAsState(
        if (selected) KeelColor.Accent else KeelColor.Elevated,
        tween(KeelMotion.STATE_MS, easing = KeelMotion.EaseOut),
        label = "chip",
    )
    Box(
        Modifier
            .pressScale(source)
            .heightIn(min = 44.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) KeelColor.Elevated else Color.Transparent)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, edge), MaterialTheme.shapes.small)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val base = MaterialTheme.typography.labelMedium
        CompositionLocalProvider(LocalContentColor provides if (selected) KeelColor.Text else KeelColor.TextSecondary) {
            androidx.compose.material3.ProvideTextStyle(if (selected) base.copy(fontWeight = FontWeight.SemiBold) else base) { label() }
        }
    }
}

/** Chips wrap instead of running off a narrow screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun KeelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value,
        onValueChange,
        modifier,
        label = label,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KeelColor.Accent,
            unfocusedBorderColor = KeelColor.InputEdge,
            focusedLabelColor = KeelColor.TextSecondary,
            unfocusedLabelColor = KeelColor.TextSecondary,
            cursorColor = KeelColor.Text,
            focusedTextColor = KeelColor.Text,
            unfocusedTextColor = KeelColor.Text,
        ),
    )
}

@Composable
fun KeelSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked,
        onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = KeelColor.Text,
            checkedTrackColor = KeelColor.Accent,
            checkedBorderColor = KeelColor.Accent,
            uncheckedThumbColor = KeelColor.TextSecondary,
            uncheckedTrackColor = KeelColor.Elevated,
            uncheckedBorderColor = KeelColor.InputEdge,
        ),
    )
}

/** A round tick, with the tap area set to 48 dp even though the circle is smaller. */
@Composable
fun KeelCheck(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val fill by animateColorAsState(
        if (checked) KeelColor.Accent else Color.Transparent,
        tween(KeelMotion.STATE_MS, easing = KeelMotion.EaseOut),
        label = "check",
    )
    val tapArea = if (onCheckedChange != null) {
        Modifier.clickable(
            interactionSource = source,
            indication = null,
            role = Role.Checkbox,
            onClick = { onCheckedChange(!checked) },
        )
    } else {
        Modifier
    }
    Box(modifier.size(48.dp).then(tapArea), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .pressScale(source)
                .size(24.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, if (checked) KeelColor.Accent else KeelColor.TextSecondary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Icons.Rounded.Check, contentDescription = "Done", tint = KeelColor.Text, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun KeelProgress(fraction: Float, modifier: Modifier = Modifier, danger: Boolean = false) {
    val shown by animateFloatAsState(
        fraction.coerceIn(0f, 1f),
        tween(KeelMotion.PROGRESS_MS, easing = KeelMotion.EaseOut),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(KeelColor.Elevated),
    ) {
        Box(
            Modifier
                .fillMaxWidth(shown)
                .height(6.dp)
                .clip(CircleShape)
                .background(if (danger) KeelColor.Danger else KeelColor.Accent),
        )
    }
}

enum class Tone { Good, Danger, Neutral }

/** A short status: a dot for scanning, and words that carry the meaning on their own. */
@Composable
fun StatusLine(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Good) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when (tone) {
                        Tone.Good -> KeelColor.Accent
                        Tone.Danger -> KeelColor.Danger
                        Tone.Neutral -> KeelColor.TextSecondary
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (tone == Tone.Danger) KeelColor.Danger else KeelColor.Text,
        )
    }
}

/** A large number and what it means, for the headline figures. */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.displaySmall.tabular, color = KeelColor.Text)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = KeelColor.TextSecondary)
    }
}
