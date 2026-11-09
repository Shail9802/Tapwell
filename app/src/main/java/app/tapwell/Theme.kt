package app.tapwell

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos

internal enum class Symbol(@param:DrawableRes val drawable: Int) {
    BRAND(R.drawable.ic_tapwell_mark),
    TAP(R.drawable.ic_reminder),
    LOCK(R.drawable.ic_lock),
    WATER(R.drawable.ic_water),
    STAND(R.drawable.ic_stand),
    HABITS(R.drawable.ic_habits),
    SETTINGS(R.drawable.ic_settings),
    PLUS(R.drawable.ic_plus),
    PLACE(R.drawable.ic_place)
}

@Composable
internal fun TapTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dark = isSystemInDarkTheme()
    val store = remember(context) { Store.get(context) }
    val revision by store.revision.collectAsState()
    val followsDevice = remember(revision) { store.wallpaperColors() }
    val wallpaperColors = followsDevice && devicePaletteEnabled(context, configuration)
    val colors = remember(context, configuration, dark, wallpaperColors) {
        when {
            wallpaperColors && dark -> dynamicDarkColorScheme(context)
            wallpaperColors -> dynamicLightColorScheme(context)
            dark -> TapwellDark
            else -> TapwellLight
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private const val SAMSUNG_PALETTE_STATE = "wallpapertheme_state"

@Composable
private fun devicePaletteEnabled(context: Context, configuration: Configuration): Boolean {
    if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return true
    val resolver = context.contentResolver
    val enabled by produceState(readSamsungPalette(context), context, configuration) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                value = readSamsungPalette(context)
            }
        }
        value = readSamsungPalette(context)
        try {
            resolver.registerContentObserver(Settings.System.getUriFor(SAMSUNG_PALETTE_STATE), false, observer)
        } catch (_: SecurityException) {
            return@produceState
        }
        awaitDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private fun readSamsungPalette(context: Context): Boolean =
    try {
        Settings.System.getInt(context.contentResolver, SAMSUNG_PALETTE_STATE, 1) != 0
    } catch (_: SecurityException) {
        // Other One UI versions may expose only the public Android palette resources.
        true
    }

private val TapwellLight = lightColorScheme(
    primary = Color(0xFF4F406A), onPrimary = Color.White,
    primaryContainer = Color(0xFFECE0FF), onPrimaryContainer = Color(0xFF231D36),
    secondary = Color(0xFF456127), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F3B0), onSecondaryContainer = Color(0xFF192807),
    tertiary = Color(0xFF75525F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E4), onTertiaryContainer = Color(0xFF30101F),
    background = Color(0xFFFDF8FF), surface = Color(0xFFFDF8FF),
    onBackground = Color(0xFF211D29), onSurface = Color(0xFF211D29),
    surfaceVariant = Color(0xFFEAE2F0), onSurfaceVariant = Color(0xFF4B4455),
    outline = Color(0xFF7A7283), outlineVariant = Color(0xFFCEC4D6),
    surfaceDim = Color(0xFFDED7E4), surfaceBright = Color(0xFFFDF8FF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F0FC),
    surfaceContainer = Color(0xFFF1EAF6), surfaceContainerHigh = Color(0xFFEBE4F0),
    surfaceContainerHighest = Color(0xFFE5DEEA),
    inverseSurface = Color(0xFF362F40), inverseOnSurface = Color(0xFFF6EFFA),
    inversePrimary = Color(0xFFD8C5F5)
)

private val TapwellDark = darkColorScheme(
    primary = Color(0xFFD8C5F5), onPrimary = Color(0xFF34254F),
    primaryContainer = Color(0xFF4F406A), onPrimaryContainer = Color(0xFFECE0FF),
    secondary = Color(0xFFD5F3B0), onSecondary = Color(0xFF233711),
    secondaryContainer = Color(0xFF354D1E), onSecondaryContainer = Color(0xFFD5F3B0),
    tertiary = Color(0xFFE5B9C9), onTertiary = Color(0xFF452533),
    tertiaryContainer = Color(0xFF5E3A48), onTertiaryContainer = Color(0xFFFFD9E4),
    background = Color(0xFF17131D), surface = Color(0xFF17131D),
    onBackground = Color(0xFFEAE2F0), onSurface = Color(0xFFEAE2F0),
    surfaceVariant = Color(0xFF4B4455), onSurfaceVariant = Color(0xFFCEC4D6),
    outline = Color(0xFF9A90A3), outlineVariant = Color(0xFF4B4455),
    surfaceDim = Color(0xFF17131D), surfaceBright = Color(0xFF3D3546),
    surfaceContainerLowest = Color(0xFF120F18), surfaceContainerLow = Color(0xFF201B27),
    surfaceContainer = Color(0xFF241F2C), surfaceContainerHigh = Color(0xFF2E2836),
    surfaceContainerHighest = Color(0xFF393241),
    inverseSurface = Color(0xFFEAE2F0), inverseOnSurface = Color(0xFF362F40),
    inversePrimary = Color(0xFF4F406A)
)

@Composable
internal fun TapIcon(symbol: Symbol, modifier: Modifier = Modifier) {
    Icon(painterResource(symbol.drawable), contentDescription = null, modifier = modifier.size(24.dp))
}

@Composable
internal fun IconBadge(symbol: Symbol, prominent: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (prominent) colors.primary else colors.secondaryContainer,
        contentColor = if (prominent) colors.onPrimary else colors.onSecondaryContainer
    ) {
        Box(Modifier.size(if (prominent) 56.dp else 44.dp), contentAlignment = Alignment.Center) {
            TapIcon(symbol)
        }
    }
}

@Composable
internal fun NfcTapAnimation(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "NFC tap")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1800, easing = LinearEasing)),
        label = "Tap progress"
    )
    Canvas(modifier) {
        val unit = size.width / 206f
        val center = Offset(size.width * 0.53f, size.height * 0.49f)
        val stroke = Stroke(2 * unit)
        for (offset in listOf(0f, 0.5f)) {
            val ripple = (phase + offset) % 1f
            drawCircle(colors.primary.copy(alpha = 0.34f * (1f - ripple)), (14 + 40 * ripple) * unit, center, style = stroke)
        }
        val phone = Offset(center.x - 62 * unit, center.y - 49 * unit)
        val phoneSize = Size(58 * unit, 98 * unit)
        drawRoundRect(colors.primaryContainer, phone, phoneSize, CornerRadius(13 * unit))
        drawRoundRect(colors.primary, phone, phoneSize, CornerRadius(13 * unit), style = stroke)
        drawRoundRect(
            colors.onPrimaryContainer,
            phone + Offset(19 * unit, 8 * unit),
            Size(20 * unit, 3 * unit),
            CornerRadius(2 * unit)
        )
        val approach = ((1 - cos(2 * PI * phase)) / 2).toFloat()
        val tag = Offset(center.x + (24 - 18 * approach) * unit, center.y - 17 * unit)
        val tagSize = Size(58 * unit, 34 * unit)
        drawRoundRect(colors.secondaryContainer, tag, tagSize, CornerRadius(8 * unit))
        drawRoundRect(colors.secondary, tag, tagSize, CornerRadius(8 * unit), style = stroke)
        val chip = tag + Offset(15 * unit, 17 * unit)
        drawCircle(colors.onSecondaryContainer, 3 * unit, chip)
        for (radius in listOf(8f, 12f)) {
            drawArc(
                colors.onSecondaryContainer,
                startAngle = -48f,
                sweepAngle = 96f,
                useCenter = false,
                topLeft = chip - Offset(radius * unit, radius * unit),
                size = Size(2 * radius * unit, 2 * radius * unit),
                style = stroke
            )
        }
    }
}

@Composable
internal fun liveNow(store: Store): Long {
    val revision by store.revision.collectAsState()
    val tick by produceState(0L) {
        while (true) {
            delay(1000)
            value++
        }
    }
    return remember(revision, tick) { System.currentTimeMillis() }
}
