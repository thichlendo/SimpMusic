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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
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
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// 3D Dual-Card layout & motion constants (Apple Music floating card standard: 120.dp / 0.31f * screenWidth)
private const val HOLD_SCALE = 0.31f
private val HOLD_CARD_SIZE_DP = 120.dp
private const val LEFT_SLOT_FRACTION = 0.24f
private const val RIGHT_SLOT_FRACTION = 0.76f
private const val CENTER_SLOT_FRACTION = 0.50f
private const val RIGHT_ENTER_START_FRACTION = 1.24f
private const val LEFT_ROTATION_Y_DEG = -8f
private const val RIGHT_ROTATION_Y_DEG = 8f
private const val CAMERA_DISTANCE_FACTOR = 20f
private const val ENTER_DURATION_MS = 500
private const val EXIT_DURATION_MS = 500
private val CARD_CORNER_RADIUS = 14.dp
private val CARD_SHADOW_ELEVATION = 14.dp

private const val DEFAULT_CROSSFADE_DURATION_MS = 5000

private val DefaultLeftAmbientColor = Color(0xFF4A2520)
private val DefaultRightAmbientColor = Color(0xFF28354A)
private val AmbientBaseDarkColor = Color(0xFF101014)

/**
 * Transition state machine for 3D Dual-Card Crossfade.
 * - IDLE: Normal playback, single card.
 * - ENTERING: 500ms enter animation (outgoing card scales down to 120.dp / 0.31f & slides left to 24%; incoming card slides in from right to 76%).
 * - CROSSFADING: Parallel hold of both floating 120.dp cards side-by-side throughout the crossfade duration with gentle breathing sway.
 * - EXITING: 500ms exit animation triggered when crossfade audio ends (old card fades out; new card slides to center offset X = 0 and scales from 120.dp / 0.31f up to 1.0f).
 */
enum class CrossfadeTransitionState {
    IDLE,
    ENTERING,
    CROSSFADING,
    EXITING,
}

/**
 * Pose of a single 3D album card at a given transition state.
 */
@Immutable
data class DualCardPose(
    val centerX: Float,
    val offsetY: Float = 0f,
    val scale: Float,
    val rotationY: Float,
    val alpha: Float,
)

/**
 * Computes the 3D pose of the outgoing (left) card from [enterProgress] and [exitProgress]:
 * - Enter (500ms, FastOutSlowInEasing): shrinks smoothly from 1f -> [holdScale] (120.dp / 0.31f),
 *   slides left from center (50%) -> 24%, and tilts rotationY gently from 0f -> -8f.
 * - Hold (parallel across the entire crossfade duration): floats at 24% width, 120.dp size,
 *   rotationY = -8f, with subtle, graceful breathing motion (no Z-axis rotation or cube flip).
 * - Exit (500ms, FastOutSlowInEasing): drifts slightly left while fading out (alpha 1f -> 0f).
 */
fun oldCardPose(
    enterProgress: Float,
    exitProgress: Float,
    width: Float,
    swayPhaseRad: Float = 0f,
    holdScale: Float = HOLD_SCALE,
    holdOffsetY: Float = 0f,
): DualCardPose {
    val enter = enterProgress.coerceIn(0f, 1f)
    val exit = exitProgress.coerceIn(0f, 1f)
    val holdWeight = (enter * (1f - exit)).coerceIn(0f, 1f)

    val centerX = width * CENTER_SLOT_FRACTION
    val leftX = width * LEFT_SLOT_FRACTION
    val exitShiftX = -width * 0.05f * exit

    // Subtle, graceful floating breathing sway during CROSSFADING
    val breatheScale = sin(swayPhaseRad) * 0.008f * holdWeight
    val swayX = sin(swayPhaseRad * 0.7f) * (width * 0.005f) * holdWeight
    val swayY = cos(swayPhaseRad) * (width * 0.007f) * holdWeight
    val swayRotY = sin(swayPhaseRad * 0.8f) * 1.0f * holdWeight

    return DualCardPose(
        centerX = centerX + (leftX - centerX) * enter + exitShiftX + swayX,
        offsetY = holdOffsetY * enter + swayY,
        scale = (1f + (holdScale - 1f) * enter - 0.03f * exit + breatheScale).coerceAtLeast(0.1f),
        rotationY = LEFT_ROTATION_Y_DEG * enter + swayRotY,
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
        swayPhaseRad = p * (4.0 * PI).toFloat(),
    )
}

/**
 * Computes the 3D pose of the incoming (right) card from [enterProgress] and [exitProgress]:
 * - Enter (500ms, FastOutSlowInEasing): fades in (alpha 0f -> 1f) and slides smoothly in from the right edge -> 76%,
 *   at size 120.dp ([holdScale] = 0.31f) and gentle tilt rotationY = +8f.
 * - Hold (parallel across the entire crossfade duration): floats at 76% width, 120.dp size,
 *   rotationY = +8f, with subtle counter-phase breathing motion (no Z-axis rotation or cube flip).
 * - Exit (500ms, FastOutSlowInEasing): slides smoothly from right (76%) -> center (50%, offset X = 0),
 *   scales from 120.dp ([holdScale]) -> full player size (1.0f), and returns rotationY from +8f -> 0f.
 */
fun newCardPose(
    enterProgress: Float,
    exitProgress: Float,
    width: Float,
    swayPhaseRad: Float = 0f,
    holdScale: Float = HOLD_SCALE,
    holdOffsetY: Float = 0f,
): DualCardPose {
    val enter = enterProgress.coerceIn(0f, 1f)
    val exit = exitProgress.coerceIn(0f, 1f)
    val holdWeight = (enter * (1f - exit)).coerceIn(0f, 1f)

    val enterStartX = width * RIGHT_ENTER_START_FRACTION
    val rightX = width * RIGHT_SLOT_FRACTION
    val centerX = width * CENTER_SLOT_FRACTION
    val heldX = enterStartX + (rightX - enterStartX) * enter

    // Subtle counter-phase floating breathing sway during CROSSFADING
    val counterPhase = swayPhaseRad + PI.toFloat()
    val breatheScale = sin(counterPhase) * 0.008f * holdWeight
    val swayX = sin(counterPhase * 0.7f) * (width * 0.005f) * holdWeight
    val swayY = cos(counterPhase) * (width * 0.007f) * holdWeight
    val swayRotY = sin(counterPhase * 0.8f) * 1.0f * holdWeight

    val baseScale = holdScale + (1f - holdScale) * exit
    return DualCardPose(
        centerX = heldX + (centerX - heldX) * exit + swayX,
        offsetY = holdOffsetY * holdWeight + swayY,
        scale = (baseScale + breatheScale).coerceIn(0.1f, 1.02f),
        rotationY = RIGHT_ROTATION_Y_DEG * (1f - exit) * enter + swayRotY,
        alpha = (enter * (1f - exit) + exit).coerceIn(0f, 1f),
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
        swayPhaseRad = p * (4.0 * PI).toFloat(),
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
    val transitionState: CrossfadeTransitionState = CrossfadeTransitionState.IDLE,
    val isCrossfading: Boolean = false,
    val oldThumbnailUrl: String?,
    val newThumbnailUrl: String?,
    val oldTrackColor: Color,
    val newTrackColor: Color,
    val oldTitle: String? = null,
    val oldArtist: String? = null,
    val oldIsExplicit: Boolean = false,
    val newTitle: String? = null,
    val newArtist: String? = null,
    val newIsExplicit: Boolean = false,
    val newBitmap: ImageBitmap? = null,
    internal val onOldBitmapLoaded: (ImageBitmap) -> Unit,
    internal val onNewBitmapLoaded: (ImageBitmap) -> Unit,
) {
    /**
     * Active whenever the state machine is not IDLE (ENTERING, CROSSFADING, or EXITING),
     * OR on the exact frame [isCrossfading] turns true before [LaunchedEffect] runs.
     * Crucially, when [isCrossfading] becomes false, [transitionState] transitions to [CrossfadeTransitionState.EXITING]
     * and stays active until the 500ms exit animation reports `isFinished`.
     */
    val isActive: Boolean
        get() = transitionState != CrossfadeTransitionState.IDLE || isCrossfading

    val ambientAlpha: Float
        get() =
            when (transitionState) {
                CrossfadeTransitionState.IDLE -> if (isCrossfading) 0.01f else 0f
                CrossfadeTransitionState.ENTERING -> enterProgress.coerceIn(0f, 1f)
                CrossfadeTransitionState.CROSSFADING -> 1f
                CrossfadeTransitionState.EXITING -> (1f - exitProgress).coerceIn(0f, 1f)
            }
}

/**
 * Remembers and drives the [DualCardCrossfadeState] from [NowPlayingContentState].
 *
 * Strict State Machine implementation:
 * - IDLE: Normal single-card playback.
 * - ENTERING (500ms, FastOutSlowInEasing): Triggered when [isCrossfading] becomes true.
 * - CROSSFADING: Parallel hold of both cards for the entire crossfade duration.
 * - EXITING (500ms, FastOutSlowInEasing): Triggered when [isCrossfading] becomes false.
 *   NEVER destroys the 2-card component when isCrossfading = false! Retains the 2-card layout intact,
 *   animating the incoming card from its current position to center (offset X = 0) and scale 0.58f -> 1.0f
 *   over 500ms (FastOutSlowInEasing).
 * - ONLY when the exit Animatable finishes completely (`exitResult.endState` / `isFinished`) does
 *   the state return to IDLE.
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
    val currentTitle = state.screenData.nowPlayingTitle
    val currentArtist = state.screenData.artistName
    val currentIsExplicit = state.screenData.isExplicit

    var transitionState by remember { mutableStateOf(CrossfadeTransitionState.IDLE) }

    // Last stable (non-crossfading) track snapshot
    var lastStableVideoId by remember { mutableStateOf<String?>(null) }
    var lastStableThumbUrl by remember { mutableStateOf<String?>(null) }
    var lastStableColor by remember { mutableStateOf(DefaultLeftAmbientColor) }
    var lastStableTitle by remember { mutableStateOf("") }
    var lastStableArtist by remember { mutableStateOf("") }
    var lastStableExplicit by remember { mutableStateOf(false) }
    var lastStableRemainingMs by remember { mutableLongStateOf(DEFAULT_CROSSFADE_DURATION_MS.toLong()) }

    // Active crossfade pair
    var outgoingVideoId by remember { mutableStateOf<String?>(null) }
    var outgoingThumbUrl by remember { mutableStateOf<String?>(null) }
    var outgoingTitle by remember { mutableStateOf<String?>(null) }
    var outgoingArtist by remember { mutableStateOf<String?>(null) }
    var outgoingExplicit by remember { mutableStateOf(false) }
    var incomingThumbUrl by remember { mutableStateOf<String?>(null) }
    var incomingTitle by remember { mutableStateOf<String?>(null) }
    var incomingArtist by remember { mutableStateOf<String?>(null) }
    var incomingExplicit by remember { mutableStateOf(false) }
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
    val isVisualIdle = transitionState == CrossfadeTransitionState.IDLE && !isCrossfading

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
    LaunchedEffect(state.screenData.bitmap, isVisualIdle) {
        val bmp = state.screenData.bitmap ?: return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) { extractVibrantAmbientColor(bmp) }
        if (extracted != null) {
            if (isVisualIdle) {
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

    // Update stable snapshot while playing normally (IDLE and not crossfading)
    // Also update incoming track metadata dynamically if it resolves mid-crossfade
    LaunchedEffect(
        currentVideoId,
        currentThumbUrl,
        currentTitle,
        currentArtist,
        currentIsExplicit,
        currentPaletteColor,
        isVisualIdle,
        isCrossfading,
    ) {
        if (isVisualIdle) {
            if (!currentVideoId.isNullOrEmpty()) {
                lastStableVideoId = currentVideoId
            }
            if (!currentThumbUrl.isNullOrBlank()) {
                lastStableThumbUrl = currentThumbUrl
            }
            if (currentTitle.isNotBlank()) {
                lastStableTitle = currentTitle
            }
            if (currentArtist.isNotBlank()) {
                lastStableArtist = currentArtist
            }
            lastStableExplicit = currentIsExplicit
            if (currentPaletteColor != null) {
                lastStableColor = liftAmbientColor(currentPaletteColor)
            }
        } else if (isCrossfading && currentVideoId != null && currentVideoId != outgoingVideoId) {
            if (!currentThumbUrl.isNullOrBlank()) {
                incomingThumbUrl = currentThumbUrl
            }
            if (currentTitle.isNotBlank()) {
                incomingTitle = currentTitle
            }
            if (currentArtist.isNotBlank()) {
                incomingArtist = currentArtist
            }
            incomingExplicit = currentIsExplicit
            if (currentPaletteColor != null) {
                newColor = liftAmbientColor(currentPaletteColor)
            }
        }
    }

    val latestQueue by rememberUpdatedState(state.artworkQueue)
    val latestOrderIndex by rememberUpdatedState(state.currentOrderIndex)
    val latestThumbUrl by rememberUpdatedState(currentThumbUrl)
    val latestVideoId by rememberUpdatedState(currentVideoId)
    val latestTitle by rememberUpdatedState(currentTitle)
    val latestArtist by rememberUpdatedState(currentArtist)
    val latestExplicit by rememberUpdatedState(currentIsExplicit)
    val latestPaletteColor by rememberUpdatedState(currentPaletteColor)
    val latestConfiguredDuration by rememberUpdatedState(configuredDurationMs)

    // Strict State Machine execution: IDLE -> ENTERING -> CROSSFADING -> EXITING -> IDLE
    LaunchedEffect(isCrossfading) {
        if (isCrossfading) {
            if (transitionState == CrossfadeTransitionState.IDLE || transitionState == CrossfadeTransitionState.EXITING) {
                val queue = latestQueue
                val idx = latestOrderIndex
                val prevTrack = queue.getOrNull(idx - 1)
                val nextTrack = queue.getOrNull(idx + 1)
                val currTrack = queue.getOrNull(idx)

                val prevQueueThumb = prevTrack?.thumbnails?.maxByOrNull { it.width * it.height }?.url
                val nextQueueThumb = nextTrack?.thumbnails?.maxByOrNull { it.width * it.height }?.url
                val currQueueThumb = currTrack?.thumbnails?.maxByOrNull { it.width * it.height }?.url

                val prevArtist = prevTrack?.artists?.joinToString(", ") { it.name }
                val nextArtist = nextTrack?.artists?.joinToString(", ") { it.name }
                val currArtist = currTrack?.artists?.joinToString(", ") { it.name }

                val alreadyAdvanced =
                    lastStableVideoId != null &&
                        latestVideoId != null &&
                        latestVideoId != lastStableVideoId

                outgoingVideoId = lastStableVideoId ?: queue.getOrNull(if (alreadyAdvanced) idx - 1 else idx)?.videoId
                outgoingThumbUrl =
                    lastStableThumbUrl
                        ?: (if (alreadyAdvanced) prevQueueThumb else latestThumbUrl ?: currQueueThumb)
                outgoingTitle =
                    lastStableTitle.takeIf { it.isNotBlank() }
                        ?: (if (alreadyAdvanced) prevTrack?.title else latestTitle.takeIf { it.isNotBlank() } ?: currTrack?.title)
                outgoingArtist =
                    lastStableArtist.takeIf { it.isNotBlank() }
                        ?: (if (alreadyAdvanced) prevArtist else latestArtist.takeIf { it.isNotBlank() } ?: currArtist)
                outgoingExplicit = lastStableExplicit
                oldColor = lastStableColor

                incomingThumbUrl =
                    if (alreadyAdvanced) {
                        latestThumbUrl ?: currQueueThumb
                    } else {
                        nextQueueThumb ?: latestThumbUrl
                    }
                incomingTitle =
                    if (alreadyAdvanced) {
                        latestTitle.takeIf { it.isNotBlank() } ?: currTrack?.title
                    } else {
                        nextTrack?.title ?: latestTitle
                    }
                incomingArtist =
                    if (alreadyAdvanced) {
                        latestArtist.takeIf { it.isNotBlank() } ?: currArtist
                    } else {
                        nextArtist ?: latestArtist
                    }
                incomingExplicit = if (alreadyAdvanced) latestExplicit else false

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

                // 1. Transition to ENTERING
                transitionState = CrossfadeTransitionState.ENTERING
                exitAnim.snapTo(0f)
                enterAnim.snapTo(0f)
                mixAnim.snapTo(0.001f)

                // 2. Enter animation: 500ms FastOutSlowInEasing (both cards slide & tilt into 3D position)
                enterAnim.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            durationMillis = ENTER_DURATION_MS,
                            easing = FastOutSlowInEasing,
                        ),
                )

                // 3. Move to CROSSFADING (holding 2 cards in parallel with 3D breathing & stardust)
                transitionState = CrossfadeTransitionState.CROSSFADING

                // Parallel hold: mixAnim progresses over remaining crossfade window
                mixAnim.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            durationMillis = (dynamicDurationMs - ENTER_DURATION_MS).coerceAtLeast(1000),
                            easing = LinearEasing,
                        ),
                )
            }
        } else {
            // isCrossfading became FALSE: audio blend completed.
            // NEVER destroy the 2-card UI immediately! Transition to EXITING and run the 500ms exit animation.
            if (transitionState != CrossfadeTransitionState.IDLE) {
                transitionState = CrossfadeTransitionState.EXITING
                // Ensure enterAnim is at 1f so the incoming card starts smoothly from its held 0.58f slot
                if (enterAnim.value < 1f) {
                    enterAnim.snapTo(1f)
                }
                exitAnim.snapTo(0f)

                // Animate exit: incoming card slides to center (offset X = 0) and scales 0.58f -> 1.0f in 500ms
                var isExitFinished = false
                val exitResult =
                    exitAnim.animateTo(
                        targetValue = 1f,
                        animationSpec =
                            tween(
                                durationMillis = EXIT_DURATION_MS,
                                easing = FastOutSlowInEasing,
                            ),
                    ) {
                        if (value >= 0.999f) {
                            isExitFinished = true
                        }
                    }

                // ONLY when the Animatable reports completion (isFinished) do we set state back to IDLE
                if (isExitFinished || exitResult.endState.value >= 0.999f) {
                    if (!latestVideoId.isNullOrEmpty()) {
                        lastStableVideoId = latestVideoId
                    }
                    if (!latestThumbUrl.isNullOrBlank()) {
                        lastStableThumbUrl = latestThumbUrl
                    }
                    if (latestTitle.isNotBlank()) {
                        lastStableTitle = latestTitle
                    }
                    if (latestArtist.isNotBlank()) {
                        lastStableArtist = latestArtist
                    }
                    lastStableExplicit = latestExplicit
                    latestPaletteColor?.let {
                        lastStableColor = liftAmbientColor(it)
                    }

                    transitionState = CrossfadeTransitionState.IDLE
                    enterAnim.snapTo(0f)
                    exitAnim.snapTo(0f)
                    mixAnim.snapTo(0f)
                    outgoingThumbUrl = null
                    incomingThumbUrl = null
                    outgoingTitle = null
                    outgoingArtist = null
                    incomingTitle = null
                    incomingArtist = null
                    oldBitmap = null
                    newBitmap = null
                }
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
        transitionState,
        isCrossfading,
        resolvedOldThumbUrl,
        resolvedNewThumbUrl,
        resolvedOldColor,
        newColor,
        outgoingTitle,
        outgoingArtist,
        outgoingExplicit,
        incomingTitle,
        incomingArtist,
        incomingExplicit,
        newBitmap,
    ) {
        DualCardCrossfadeState(
            transitionProgress = transitionProgress,
            enterProgress = enterProgress,
            exitProgress = exitProgress,
            transitionState = transitionState,
            isCrossfading = isCrossfading,
            oldThumbnailUrl = resolvedOldThumbUrl,
            newThumbnailUrl = resolvedNewThumbUrl,
            oldTrackColor = resolvedOldColor,
            newTrackColor = newColor,
            oldTitle = outgoingTitle ?: lastStableTitle,
            oldArtist = outgoingArtist ?: lastStableArtist,
            oldIsExplicit = outgoingExplicit,
            newTitle = incomingTitle ?: currentTitle,
            newArtist = incomingArtist ?: currentArtist,
            newIsExplicit = incomingExplicit,
            newBitmap = newBitmap,
            onOldBitmapLoaded = { oldBitmap = it },
            onNewBitmapLoaded = { newBitmap = it },
        )
    }
}

/**
 * Dynamic Ambient Dark Gradient background based on the color tones of the 2 tracks.
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

        // Left card radial ambient glow (outgoing song sliding 50% -> 25% and fading out on exit)
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

        // Right card radial ambient glow (incoming song at 75% -> 50% width)
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
 * 3D Dual-Card Layout + Luminous Stardust Particle Effect + State-Machine Driven Transitions.
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

    // Gentle floating breathing phase during crossfade
    val infiniteTransition = rememberInfiniteTransition(label = "dualCardMusicalSway")
    val swayCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 3600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "dualCardSwayPhase",
    )

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val containerW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val fullCardPx = min(containerW, containerH).coerceAtLeast(1f)
        val fullCardDp = with(density) { fullCardPx.toDp() }

        // Lock hold card size to 120.dp / 0.31f * screenWidth (30% - 32% of screen width)
        val targetHoldCardPx =
            min(
                with(density) { HOLD_CARD_SIZE_DP.toPx() },
                containerW * HOLD_SCALE,
            ).coerceAtLeast(1f)
        val effectiveHoldScale = (targetHoldCardPx / fullCardPx).coerceIn(0.25f, 0.34f)

        // Position cards at ~35% of player height during crossfade, returning smoothly to 0f on exit
        val holdOffsetY = -containerH * 0.04f

        val enter =
            when (crossfadeState.transitionState) {
                CrossfadeTransitionState.IDLE -> 0f
                CrossfadeTransitionState.ENTERING -> crossfadeState.enterProgress.coerceIn(0f, 1f)
                CrossfadeTransitionState.CROSSFADING,
                CrossfadeTransitionState.EXITING,
                -> 1f
            }
        val exit =
            when (crossfadeState.transitionState) {
                CrossfadeTransitionState.EXITING -> crossfadeState.exitProgress.coerceIn(0f, 1f)
                else -> 0f
            }

        val oldPose =
            oldCardPose(
                enterProgress = enter,
                exitProgress = exit,
                width = containerW,
                swayPhaseRad = swayCycle,
                holdScale = effectiveHoldScale,
                holdOffsetY = holdOffsetY,
            )
        val newPose =
            newCardPose(
                enterProgress = enter,
                exitProgress = exit,
                width = containerW,
                swayPhaseRad = swayCycle,
                holdScale = effectiveHoldScale,
                holdOffsetY = holdOffsetY,
            )

        val cardVisualHeightPx = fullCardPx * effectiveHoldScale
        // With 120.dp cards centered at 24% and 76%, the open gap between them spans ~39.5% to ~60.5% (~120dp wide)
        val oldCardRightEdgePx = oldPose.centerX + (fullCardPx * oldPose.scale) * 0.48f
        val newCardLeftEdgePx = newPose.centerX - (fullCardPx * newPose.scale) * 0.48f
        val gapLeftEdgePx = min(oldCardRightEdgePx, containerW * 0.38f).coerceIn(0f, containerW)
        val gapRightEdgePx = max(newCardLeftEdgePx, containerW * 0.62f).coerceIn(gapLeftEdgePx + 1f, containerW)
        val stardustCenterY = containerH * 0.5f + holdOffsetY * enter * (1f - exit)

        // 1. Outgoing (Old) Card — shrinks 1f -> 120.dp (0.31f) & slides to left (24%, rotationY = -8f), fades out on exit
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

        // 2. Stardust particles glowing with BlendMode.Screen floating in the ~120dp gap between the cards
        val stardustAlpha = (enter * (1f - exit * 1.15f)).coerceIn(0f, 1f)
        if (stardustAlpha > 0.01f) {
            WhiteStardustParticles(
                gapStartX = gapLeftEdgePx,
                gapEndX = gapRightEdgePx,
                centerY = stardustCenterY,
                bandHeight = cardVisualHeightPx * 1.08f,
                alphaMultiplier = stardustAlpha,
                oldTrackColor = crossfadeState.oldTrackColor,
                newTrackColor = crossfadeState.newTrackColor,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 3. Incoming (New) Card — slides in from right to 76% (120.dp, rotationY = +8f), exits to 50% (offset X = 0), scale -> 1.0f
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
                    transformOrigin = TransformOrigin.Center
                    translationX = pose.centerX - containerWidthPx * 0.5f
                    translationY = pose.offsetY
                    scaleX = pose.scale
                    scaleY = pose.scale
                    rotationY = pose.rotationY
                    alpha = pose.alpha
                    cameraDistance = CAMERA_DISTANCE_FACTOR * this.density
                    shadowElevation = shadowPx * chromeAlpha
                    shape = cardShape
                    clip = true
                    ambientShadowColor = Color.Black
                    spotShadowColor = Color.Black
                }.background(Color(0xFF1A1A20).copy(alpha = chromeAlpha), cardShape)
                .border(
                    width = (1.0f / currentScale).dp,
                    brush =
                        Brush.linearGradient(
                            0f to Color.White.copy(alpha = 0.24f * chromeAlpha),
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
 * Luminous Stardust Particle Effect:
 * Floating, glowing bokeh/dust particles in the space between the two 3D cards,
 * rendered with [BlendMode.Screen] and radial gradients so they drift organically
 * and dissolve naturally into the gap rather than sticking statically to card surfaces.
 */
@Composable
private fun WhiteStardustParticles(
    gapStartX: Float,
    gapEndX: Float,
    centerY: Float,
    bandHeight: Float,
    alphaMultiplier: Float,
    oldTrackColor: Color,
    newTrackColor: Color,
    modifier: Modifier = Modifier,
) {
    val particles = remember { generateStardustSpecs(STARDUST_COUNT) }

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
    val warmTint = remember(oldTrackColor, newTrackColor) {
        lerp(
            lerp(oldTrackColor, newTrackColor, 0.5f),
            Color(0xFFFFE5D0),
            0.65f,
        )
    }

    Canvas(modifier = modifier) {
        val spanX = (gapEndX - gapStartX).coerceAtLeast(1f)
        val gapCenterX = (gapStartX + gapEndX) * 0.5f
        val halfBandY = (bandHeight * 0.5f).coerceAtLeast(1f)
        val twoPi = (2.0 * PI).toFloat()

        // Soft central ambient glow in the gap between the two cards
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            warmTint.copy(alpha = 0.20f * alphaMultiplier),
                            warmTint.copy(alpha = 0.06f * alphaMultiplier),
                            Color.Transparent,
                        ),
                    center = Offset(gapCenterX, centerY),
                    radius = max(spanX * 0.85f, halfBandY * 0.75f),
                ),
            radius = max(spanX * 0.85f, halfBandY * 0.75f),
            center = Offset(gapCenterX, centerY),
            blendMode = BlendMode.Screen,
        )

        for (i in particles.indices) {
            val p = particles[i]

            // Individual particle lifecycle (0f -> 1f): spawns, floats across the gap, and dissolves naturally
            val life = (p.initialProgress + elapsedSec * p.driftSpeed) % 1f

            // Smooth bell-curve fade in and fade out over the particle's life so it never blinks or teleports
            val lifeEnvelope = sin(life * PI).toFloat().coerceIn(0f, 1f)

            // Horizontal position: concentrated in the open space between the two cards, drifting gently
            val startX = gapStartX + p.spawnXFraction * spanX
            val driftX = (life - 0.5f) * spanX * p.horizontalRangeFraction
            val waveX = sin(elapsedSec * p.swaySpeed + p.phaseOffset) * p.swayAmplitudeDp.dp.toPx() * 0.45f
            val x = startX + driftX + waveX

            // Vertical float: gentle upward/downward buoyancy + sinusoidal wave
            val floatY = (life - 0.5f) * p.verticalDriftDp.dp.toPx()
            val waveY = cos(elapsedSec * p.swaySpeed * 0.85f + p.phaseOffset) * p.swayAmplitudeDp.dp.toPx()
            val y = (centerY + p.verticalNormalized * halfBandY + floatY + waveY).coerceIn(0f, size.height)

            // Spatial bell-curve envelope: peaks in the middle of the gap, fades out smoothly near card edges
            val normX = ((x - gapStartX) / spanX).coerceIn(0f, 1f)
            val spatialEnvelope = sin(normX * PI).toFloat().coerceIn(0f, 1f)
            val verticalDistNorm = (abs(y - centerY) / halfBandY).coerceIn(0f, 1f)
            val verticalEnvelope = (1f - verticalDistNorm * verticalDistNorm * 0.65f).coerceIn(0f, 1f)

            // Gentle organic shimmer
            val shimmer = 0.72f + 0.28f * (0.5f + 0.5f * sin(elapsedSec * p.twinkleSpeed * twoPi + p.phaseOffset))
            val alpha =
                (p.baseAlpha * lifeEnvelope * spatialEnvelope * verticalEnvelope * shimmer * alphaMultiplier)
                    .coerceIn(0f, 1f)
            if (alpha <= 0.01f) continue

            // Subtle scale breathing as the particle floats and dissolves
            val scalePulse = 0.75f + 0.40f * lifeEnvelope
            val radiusPx = p.radiusDp.dp.toPx() * scalePulse
            val haloRadiusPx = radiusPx * 3.8f
            val center = Offset(x, y)
            val particleTint = lerp(p.tint, warmTint, 0.35f)

            // Soft luminous bokeh halo with BlendMode.Screen
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                Color.White.copy(alpha = alpha),
                                particleTint.copy(alpha = alpha * 0.68f),
                                particleTint.copy(alpha = alpha * 0.22f),
                                Color.Transparent,
                            ),
                        center = center,
                        radius = haloRadiusPx,
                    ),
                radius = haloRadiusPx,
                center = center,
                blendMode = BlendMode.Screen,
            )

            // Bright pearlescent core
            drawCircle(
                color = Color.White.copy(alpha = (alpha * 0.95f).coerceIn(0f, 1f)),
                radius = radiusPx * 0.82f,
                center = center,
                blendMode = BlendMode.Screen,
            )
        }
    }
}

@Immutable
private data class StardustSpec(
    val initialProgress: Float,
    val spawnXFraction: Float,
    val horizontalRangeFraction: Float,
    val verticalNormalized: Float,
    val verticalDriftDp: Float,
    val driftSpeed: Float,
    val swayAmplitudeDp: Float,
    val swaySpeed: Float,
    val twinkleSpeed: Float,
    val phaseOffset: Float,
    val radiusDp: Float,
    val baseAlpha: Float,
    val tint: Color,
)

private const val STARDUST_COUNT = 44
private const val STARDUST_CYCLE_MS = 24000

// Warm pearlescent & champagne stardust palette matching Apple Music crossfade
private val StardustPalette =
    listOf(
        Color(0xFFFFFFFF), // Pure Bright White
        Color(0xFFFFF3E6), // Warm Champagne White
        Color(0xFFFFE2C4), // Soft Peach Gold
        Color(0xFFF5E6FF), // Soft Pearl Lavender
        Color(0xFFFFD8B8), // Warm Rose Gold Glow
        Color(0xFFE8F4FF), // Starlight Ice White
    )

private fun generateStardustSpecs(count: Int): List<StardustSpec> {
    val rng = Random(0x57A8D057)
    val twoPi = (2.0 * PI).toFloat()
    return List(count) { idx ->
        // Bell-curve distribution around the center of the gap (0.5f)
        val u1 = rng.nextFloat()
        val u2 = rng.nextFloat()
        val centeredX = (0.18f + (u1 + u2) * 0.32f).coerceIn(0.12f, 0.88f)
        val centeredY = ((rng.nextFloat() + rng.nextFloat() - 1f) * 0.72f).coerceIn(-0.75f, 0.75f)
        val isBokehOrb = idx % 4 == 0
        StardustSpec(
            initialProgress = (idx.toFloat() / count + rng.nextFloat() * 0.12f) % 1f,
            spawnXFraction = centeredX,
            horizontalRangeFraction = (0.35f + rng.nextFloat() * 0.45f) * (if (idx % 3 == 0) -0.6f else 1f),
            verticalNormalized = centeredY,
            verticalDriftDp = -28f + rng.nextFloat() * 56f,
            driftSpeed = 0.16f + rng.nextFloat() * 0.18f,
            swayAmplitudeDp = 4.5f + rng.nextFloat() * 10.5f,
            swaySpeed = 0.8f + rng.nextFloat() * 1.2f,
            twinkleSpeed = 0.25f + rng.nextFloat() * 0.45f,
            phaseOffset = rng.nextFloat() * twoPi,
            radiusDp = if (isBokehOrb) 2.2f + rng.nextFloat() * 2.0f else 1.1f + rng.nextFloat() * 1.5f,
            baseAlpha = if (isBokehOrb) 0.55f + rng.nextFloat() * 0.35f else 0.70f + rng.nextFloat() * 0.30f,
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
