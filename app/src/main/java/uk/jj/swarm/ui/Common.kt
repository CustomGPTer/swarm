@file:OptIn(ExperimentalMaterial3Api::class)

package uk.jj.swarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.jj.swarm.Prefs
import kotlin.math.cos
import kotlin.math.sin

val Teal = Color(0xFF0B8F7E)
val TealLight = Color(0xFF43D1BE)

private val LightColors = lightColorScheme(
    primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFD3F1EB), onPrimaryContainer = Color(0xFF00382F),
    secondary = Color(0xFF4A635E), secondaryContainer = Color(0xFFE2F2EE), onSecondaryContainer = Color(0xFF06201B),
    background = Color.White, surface = Color.White, onSurface = Color(0xFF1F2325), onBackground = Color(0xFF1F2325),
    surfaceVariant = Color(0xFFEFF3F2), onSurfaceVariant = Color(0xFF6C747A), outline = Color(0xFFB8C0C4), outlineVariant = Color(0xFFE4E8EA),
    surfaceContainer = Color(0xFFF6F8F8), surfaceContainerHigh = Color(0xFFF0F3F3), surfaceContainerLow = Color(0xFFFAFBFB), surfaceContainerHighest = Color(0xFFE9EDED),
    error = Color(0xFFC62828),
)
private val DarkColors = darkColorScheme(
    primary = TealLight, onPrimary = Color(0xFF00382F), primaryContainer = Color(0xFF0E4A42), onPrimaryContainer = Color(0xFFB5F0E6),
    secondary = Color(0xFFB1CCC6), secondaryContainer = Color(0xFF23393A), onSecondaryContainer = Color(0xFFCDE8E2),
    background = Color(0xFF101314), surface = Color(0xFF101314), onSurface = Color(0xFFE6EAEA), onBackground = Color(0xFFE6EAEA),
    surfaceVariant = Color(0xFF1D2224), onSurfaceVariant = Color(0xFFA1ABAF), outline = Color(0xFF4A5357), outlineVariant = Color(0xFF2A3033),
    surfaceContainer = Color(0xFF171B1C), surfaceContainerHigh = Color(0xFF1D2224), surfaceContainerLow = Color(0xFF141718), surfaceContainerHighest = Color(0xFF252B2D),
    error = Color(0xFFFF8A80),
)

@Composable
fun isDark(): Boolean {
    val mode by Prefs.theme.collectAsState()
    return when (mode) { 1 -> true; 2 -> isSystemInDarkTheme(); else -> false }
}

@Composable
fun SwarmTheme(content: @Composable () -> Unit) {
    val dark = isDark()
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) SideEffect {
        val w = (view.context as? android.app.Activity)?.window
        if (w != null) androidx.core.view.WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

@Composable
fun headerBrush(): Brush = if (isDark()) Brush.linearGradient(listOf(Color(0xFF12322E), Color(0xFF16222B)))
else Brush.linearGradient(listOf(Color(0xFFD2F1EA), Color(0xFFE6F1FA)))

/** The app's standard page: tinted header with a title and actions, then a white card with rounded top corners. */
@Composable
fun Page(
    title: String,
    nav: ImageVector? = null,
    onNav: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    insetBottom: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(headerBrush())) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (header != null) header() else Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (nav != null) IconButton(onClick = onNav) { Icon(nav, null, tint = MaterialTheme.colorScheme.onSurface) } else Spacer(Modifier.width(16.dp))
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
                actions()
            }
            Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), color = MaterialTheme.colorScheme.surface) {
                Column(if (insetBottom) Modifier.fillMaxSize().navigationBarsPadding().imePadding() else Modifier.fillMaxSize(), content = content)
            }
        }
    }
}

@Composable
fun BackPage(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) =
    Page(title, Icons.AutoMirrored.Filled.ArrowBack, onBack, actions, null, true, content)

/** Header variant with a search box, like the filter bars in the reference app. */
@Composable
fun SearchHeader(value: String, onValue: (String) -> Unit, hint: String, onClose: (() -> Unit)?, onSearch: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) leading() else if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") } else Spacer(Modifier.width(12.dp))
        Surface(Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    if (value.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    androidx.compose.foundation.text.BasicTextField(
                        value, onValue, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (value.isNotEmpty()) Icon(Icons.Filled.Close, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable { onValue("") })
            }
        }
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search") }
    }
}

/** Text tabs with a short rounded underline under the selected one. */
@Composable
fun SubTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 14.dp, bottom = 4.dp)) {
        tabs.forEachIndexed { i, t ->
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(t, fontSize = 17.sp, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal, color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(28.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i == selected) MaterialTheme.colorScheme.primary else Color.Transparent))
            }
        }
    }
}

private fun DrawScope.hex(cx: Float, cy: Float, r: Float, color: Color, stroke: Float? = null) {
    val p = Path()
    for (i in 0 until 6) {
        val a = Math.toRadians(60.0 * i - 90.0)
        val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    if (stroke != null) drawPath(p, color, style = Stroke(stroke)) else drawPath(p, color)
}

/** Swarm's own empty-state picture: a small honeycomb. */
@Composable
fun Honeycomb(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(170.dp, 130.dp)) {
        val r = size.height * 0.25f
        val cx = size.width / 2; val cy = size.height * 0.36f
        val dx = r * 0.92f; val dy = r * 1.56f
        drawOval(c.copy(alpha = 0.07f), topLeft = Offset(size.width * 0.05f, size.height * 0.72f), size = androidx.compose.ui.geometry.Size(size.width * 0.9f, size.height * 0.26f))
        hex(cx - dx, cy + dy, r, c.copy(alpha = 0.22f))
        hex(cx + dx, cy + dy, r, c.copy(alpha = 0.34f))
        hex(cx, cy, r, c.copy(alpha = 0.55f))
        hex(cx - 2 * dx, cy, r * 0.55f, c.copy(alpha = 0.18f), 3f)
        hex(cx + 2 * dx, cy, r * 0.55f, c.copy(alpha = 0.18f), 3f)
        val w = r * 0.16f
        drawLine(Color.White, Offset(cx, cy - r * 0.42f), Offset(cx, cy + r * 0.3f), w, StrokeCap.Round)
        drawLine(Color.White, Offset(cx - r * 0.3f, cy + r * 0.02f), Offset(cx, cy + r * 0.34f), w, StrokeCap.Round)
        drawLine(Color.White, Offset(cx + r * 0.3f, cy + r * 0.02f), Offset(cx, cy + r * 0.34f), w, StrokeCap.Round)
    }
}

@Composable
fun EmptyState(line1: String, line2: String, button: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(top = 70.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Honeycomb()
        Spacer(Modifier.height(14.dp))
        Text(line1, fontSize = 16.sp, textAlign = TextAlign.Center)
        Text(line2, fontSize = 16.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
        if (button != null) {
            Spacer(Modifier.height(22.dp))
            Button(onClick = onClick, shape = RoundedCornerShape(26.dp), contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp)) { Text(button, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
}

/** Two-button dialog in the style of the reference app. */
@Composable
fun ChoiceDialog(title: String, no: String, yes: String, onNo: () -> Unit, onYes: () -> Unit, body: @Composable ColumnScope.() -> Unit = {}) {
    BasicAlertDialog(onDismissRequest = onNo) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    body()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.weight(1f).clickable(onClick = onNo).padding(vertical = 18.dp), contentAlignment = Alignment.Center) { Text(no.uppercase(), fontWeight = FontWeight.Bold) }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1f).clickable(onClick = onYes).padding(vertical = 18.dp), contentAlignment = Alignment.Center) { Text(yes.uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@Composable
fun Banner(text: String, color: Color = MaterialTheme.colorScheme.primaryContainer, onColor: Color = MaterialTheme.colorScheme.onPrimaryContainer, action: String? = null, onAction: () -> Unit = {}) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = color) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = onColor, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(action, fontSize = 13.sp) }
        }
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant, onColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 7.dp, vertical = 2.dp)) { Text(text, fontSize = 11.sp, color = onColor, maxLines = 1) }
}

/** Green / amber / red dot for swarm health. */
@Composable
fun HealthDot(seeders: Int) {
    val c = when { seeders < 0 -> MaterialTheme.colorScheme.outline; seeders == 0 -> Color(0xFFD32F2F); seeders < 5 -> Color(0xFFF59E0B); else -> Color(0xFF2E9E5B) }
    Box(Modifier.size(9.dp).clip(CircleShape).background(c))
}

@Composable
fun Labeled(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.width(118.dp))
        Text(value, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SectionTitle(icon: ImageVector?, text: String) {
    Row(Modifier.padding(start = 22.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(12.dp)) }
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
