package dev.keel.app.ui

import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.keel.app.R

/**
 * One theme's colours, by role. Measured contrast decided the rules, so keep to them:
 * - [accentFill] with [onAccent] is the primary action. Marigold is too pale to use alone on Ice Silver.
 * - [accent] is for outlines, icons and progress. Cerulean is never small text (3.3-4.1:1).
 * - [danger] only means danger, overdue or over budget.
 * - Reading text only uses [text] and [textSecondary].
 */
data class KeelPalette(
    val page: Color,
    val surface: Color,
    val elevated: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentFill: Color,
    val onAccent: Color,
    val danger: Color,
    val onDanger: Color,
    val isDark: Boolean,
    /** Frosted glass: a see-through card fill, and the light catching its top edge. */
    val glass: Color,
    val glassEdge: Color,
    /** Two soft glows behind every screen, so the glass has something to show through it. */
    val glowA: Color,
    val glowB: Color,
)

/** Jet Black, Cerulean and Neon Rose. */
val DarkPalette = KeelPalette(
    page = Color(0xFF0A0A0A),
    surface = Color(0xFF141414),
    elevated = Color(0xFF222222),
    text = Color(0xFFF5F5F5),
    textSecondary = Color(0xFFA1A1AA),
    accent = Color(0xFF007BA7),
    accentFill = Color(0xFF007BA7),
    onAccent = Color(0xFFF5F5F5),
    danger = Color(0xFFFF2950),
    onDanger = Color(0xFF0A0A0A),
    isDark = true,
    glass = Color.White.copy(alpha = 0.055f),
    glassEdge = Color.White.copy(alpha = 0.14f),
    glowA = Color(0xFF007BA7).copy(alpha = 0.30f),
    glowB = Color(0xFFFF2950).copy(alpha = 0.10f),
)

/** Ice Silver, Deep Aubergine and Marigold. A muted mulberry marks warnings without shouting. */
val LightPalette = KeelPalette(
    page = Color(0xFFE4E9ED),
    surface = Color(0xFFF4F6F8),
    elevated = Color(0xFFD3DAE0),
    text = Color(0xFF3B0F40),
    textSecondary = Color(0xFF5D4763),
    accent = Color(0xFF3B0F40),
    accentFill = Color(0xFFEAA221),
    onAccent = Color(0xFF3B0F40),
    danger = Color(0xFF8A3A63),
    onDanger = Color(0xFFF4F6F8),
    isDark = false,
    glass = Color.White.copy(alpha = 0.58f),
    glassEdge = Color.White.copy(alpha = 0.95f),
    glowA = Color(0xFFEAA221).copy(alpha = 0.26f),
    glowB = Color(0xFF3B0F40).copy(alpha = 0.10f),
)

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Stored outside the database so the first frame and the pause screen can read it instantly. */
object ThemePrefs {
    private const val FILE = "appearance"
    private const val KEY = "theme"

    fun load(context: Context): ThemeMode = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getString(KEY, null)?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } } ?: ThemeMode.SYSTEM

    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY, mode.name) }
    }

    fun palette(context: Context, mode: ThemeMode = load(context)): KeelPalette = when (mode) {
        ThemeMode.LIGHT -> LightPalette
        ThemeMode.DARK -> DarkPalette
        ThemeMode.SYSTEM -> {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_YES) DarkPalette else LightPalette
        }
    }
}

/** The colours in force. Reading these from a screen redraws it when the theme changes. */
object KeelColor {
    var palette by mutableStateOf(DarkPalette)
    val Page get() = palette.page
    val Surface get() = palette.surface
    val Elevated get() = palette.elevated
    val Text get() = palette.text
    val TextSecondary get() = palette.textSecondary
    val Accent get() = palette.accent
    val AccentFill get() = palette.accentFill
    val OnAccent get() = palette.onAccent
    val Danger get() = palette.danger
    val OnDanger get() = palette.onDanger
    val Glass get() = palette.glass
    val GlassEdge get() = palette.glassEdge

    /** An input's edge at rest. Half-strength secondary keeps it visible without shouting. */
    val InputEdge get() = palette.textSecondary.copy(alpha = 0.5f)
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

/** JetBrainsMono Nerd Font, bundled so nothing is downloaded. */
val KeelFontFamily = FontFamily(
    Font(R.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(R.font.jetbrainsmono_medium, FontWeight.Medium),
    Font(R.font.jetbrainsmono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrainsmono_bold, FontWeight.Bold),
)

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = KeelFontFamily,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
)

// A monospace font runs wide, so sizes sit a step below a proportional scale, and headings stay untracked.
val KeelTypography = Typography(
    displaySmall = style(38, 44, FontWeight.SemiBold, -0.02),
    headlineLarge = style(28, 34, FontWeight.SemiBold, -0.015),
    headlineMedium = style(28, 34, FontWeight.SemiBold, -0.015),
    headlineSmall = style(22, 28, FontWeight.SemiBold, -0.01),
    titleLarge = style(18, 25, FontWeight.SemiBold),
    titleMedium = style(15, 21, FontWeight.SemiBold),
    titleSmall = style(13, 19, FontWeight.SemiBold),
    bodyLarge = style(15, 23),
    bodyMedium = style(13, 20),
    bodySmall = style(12, 17),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(11, 16, FontWeight.Medium, 0.05),
)

/** For numbers that change, so digits keep one width and nothing shifts. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

private fun colorSchemeFor(p: KeelPalette): ColorScheme {
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accentFill,
        onPrimary = p.onAccent,
        primaryContainer = p.elevated,
        onPrimaryContainer = p.text,
        secondaryContainer = p.elevated,
        onSecondaryContainer = p.text,
        background = p.page,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surface,
        onSurfaceVariant = p.textSecondary,
        // Dialogs sit on surface, where danger text stays readable in both themes.
        surfaceContainer = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainerLowest = p.surface,
        surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.elevated,
        surfaceBright = p.elevated,
        surfaceDim = p.page,
        outline = p.textSecondary.copy(alpha = 0.5f),
        outlineVariant = p.elevated,
        error = p.danger,
        onError = p.onDanger,
        scrim = Color.Black,
    )
}

val KeelShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun KeelTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkPalette else LightPalette
    KeelColor.palette = palette
    MaterialTheme(colorScheme = colorSchemeFor(palette), typography = KeelTypography, shapes = KeelShapes, content = content)
}

/**
 * The backdrop behind every screen: the page colour with two wide, soft glows in the theme's accents.
 * It's drawn once and doesn't move, so it costs nothing while scrolling.
 */
fun Modifier.keelBackdrop(palette: KeelPalette): Modifier = drawBehind {
    drawRect(palette.page)
    val reach = maxOf(size.width, size.height)
    drawCircle(
        Brush.radialGradient(listOf(palette.glowA, Color.Transparent), Offset(size.width * 0.1f, size.height * 0.02f), reach * 0.75f),
        radius = reach * 0.75f,
        center = Offset(size.width * 0.1f, size.height * 0.02f),
    )
    drawCircle(
        Brush.radialGradient(listOf(palette.glowB, Color.Transparent), Offset(size.width * 0.95f, size.height * 0.85f), reach * 0.7f),
        radius = reach * 0.7f,
        center = Offset(size.width * 0.95f, size.height * 0.85f),
    )
}

/** A frosted-glass surface: see-through fill, a faint sheen at the top, and an edge that catches light. */
fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape): Modifier = this
    .clip(shape)
    .drawBehind {
        drawRect(KeelColor.Glass)
        drawRect(Brush.verticalGradient(listOf(KeelColor.GlassEdge.copy(alpha = KeelColor.GlassEdge.alpha * 0.25f), Color.Transparent), endY = size.height * 0.5f))
    }
    .border(
        BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(KeelColor.GlassEdge, KeelColor.GlassEdge.copy(alpha = KeelColor.GlassEdge.alpha * 0.2f))),
        ),
        shape,
    )

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
        tone == ButtonTone.Primary -> KeelColor.AccentFill
        tone == ButtonTone.Danger -> KeelColor.Danger
        else -> KeelColor.Elevated
    }
    val foreground = when {
        !enabled -> KeelColor.TextSecondary
        tone == ButtonTone.Danger -> KeelColor.OnDanger
        tone == ButtonTone.Primary -> KeelColor.OnAccent
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
            androidx.compose.material3.ProvideTextStyle(if (tone == ButtonTone.Quiet) labelStyle.copy(fontSize = 14.sp) else labelStyle) {
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

/**
 * A choice. Selection shows as an outline and a filled background, not by colour alone, and every part
 * moves on one shared timing so the highlight fades as a single thing. [pending] marks a choice that
 * takes effect later, so a deferred tap still visibly registers.
 */
@Composable
fun KeelChip(selected: Boolean, onClick: () -> Unit, pending: Boolean = false, label: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val spec = tween<Float>(KeelMotion.STATE_MS, easing = KeelMotion.EaseOut)
    val chosen by animateFloatAsState(if (selected) 1f else 0f, spec, label = "chipSelected")
    val later by animateFloatAsState(if (pending && !selected) 1f else 0f, spec, label = "chipPending")
    // On glass, a resting edge needs to be a see-through line, not a flat grey that vanishes into the card.
    val edge = lerp(lerp(KeelColor.TextSecondary.copy(alpha = 0.32f), KeelColor.TextSecondary, later), KeelColor.Accent, chosen)
    val text = lerp(KeelColor.TextSecondary, KeelColor.Text, maxOf(chosen, later))
    Box(
        Modifier
            .pressScale(source)
            .heightIn(min = 44.dp)
            .clip(MaterialTheme.shapes.small)
            .background(KeelColor.Elevated.copy(alpha = chosen))
            .border(BorderStroke(1.dp + 0.5.dp * maxOf(chosen, later), edge), MaterialTheme.shapes.small)
            .clickable(interactionSource = source, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides text) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelMedium) { label() }
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
            checkedThumbColor = KeelColor.OnAccent,
            checkedTrackColor = KeelColor.AccentFill,
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
        if (checked) KeelColor.AccentFill else Color.Transparent,
        tween(KeelMotion.STATE_MS, easing = KeelMotion.EaseOut),
        label = "check",
    )
    val tapArea = if (onCheckedChange != null) {
        // Toggleable, not just clickable, so screen readers announce it as ticked or unticked.
        Modifier.toggleable(
            value = checked,
            interactionSource = source,
            indication = null,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
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
                Icon(Icons.Rounded.Check, contentDescription = "Done", tint = KeelColor.OnAccent, modifier = Modifier.size(16.dp))
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
