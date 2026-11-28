package uk.jj.swarm

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The 2.5-second animated opening screen shown after Android's own splash. It starts on the same
 * background with the icon in the same place, so the hand-over is seamless, then lights a ring
 * round the icon, brings in the name and line, and dissolves into the app drawn underneath.
 */
@Composable
fun OpeningScreen(
    name: String,
    tagline: String,
    background: Color,
    ink: Color,
    accent: Color,
    iconBackground: Color,
    onFinished: () -> Unit,
    iconBackgroundRes: Int? = null,
) {
    val icon = remember { Animatable(0.92f) }
    val ring = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val line = remember { Animatable(0f) }
    val out = remember { Animatable(0f) }
    val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { icon.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
            launch { delay(120); ring.animateTo(1f, tween(780, easing = FastOutSlowInEasing)) }
            launch { delay(160); glow.animateTo(1f, tween(900, easing = ease)) }
            launch { delay(380); title.animateTo(1f, tween(560, easing = ease)) }
            launch { delay(620); line.animateTo(1f, tween(520, easing = ease)) }
            launch { delay(2100); out.animateTo(1f, tween(400, easing = LinearEasing)) }
        }
        onFinished()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - out.value }
            .background(background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = 150.dp.toPx() * (0.6f + 0.4f * glow.value)
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.30f * glow.value * (1f - out.value)), Color.Transparent), c, r),
                radius = r, center = c,
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val s = 1f + 0.05f * out.value
                scaleX = s; scaleY = s
            },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
                Canvas(Modifier.size(132.dp)) {
                    val w = 3.dp.toPx()
                    drawArc(
                        brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0f), accent, accent)),
                        startAngle = -90f, sweepAngle = 360f * ring.value, useCenter = false,
                        topLeft = Offset(w, w), size = Size(size.width - 2 * w, size.height - 2 * w),
                        style = Stroke(width = w, cap = StrokeCap.Round),
                        alpha = 1f - 0.6f * out.value,
                    )
                }
                Box(
                    Modifier
                        .size(104.dp)
                        .graphicsLayer { scaleX = icon.value; scaleY = icon.value }
                        .clip(RoundedCornerShape(30.dp))
                        .background(iconBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    if (iconBackgroundRes != null) Image(painterResource(iconBackgroundRes), null, Modifier.size(104.dp).graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
                    Image(painterResource(R.drawable.ic_launcher_fg), null, Modifier.size(104.dp).graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.TopCenter).layout { m, c ->
                    val pl = m.measure(c.copy(minHeight = 0))
                    layout(c.maxWidth, c.maxHeight) { pl.place((c.maxWidth - pl.width) / 2, c.maxHeight / 2 + 66.dp.roundToPx() + 26.dp.roundToPx()) }
                },
            ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = title.value
                    translationY = (1f - title.value) * 18.dp.toPx()
                },
            ) {
                androidx.compose.foundation.text.BasicText(
                    name,
                    style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (0.5f + 2.5f * (1f - title.value)).sp, color = ink),
                )
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.text.BasicText(
                tagline,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink.copy(alpha = 0.66f)),
                modifier = Modifier.graphicsLayer {
                    alpha = line.value
                    translationY = (1f - line.value) * 12.dp.toPx()
                },
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .size(width = (56f * line.value).dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            }
        }
    }
}
