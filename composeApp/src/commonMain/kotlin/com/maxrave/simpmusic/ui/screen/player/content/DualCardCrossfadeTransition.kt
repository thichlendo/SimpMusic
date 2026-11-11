package com.maxrave.simpmusic.ui.screen.player.content

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.expect.ui.toImageBitmap
import com.maxrave.simpmusic.expect.ui.toReadableBitmap
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.screen.player.content.applemusic.appleMusicVerticalFadeEdges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// 3D Dual-Card layout & motion constants
private const val HOLD_SCALE = 0.58f
private const val LEFT_SLOT_FRACTION = 0.26f
private const val RIGHT_SLOT_FRACTION = 0.74f
private const val CENTER_SLOT_FRACTION = 0.50f
private const val RIGHT_ENTER_START_FRACTION = 1.0f
private const val LEFT_ROTATION_Y_DEG = 15f
private const val RIGHT_ROTATION_Y_DEG = -15f
private const val CAMERA_DISTANCE_FACTOR = 16f
private const val ENTER_DURATION_MS = 500
private const val EXIT_DURATION_MS = 500
private val CARD_CORNER_RADIUS = 16.dp
private val CARD_SHADOW_ELEVATION = 12.dp

private const val DEFAULT_CROSSFADE_DURATION_MS = 5000

private val DefaultLeftAmbientColor = Color(0xFF4A2520)
private val DefaultRightAmbientColor = Color(0xFF28354A)
private val AmbientBaseDarkColor = Color(0xFF101014)

/**
 * Pose of a single 3D album card at a given transition state.
 */
@Immutable
data class DualCardPose(
    val centerX: Float,
    val scale: Float,
    val rotationY: Float,
    val alpha: Float,
)

/**
 * Computes the 3D pose of the outgoing (left) card from [enterProgress] and [exitProgress]:
 * - Enter (500ms, FastOutSlowInEasing): shrinks smoothly from 1f -> 0.58f, slides left from center (50%) -> 26%,
 *   and tilts rotationY from 0f -> 15f.
 * - Hold (parallel across the entire crossfade duration): stays at 26%, scale = 0.58f, rotationY = 15f, alpha = 1f.
 * - Exit (500ms, FastOutSlowInEasing): fades out (alpha 1f -> 0f).
 */
fun oldCardPose(
    enterProgress: Float,
    exitProgress: Float,
    width: Float,
): DualCardPose {
    val enter = enterProgress.coerceIn(0f, 1f)
    val exit = exitProgress.coerceIn(0f, 1f)
    val centerX = width * CENTER_SLOT_FRACTION
    val leftX = width * LEFT_SLOT_FRACTION
    return DualCardPose(
        centerX = centerX + (leftX - centerX) * enter,
        scale = 1f + (HOLD_SCALE - 1f) * enter,
        rotationY = LEFT_ROTATION_Y_DEG * enter,
        alpha = (1f - exit).coerceIn(0f, 1f),
    )
}

/**
 * Computes the 3D pose of the outgoing (left) card from normalized [progress] and [totalDurationMs].
 */
fun oldCardPose(
    progress: Float,
    width: Float,
    totalDurationMs: Int = DEFAULT_CROSSFADE_DURATION_MS,
): DualCardPose {
    val p = progress.coerceIn(0f, 1f)
    val safeTotalMs = totalDurationMs.coerceAtLeast(ENTER_DURATION_MS + EXIT_DURATION_MS).toFloat()
    val enterSpan = (ENTER_DURATION_MS / safeTotalMs).coerceIn(0.01f, 0.45f)
    val exitSpan = (EXIT_DURATION_MS / safeTotalMs).coerceIn(0.01f, 0.45f)
    val enterRaw = (p / enterSpan).coerceIn(0f, 1f)
    val exitRaw = ((p - (1f - exitSpan)) / exitSpan).coerceIn(0f, 1f)
    return oldCardPose(
        enterProgress = FastOutSlowInEasing.transform(enterRaw),
        exitProgress = FastOutSlowInEasing.transform(exitRaw),
        width = width,
    )
}

/**
 * Computes the 3D pose of the incoming (right) card from [enterProgress] and [exitProgress]:
 * - Enter (500ms, FastOutSlowInEasing): fades in (alpha 0f -> 1f) and slides in from the right -> 74%,
 *   at scale = 0.58f, rotationY = -15f.
 * - Hold (parallel across the entire crossfade duration): stays at 74%, scale = 0.58f, rotationY = -15f, alpha = 1f.
 * - Exit (500ms, FastOutSlowInEasing): slides from right (74%) -> center (50%), scales 0.58f -> 1f,
 *   and returns rotationY from -15f -> 0f.
 */
fun newCardPose(
    enterProgress: Float,
    exitProgress: Float,
    width: Float,
): DualCardPose {
    val enter = enterProgress.coerceIn(0f, 1f)
    val exit = exitProgress.coerceIn(0f, 1f)
    val enterStartX = width * RIGHT_ENTER_START_FRACTION
    val rightX = width * RIGHT_SLOT_FRACTION
    val centerX = width * CENTER_SLOT_FRACTION
    val heldX = enterStartX + (rightX - enterStartX) * enter
    return DualCardPose(
        centerX = heldX + (centerX - heldX) * exit,
        scale = HOLD_SCALE + (1f - HOLD_SCALE) * exit,
        rotationY = RIGHT_ROTATION_Y_DEG * (1f - exit),
        alpha = (enter + (1f - enter) * exit).coerceIn(0f, 1f),
    )
}

/**
 * Computes the 3D pose of the incoming (right) card from normalized [progress] and [totalDurationMs].
 */
fun newCardPose(
    progress: Float,
    width: Float,
    totalDurationMs: Int = DEFAULT_CROSSFADE_DURATION_MS,
): DualCardPose {
    val p = progress.coerceIn(0f, 1f)
    val safeTotalMs = totalDurationMs.coerceAtLeast(ENTER_DURATION_MS + EXIT_DURATION_MS).toFloat()
    val enterSpan = (ENTER_DURATION_MS / safeTotalMs).coerceIn(0.01f, 0.45f)
    val exitSpan = (EXIT_DURATION_MS / safeTotalMs).coerceIn(0.01f, 0.45f)
    val enterRaw = (p / enterSpan).coerceIn(0f, 1f)
    val exitRaw = ((p - (1f - exitSpan)) / exitSpan).coerceIn(0f, 1f)
    return newCardPose(
        enterProgress = FastOutSlowInEasing.transform(enterRaw),
        exitProgress = FastOutSlowInEasing.transform(exitRaw),
        width = width,
    )
}

/**
 * State holder for the 3D Dual-Card Crossfade Transition.
 */
@Stable
class DualCardCrossfadeState(
    val transitionProgress: Float,
    val enterProgress: Float,
    val exitProgress: Float,
    val isCrossfading: Boolean,
    val oldThumbnailUrl: String?,
    val newThumbnailUrl: String?,
    val oldTrackColor: Color,
    val newTrackColor: Color,
    val newBitmap: ImageBitmap? = null,
    internal val onOldBitmapLoaded: (ImageBitmap) -> Unit,
    internal val onNewBitmapLoaded: (ImageBitmap) -> Unit,
) {
    val isActive: Boolean
        get() = isCrossfading || enterProgress > 0f || exitProgress > 0f || transitionProgress > 0f

    val ambientAlpha: Float
        get() = (enterProgress * (1f - exitProgress)).coerceIn(0f, 1f)
}

/**
 * Remembers and drives the [DualCardCrossfadeState] from [NowPlayingContentState].
 *
 * Dynamically synchronizes 1:1 with the user's configured [DataStoreManager.crossfadeDuration] and
 * live crossfade state:
 * - Enter: 500ms with [FastOutSlowInEasing] when crossfade begins.
 * - Parallel Hold: Keeps both cards side-by-side throughout the entire crossfade duration
 *   (whether 3s, 5s, 8s, 12s, or any configured duration) until the outgoing track finishes.
 * - Exit: 500ms with [FastOutSlowInEasing] at the exact moment crossfade ends, settling the
 *   incoming card to center (1.0f) seamlessly without snapping.
 */
@Composable
fun rememberDualCardCrossfadeState(state: NowPlayingContentState): DualCardCrossfadeState {
    val dataStoreManager: DataStoreManager = koinInject()
    val configuredDurationMs by dataStoreManager.crossfadeDuration.collectAsStateWithLifecycle(
        initialValue = DEFAULT_CROSSFADE_DURATION_MS,
    )

    val isCrossfading = state.timelineState.isCrossfading || state.controllerState.isCrossfading
    val currentVideoId = state.videoId ?: state.artworkQueue.getOrNull(state.currentOrderIndex)?.videoId
    val currentThumbUrl =
        state.screenData.thumbnailURL
            ?: state.artworkQueue
                .getOrNull(state.currentOrderIndex)
                ?.thumbnails
                ?.maxByOrNull { it.width * it.height }
                ?.url
    val currentPaletteColor = state.startColor.value.takeIf { it != Color.Black }

    // Last stable (non-crossfading) track snapshot
    var lastStableVideoId by remember { mutableStateOf<String?>(null) }
    var lastStableThumbUrl by remember { mutableStateOf<String?>(null) }
    var lastStableColor by remember { mutableStateOf(DefaultLeftAmbientColor) }
    var lastStableRemainingMs by remember { mutableLongStateOf(DEFAULT_CROSSFADE_DURATION_MS.toLong()) }

    // Active crossfade pair
    var outgoingVideoId by remember { mutableStateOf<String?>(null) }
    var outgoingThumbUrl by remember { mutableStateOf<String?>(null) }
    var incomingThumbUrl by remember { mutableStateOf<String?>(null) }
    var oldColor by remember { mutableStateOf(DefaultLeftAmbientColor) }
    var newColor by remember { mutableStateOf(DefaultRightAmbientColor) }

    var oldBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var newBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val enterAnim = remember { Animatable(0f) }
    val exitAnim = remember { Animatable(0f) }
    val mixAnim = remember { Animatable(0f) }

    val enterProgress = enterAnim.value
    val exitProgress = exitAnim.value
    val transitionProgress = mixAnim.value
    val isVisualIdle = !isCrossfading && enterProgress == 0f && exitProgress == 0f && transitionProgress == 0f

    // Track remaining playback time while not crossfading so Auto/dynamic crossfade knows the exact window
    val timelineCurrent = state.timelineState.current
    val timelineTotal = state.timelineState.total
    LaunchedEffect(timelineCurrent, timelineTotal, isCrossfading) {
        if (!isCrossfading && timelineTotal > 0L && timelineCurrent >= 0L) {
            val remaining = timelineTotal - timelineCurrent
            if (remaining > 500L) {
                lastStableRemainingMs = remaining
            }
        }
    }

    // Extract vibrant color from current screen bitmap when not crossfading
    LaunchedEffect(state.screenData.bitmap, isCrossfading) {
        val bmp = state.screenData.bitmap ?: return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) { extractVibrantAmbientColor(bmp) }
        if (extracted != null) {
            if (!isCrossfading && enterAnim.value == 0f) {
                lastStableColor = extracted
            } else if (currentVideoId != outgoingVideoId) {
                newColor = extracted
            }
        }
    }

    // Extract vibrant color from outgoing card bitmap if loaded separately
    LaunchedEffect(oldBitmap) {
        val bmp = oldBitmap ?: return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) { extractVibrantAmbientColor(bmp) }
        if (extracted != null) oldColor = extracted
    }

    // Extract vibrant color from incoming card bitmap as soon as it loads
    LaunchedEffect(newBitmap) {
        val bmp = newBitmap ?: return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) { extractVibrantAmbientColor(bmp) }
        if (extracted != null) newColor = extracted
    }

    // Update stable snapshot while playing normally (not crossfading)
    LaunchedEffect(currentVideoId, currentThumbUrl, currentPaletteColor, isVisualIdle, isCrossfading) {
        if (isVisualIdle) {
            if (!currentVideoId.isNullOrEmpty()) {
                lastStableVideoId = currentVideoId
            }
            if (!currentThumbUrl.isNullOrBlank()) {
                lastStableThumbUrl = currentThumbUrl
            }
            if (currentPaletteColor != null) {
                lastStableColor = liftAmbientColor(currentPaletteColor)
            }
        } else if (isCrossfading) {
            // Once nowPlayingState advances to the incoming song during crossfade, capture its URL & color
            if (currentVideoId != null && currentVideoId != outgoingVideoId && !currentThumbUrl.isNullOrBlank()) {
                incomingThumbUrl = currentThumbUrl
            }
            if (currentVideoId != null && currentVideoId != outgoingVideoId && currentPaletteColor != null) {
                newColor = liftAmbientColor(currentPaletteColor)
            }
        }
    }

    val latestQueue by rememberUpdatedState(state.artworkQueue)
    val latestOrderIndex by rememberUpdatedState(state.currentOrderIndex)
    val latestThumbUrl by rememberUpdatedState(currentThumbUrl)
    val latestVideoId by rememberUpdatedState(currentVideoId)
    val latestPaletteColor by rememberUpdatedState(currentPaletteColor)
    val latestConfiguredDuration by rememberUpdatedState(configuredDurationMs)

    // Drive Enter -> Parallel Hold (1:1 dynamic crossfade duration) -> Exit from isCrossfading
    LaunchedEffect(isCrossfading) {
        if (isCrossfading) {
            val queue = latestQueue
            val idx = latestOrderIndex
            val prevQueueThumb =
                queue
                    .getOrNull(idx - 1)
                    ?.thumbnails
                    ?.maxByOrNull { it.width * it.height }
                    ?.url
            val nextQueueThumb =
                queue
                    .getOrNull(idx + 1)
                    ?.thumbnails
                    ?.maxByOrNull { it.width * it.height }
                    ?.url
            val currQueueThumb =
                queue
                    .getOrNull(idx)
                    ?.thumbnails
                    ?.maxByOrNull { it.width * it.height }
                    ?.url

            // Determine whether currentOrderIndex has already moved to the incoming track
            val alreadyAdvanced =
                lastStableVideoId != null &&
                    latestVideoId != null &&
                    latestVideoId != lastStableVideoId

            outgoingVideoId = lastStableVideoId ?: queue.getOrNull(if (alreadyAdvanced) idx - 1 else idx)?.videoId
            outgoingThumbUrl =
                lastStableThumbUrl
                    ?: (if (alreadyAdvanced) prevQueueThumb else latestThumbUrl ?: currQueueThumb)
            oldColor = lastStableColor

            incomingThumbUrl =
                if (alreadyAdvanced) {
                    latestThumbUrl ?: currQueueThumb
                } else {
                    nextQueueThumb ?: latestThumbUrl
                }

            // Dynamic duration resolved directly from user settings (crossfadeDuration) and remaining track time
            val durConfig = latestConfiguredDuration
            val dynamicDurationMs =
                if (durConfig > 0) {
                    val rem = lastStableRemainingMs
                    if (rem in 1000L..durConfig.toLong()) rem.toInt() else durConfig
                } else {
                    lastStableRemainingMs
                        .takeIf { it > 0L }
                        ?.coerceAtLeast(1000L)
                        ?.toInt()
                        ?: DEFAULT_CROSSFADE_DURATION_MS
                }

            exitAnim.snapTo(0f)
            enterAnim.snapTo(0f)
            mixAnim.snapTo(0.001f)

            coroutineScope {
                // 2. Enter animation: 500ms FastOutSlowInEasing
                launch {
                    enterAnim.animateTo(
                        targetValue = 1f,
                        animationSpec =
                            tween(
                                durationMillis = ENTER_DURATION_MS,
                                easing = FastOutSlowInEasing,
                            ),
                    )
                }
                // 1. Dynamic crossfade progress over the actual crossfade duration;
                // both cards remain held in parallel for the entire time isCrossfading is true.
                launch {
                    mixAnim.animateTo(
                        targetValue = 1f,
                        animationSpec =
                            tween(
                                durationMillis = dynamicDurationMs.coerceAtLeast(ENTER_DURATION_MS),
                                easing = LinearEasing,
                            ),
                    )
                }
            }
        } else {
            if (enterAnim.value > 0f || mixAnim.value > 0f) {
                // 3. Exit animation: right when crossfade ends, old card fades to 0 and new card
                // slides from right to center while scaling 0.58f -> 1f in 500ms (FastOutSlowInEasing).
                exitAnim.snapTo(0f)
                exitAnim.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            durationMillis = EXIT_DURATION_MS,
                            easing = FastOutSlowInEasing,
                        ),
                )
                // Sync stable snapshot to the settled incoming track before resetting
                if (!latestVideoId.isNullOrEmpty()) {
                    lastStableVideoId = latestVideoId
                }
                if (!latestThumbUrl.isNullOrBlank()) {
                    lastStableThumbUrl = latestThumbUrl
                }
                latestPaletteColor?.let {
                    lastStableColor = liftAmbientColor(it)
                }
                enterAnim.snapTo(0f)
                exitAnim.snapTo(0f)
                mixAnim.snapTo(0f)
                outgoingThumbUrl = null
                incomingThumbUrl = null
                oldBitmap = null
                newBitmap = null
            }
        }
    }

    val resolvedOldThumbUrl = outgoingThumbUrl ?: lastStableThumbUrl ?: currentThumbUrl
    val resolvedNewThumbUrl =
        incomingThumbUrl
            ?: (if (currentVideoId != null && currentVideoId != lastStableVideoId) currentThumbUrl else null)
            ?: state.artworkQueue
                .getOrNull(state.currentOrderIndex + 1)
                ?.thumbnails
                ?.maxByOrNull { it.width * it.height }
                ?.url
            ?: currentThumbUrl
    val resolvedOldColor = if (outgoingThumbUrl != null) oldColor else lastStableColor

    return remember(
        transitionProgress,
        enterProgress,
        exitProgress,
        isCrossfading,
        resolvedOldThumbUrl,
        resolvedNewThumbUrl,
        resolvedOldColor,
        newColor,
        newBitmap,
    ) {
        DualCardCrossfadeState(
            transitionProgress = transitionProgress,
            enterProgress = enterProgress,
            exitProgress = exitProgress,
            isCrossfading = isCrossfading,
            oldThumbnailUrl = resolvedOldThumbUrl,
            newThumbnailUrl = resolvedNewThumbUrl,
            oldTrackColor = resolvedOldColor,
            newTrackColor = newColor,
            newBitmap = newBitmap,
            onOldBitmapLoaded = { oldBitmap = it },
            onNewBitmapLoaded = { newBitmap = it },
        )
    }
}

/**
 * Dynamic Ambient Dark Gradient background based on the color tones of the 2 tracks.
 *
 * Outgoing track color glows on the left (26%), incoming track color glows on the right (74% -> 50%),
 * blended into deep ambient dark tones so controls and stardust particles stand out clearly.
 */
@Composable
fun DynamicAmbientDarkGradient(
    transitionProgress: Float,
    oldTrackColor: Color,
    newTrackColor: Color,
    modifier: Modifier = Modifier,
    enterProgress: Float = 1f,
    exitProgress: Float = 0f,
    alpha: Float = 1f,
) {
    val effectiveAlpha = alpha.coerceIn(0f, 1f)
    if (effectiveAlpha <= 0.001f) return

    val p = transitionProgress.coerceIn(0f, 1f)
    val enter = enterProgress.coerceIn(0f, 1f)
    val exit = exitProgress.coerceIn(0f, 1f)

    val leftAmbient = remember(oldTrackColor) { liftAmbientColor(oldTrackColor) }
    val rightAmbient = remember(newTrackColor) { liftAmbientColor(newTrackColor) }

    Canvas(
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = effectiveAlpha },
    ) {
        val w = size.width
        val h = size.height
        val maxDim = max(w, h).coerceAtLeast(1f)

        // Base deep dark floor
        drawRect(color = AmbientBaseDarkColor)

        // Blended ambient vertical wash from the two tracks' tones
        val mixRatio = (0.35f + 0.65f * p).coerceIn(0f, 1f)
        val blendedTop = lerp(leftAmbient, rightAmbient, mixRatio).darkened(0.42f)
        val blendedMid = lerp(leftAmbient, rightAmbient, (mixRatio + 0.15f).coerceAtMost(1f)).darkened(0.62f)
        val blendedBottom = lerp(leftAmbient, rightAmbient, 0.5f).darkened(0.84f)

        drawRect(
            brush =
                Brush.verticalGradient(
                    0f to blendedTop,
                    0.48f to blendedMid,
                    1f to blendedBottom,
                ),
        )

        // Left card radial ambient glow (outgoing song sliding 50% -> 26% and fading out on exit)
        val outWeight = ((1f - p * 0.45f) * (1f - exit)).coerceIn(0f, 1f)
        val leftCenterX = w * (CENTER_SLOT_FRACTION + (LEFT_SLOT_FRACTION - CENTER_SLOT_FRACTION) * enter)
        if (outWeight > 0.01f) {
            drawRect(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                leftAmbient.copy(alpha = 0.52f * outWeight),
                                leftAmbient.copy(alpha = 0.22f * outWeight),
                                Color.Transparent,
                            ),
                        center = Offset(leftCenterX, h * 0.30f),
                        radius = maxDim * 0.72f,
                    ),
            )
        }

        // Right card radial ambient glow (incoming song at 74% -> 50% width)
        val inWeight = (0.48f + p * 0.52f).coerceIn(0f, 1f) * enter
        val rightCenterX = w * (RIGHT_SLOT_FRACTION + (CENTER_SLOT_FRACTION - RIGHT_SLOT_FRACTION) * exit)
        if (inWeight > 0.01f) {
            drawRect(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                rightAmbient.copy(alpha = 0.56f * inWeight),
                                rightAmbient.copy(alpha = 0.25f * inWeight),
                                Color.Transparent,
                            ),
                        center = Offset(rightCenterX, h * 0.30f),
                        radius = maxDim * 0.76f,
                    ),
            )
        }

        // Soft ambient dark vignette for contrast under transport & lyrics
        drawRect(
            brush =
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.16f),
                    0.50f to Color.Black.copy(alpha = 0.24f),
                    1f to Color.Black.copy(alpha = 0.58f),
                ),
        )
    }
}

/**
 * 3D Dual-Card Layout + White Stardust Particle Effect + Smooth Enter & Exit.
 *
 * - Enter (500ms, FastOutSlowInEasing):
 *   - Old card shrinks smoothly from 1f -> 0.58f, slides left to 26%, tilts rotationY to 15f.
 *   - New card fades in (0f -> 1f) and slides in from the right to 74% at scale = 0.58f, rotationY = -15f.
 * - Parallel Hold (1:1 for the entire crossfade duration):
 *   - Both cards stay in parallel at scale = 0.58f, rotationY = 15f / -15f, cameraDistance = 16f * density.
 *   - White Stardust particles float and drift from the left card to the right card across the gap.
 * - Exit (500ms, FastOutSlowInEasing, triggered right when crossfade ends):
 *   - Old card fades out (alpha -> 0f).
 *   - New card slides from right (74%) to center (50%), scales 0.58f -> 1.0f, and returns rotationY -> 0f.
 */
@Composable
fun DualCard3DTransitionOverlay(
    crossfadeState: DualCardCrossfadeState,
    modifier: Modifier = Modifier,
    settledCornerRadius: Dp = 0.dp,
    settledTopFade: Dp = 0.dp,
    settledBottomFade: Dp = 0.dp,
) {
    if (!crossfadeState.isActive) return

    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val containerW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val fullCardPx = min(containerW, containerH).coerceAtLeast(1f)
        val fullCardDp = with(density) { fullCardPx.toDp() }

        val enter = crossfadeState.enterProgress.coerceIn(0f, 1f)
        val exit = crossfadeState.exitProgress.coerceIn(0f, 1f)

        val oldPose = oldCardPose(enterProgress = enter, exitProgress = exit, width = containerW)
        val newPose = newCardPose(enterProgress = enter, exitProgress = exit, width = containerW)

        val cardVisualHeightPx = fullCardPx * HOLD_SCALE
        val gapLeftEdgePx = (containerW * 0.34f).coerceIn(0f, containerW)
        val gapRightEdgePx = (containerW * 0.66f).coerceIn(gapLeftEdgePx + 1f, containerW)

        // White Stardust glowing particles drifting from left card to right card during the mix
        val stardustAlpha = (enter * (1f - exit)).coerceIn(0f, 1f)
        if (stardustAlpha > 0.01f) {
            WhiteStardustParticles(
                gapStartX = gapLeftEdgePx,
                gapEndX = gapRightEdgePx,
                centerY = containerH * 0.5f,
                bandHeight = cardVisualHeightPx * 0.92f,
                alphaMultiplier = stardustAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Outgoing (Old) Card — shrinks 1f -> 0.58f & slides to left (26%, rotationY = 15f), fades out on exit
        if (oldPose.alpha > 0.001f) {
            val oldCornerDp = (settledCornerRadius + (CARD_CORNER_RADIUS - settledCornerRadius) * enter).coerceAtLeast(0.dp)
            val oldTopFadeDp = settledTopFade * (1f - enter)
            val oldBottomFadeDp = settledBottomFade * (1f - enter)
            TiltedAlbumCard(
                thumbnailUrl = crossfadeState.oldThumbnailUrl,
                pose = oldPose,
                containerWidthPx = containerW,
                cardSizeDp = fullCardDp,
                cornerRadiusDp = oldCornerDp,
                chromeWeight = enter * (1f - exit),
                topFadeDp = oldTopFadeDp,
                bottomFadeDp = oldBottomFadeDp,
                onBitmapLoaded = crossfadeState.onOldBitmapLoaded,
            )
        }

        // Incoming (New) Card — slides in from right to 74% (rotationY = -15f, scale = 0.58f), exits to 50%, 0f, 1.0f
        if (newPose.alpha > 0.001f) {
            val newCornerDp = (CARD_CORNER_RADIUS + (settledCornerRadius - CARD_CORNER_RADIUS) * exit).coerceAtLeast(0.dp)
            val newTopFadeDp = settledTopFade * exit
            val newBottomFadeDp = settledBottomFade * exit
            TiltedAlbumCard(
                thumbnailUrl = crossfadeState.newThumbnailUrl,
                pose = newPose,
                containerWidthPx = containerW,
                cardSizeDp = fullCardDp,
                cornerRadiusDp = newCornerDp,
                chromeWeight = enter * (1f - exit),
                topFadeDp = newTopFadeDp,
                bottomFadeDp = newBottomFadeDp,
                onBitmapLoaded = crossfadeState.onNewBitmapLoaded,
            )
        }
    }
}

@Composable
private fun TiltedAlbumCard(
    thumbnailUrl: String?,
    pose: DualCardPose,
    containerWidthPx: Float,
    cardSizeDp: Dp,
    cornerRadiusDp: Dp,
    chromeWeight: Float,
    topFadeDp: Dp = 0.dp,
    bottomFadeDp: Dp = 0.dp,
    onBitmapLoaded: (ImageBitmap) -> Unit,
) {
    val density = LocalDensity.current
    val currentScale = pose.scale.coerceAtLeast(0.01f)
    // Compensate corner radius for graphicsLayer scale so on-screen corners match cornerRadiusDp
    val compensatedCornerDp = (cornerRadiusDp.value / currentScale).dp
    val cardShape = RoundedCornerShape(compensatedCornerDp)
    val shadowPx = with(density) { CARD_SHADOW_ELEVATION.toPx() }
    val chromeAlpha = (chromeWeight * pose.alpha).coerceIn(0f, 1f)
    val hasEdgeFade = topFadeDp > 0.05.dp || bottomFadeDp > 0.05.dp

    var activeUrl by remember(thumbnailUrl) { mutableStateOf(thumbnailUrl) }

    Box(
        modifier =
            Modifier
                .size(cardSizeDp)
                .graphicsLayer {
                    translationX = pose.centerX - containerWidthPx * 0.5f
                    scaleX = pose.scale
                    scaleY = pose.scale
                    rotationY = pose.rotationY
                    alpha = pose.alpha
                    cameraDistance = 16f * this.density
                    shadowElevation = shadowPx * chromeAlpha
                    shape = cardShape
                    clip = true
                    ambientShadowColor = Color.Black
                    spotShadowColor = Color.Black
                }.background(Color(0xFF1A1A20).copy(alpha = chromeAlpha), cardShape)
                .border(
                    width = (1f / currentScale).dp,
                    brush =
                        Brush.linearGradient(
                            0f to Color.White.copy(alpha = 0.22f * chromeAlpha),
                            0.5f to Color.White.copy(alpha = 0.08f * chromeAlpha),
                            1f to Color.White.copy(alpha = 0.16f * chromeAlpha),
                        ),
                    shape = cardShape,
                ).clip(cardShape),
    ) {
        AsyncImage(
            model =
                ImageRequest
                    .Builder(LocalPlatformContext.current)
                    .data(activeUrl)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .diskCacheKey((activeUrl ?: "") + "BIGGER")
                    .crossfade(false)
                    .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = rememberHolderPainter(square = true),
            error = rememberHolderPainter(square = true),
            onSuccess = { result ->
                onBitmapLoaded(result.result.image.toImageBitmap())
            },
            onError = {
                val fallback = activeUrl?.replace("maxresdefault", "hqdefault")
                if (fallback != null && fallback != activeUrl) {
                    activeUrl = fallback
                }
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (hasEdgeFade) {
                            Modifier.appleMusicVerticalFadeEdges(topFade = topFadeDp, bottomFade = bottomFadeDp)
                        } else {
                            Modifier
                        },
                    ),
        )
    }
}

/**
 * White Stardust Particle Effect:
 * Floating, glowing white/silver dust particles in the gap between the two 3D cards,
 * drifting horizontally from the left card to the right card during the mix.
 */
@Composable
private fun WhiteStardustParticles(
    gapStartX: Float,
    gapEndX: Float,
    centerY: Float,
    bandHeight: Float,
    alphaMultiplier: Float,
    modifier: Modifier = Modifier,
) {
    val particles = remember { generateStardustSpecs(STARDUST_COUNT) }

    // Uses rememberInfiniteTransition so it automatically obeys DesktopFrameGovernor in commonMain
    val infiniteTransition = rememberInfiniteTransition(label = "whiteStardustTransition")
    val cyclePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = STARDUST_CYCLE_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "whiteStardustCycle",
    )

    val elapsedSec = cyclePhase * (STARDUST_CYCLE_MS / 1000f)

    Canvas(modifier = modifier) {
        val spanX = (gapEndX - gapStartX).coerceAtLeast(1f)
        val halfBandY = (bandHeight * 0.5f).coerceAtLeast(1f)
        val twoPi = (2.0 * PI).toFloat()

        for (i in particles.indices) {
            val p = particles[i]

            // Horizontal drift from left card (0f) -> right card (1f)
            val travel = (p.initialProgress + elapsedSec * p.driftSpeed) % 1f
            val x = gapStartX + travel * spanX

            // Gentle vertical float + sinusoidal sway
            val swayAngle = elapsedSec * p.swaySpeed + p.phaseOffset
            val yOffset =
                p.verticalNormalized * halfBandY +
                    sin(swayAngle) * p.swayAmplitudeDp.dp.toPx()
            val y = (centerY + yOffset).coerceIn(0f, size.height)

            // Smooth envelope at left entrance and right exit
            val edgeEnvelope =
                when {
                    travel < 0.16f -> (travel / 0.16f).coerceIn(0f, 1f)
                    travel > 0.82f -> ((1f - travel) / 0.18f).coerceIn(0f, 1f)
                    else -> 1f
                }

            // Twinkle modulation
            val twinkle = 0.62f + 0.38f * (0.5f + 0.5f * sin(elapsedSec * p.twinkleSpeed * twoPi + p.phaseOffset))
            val alpha = (p.baseAlpha * edgeEnvelope * twinkle * alphaMultiplier).coerceIn(0f, 1f)
            if (alpha <= 0.01f) continue

            val radiusPx = p.radiusDp.dp.toPx()
            val center = Offset(x, y)

            // Soft outer silver/white glow halo
            drawCircle(
                color = p.tint.copy(alpha = (alpha * 0.22f).coerceIn(0f, 1f)),
                radius = radiusPx * 3.4f,
                center = center,
            )
            // Mid glow
            drawCircle(
                color = p.tint.copy(alpha = (alpha * 0.46f).coerceIn(0f, 1f)),
                radius = radiusPx * 1.85f,
                center = center,
            )
            // Bright white core
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = radiusPx,
                center = center,
            )
        }
    }
}

@Immutable
private data class StardustSpec(
    val initialProgress: Float,
    val verticalNormalized: Float,
    val driftSpeed: Float,
    val swayAmplitudeDp: Float,
    val swaySpeed: Float,
    val twinkleSpeed: Float,
    val phaseOffset: Float,
    val radiusDp: Float,
    val baseAlpha: Float,
    val tint: Color,
)

private const val STARDUST_COUNT = 38
private const val STARDUST_CYCLE_MS = 24000

private val StardustPalette =
    listOf(
        Color(0xFFFFFFFF), // Pure White
        Color(0xFFF8FAFC), // Ice Silver White
        Color(0xFFE2E8F0), // Soft Silver
        Color(0xFFF1F5F9), // Pearl White
    )

private fun generateStardustSpecs(count: Int): List<StardustSpec> {
    val rng = Random(0x57A8D057)
    val twoPi = (2.0 * PI).toFloat()
    return List(count) { idx ->
        StardustSpec(
            initialProgress = (idx.toFloat() / count + rng.nextFloat() * 0.08f) % 1f,
            // Concentrated slightly more around the middle of the gap with full vertical coverage
            verticalNormalized = (rng.nextFloat() * 2f - 1f) * 0.88f,
            // Completes a left->right crossing in ~2.2s - 4.2s
            driftSpeed = 0.24f + rng.nextFloat() * 0.22f,
            swayAmplitudeDp = 3.5f + rng.nextFloat() * 8.5f,
            swaySpeed = 1.1f + rng.nextFloat() * 1.6f,
            twinkleSpeed = 0.45f + rng.nextFloat() * 0.75f,
            phaseOffset = rng.nextFloat() * twoPi,
            radiusDp = 0.85f + rng.nextFloat() * 1.45f,
            baseAlpha = 0.55f + rng.nextFloat() * 0.42f,
            tint = StardustPalette[idx % StardustPalette.size],
        )
    }
}

private fun extractVibrantAmbientColor(bitmap: ImageBitmap): Color? =
    runCatching {
        if (bitmap.width < 1 || bitmap.height < 1) return null
        val sampleSize = 36
        val pixels = IntArray(sampleSize * sampleSize)
        bitmap.toReadableBitmap(sampleSize, sampleSize).readPixels(pixels)

        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var weightSum = 0.0

        for (px in pixels) {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val chroma = maxC - minC
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            // Favor mid-tone chromatic pixels so dark/black borders don't wash out the ambient hue
            val weight = (0.25 + chroma * 2.2) * (if (luma in 0.08f..0.92f) 1.0 else 0.25)
            rSum += r * weight
            gSum += g * weight
            bSum += b * weight
            weightSum += weight
        }

        if (weightSum <= 0.0) return null
        val raw =
            Color(
                red = (rSum / weightSum).toFloat().coerceIn(0f, 1f),
                green = (gSum / weightSum).toFloat().coerceIn(0f, 1f),
                blue = (bSum / weightSum).toFloat().coerceIn(0f, 1f),
                alpha = 1f,
            )
        liftAmbientColor(raw)
    }.getOrNull()

private fun liftAmbientColor(color: Color): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val l = (maxC + minC) * 0.5f
    val d = maxC - minC
    if (d < 0.02f) {
        val liftedL = l.coerceIn(0.18f, 0.42f)
        return Color(liftedL, liftedL, liftedL, 1f)
    }
    val s = d / (1f - abs(2f * l - 1f)).coerceAtLeast(0.001f)
    val hue =
        (
            when (maxC) {
                r -> ((g - b) / d).mod(6f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
        ).coerceIn(0f, 360f)
    return Color.hsl(
        hue = hue,
        saturation = (s * 1.18f).coerceIn(0.28f, 0.88f),
        lightness = l.coerceIn(0.24f, 0.48f),
    )
}

private fun Color.darkened(amount: Float): Color = lerp(this, AmbientBaseDarkColor, amount.coerceIn(0f, 1f))
