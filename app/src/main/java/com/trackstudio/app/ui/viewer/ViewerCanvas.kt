package com.trackstudio.app.ui.viewer

import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.trackstudio.app.tracking.CameraFeatureObservation
import com.trackstudio.app.tracking.TrackingAnchorSegment
import androidx.compose.ui.ExperimentalComposeUiApi
import com.trackstudio.app.ui.theme.PanelSurface
import com.trackstudio.app.ui.theme.TextSecondary
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class ViewerTransform(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f
) {
    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ViewerCanvas(
    bitmap: Bitmap?,
    trackingPoints: List<CameraFeatureObservation> = emptyList(),
    trackingPointColor: Color = Color(0xFF00E5FF),
    trackingPointSizeDp: Float = 3f,
    trackingPointOutline: Boolean = true,
    anchorSegments: List<TrackingAnchorSegment> = emptyList(),
    trackingCoordinateWidth: Int = 0,
    trackingCoordinateHeight: Int = 0,
    anchorStrokeDp: Float = 2.2f,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    requestedZoom: Float? = null,
    onZoomChanged: ((Float) -> Unit)? = null,
    onImageCoordinateTap: ((x: Float, y: Float) -> Unit)? = null,
    backgroundColor: Color = PanelSurface,
    showZoomLabel: Boolean = true
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var transform by remember { mutableStateOf(ViewerTransform()) }

    LaunchedEffect(requestedZoom, bitmap) {
        if (bitmap != null && requestedZoom != null) {
            transform = clampTransform(
                transform.copy(zoom = requestedZoom),
                bitmap,
                containerSize
            )
        }
        if (bitmap == null) {
            transform = ViewerTransform()
        }
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .onSizeChanged { size ->
                containerSize = size
                transform = clampTransform(transform, bitmap, size)
            }
            .pointerInput(bitmap, containerSize) {
                detectTransformGestures(
                    panZoomLock = false
                ) { centroid, pan, zoomChange, _ ->
                    if (bitmap == null) return@detectTransformGestures

                    val oldZoom = transform.zoom
                    val newZoom = (oldZoom * zoomChange).coerceIn(
                        ViewerTransform.MIN_ZOOM,
                        ViewerTransform.MAX_ZOOM
                    )
                    val zoomRatio = if (oldZoom > 0f) {
                        newZoom / oldZoom
                    } else {
                        1f
                    }

                    val center = Offset(
                        containerSize.width / 2f,
                        containerSize.height / 2f
                    )

                    val newPanX =
                        centroid.x - center.x -
                            (centroid.x - center.x - transform.panX) * zoomRatio +
                            pan.x

                    val newPanY =
                        centroid.y - center.y -
                            (centroid.y - center.y - transform.panY) * zoomRatio +
                            pan.y

                    transform = clampTransform(
                        ViewerTransform(
                            zoom = newZoom,
                            panX = newPanX,
                            panY = newPanY
                        ),
                        bitmap,
                        containerSize
                    )

                    onZoomChanged?.invoke(transform.zoom)
                }
            }
            .pointerInteropFilter { event ->
                if (bitmap == null || event.actionMasked != MotionEvent.ACTION_SCROLL) {
                    false
                } else {
                    val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (scrollY == 0f) {
                        false
                    } else {
                        val oldZoom = transform.zoom
                        val factor = exp((-scrollY * 0.12f).toDouble()).toFloat()
                        val newZoom = (oldZoom * factor).coerceIn(
                            ViewerTransform.MIN_ZOOM,
                            ViewerTransform.MAX_ZOOM
                        )

                        if (newZoom != oldZoom) {
                            val center = Offset(
                                containerSize.width / 2f,
                                containerSize.height / 2f
                            )
                            val ratio = newZoom / oldZoom

                            transform = clampTransform(
                                ViewerTransform(
                                    zoom = newZoom,
                                    panX = event.x - center.x -
                                        (event.x - center.x - transform.panX) * ratio,
                                    panY = event.y - center.y -
                                        (event.y - center.y - transform.panY) * ratio
                                ),
                                bitmap,
                                containerSize
                            )
                            onZoomChanged?.invoke(transform.zoom)
                        }
                        true
                    }
                }
            }
            .pointerInput(bitmap, containerSize, transform) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        if (bitmap == null) return@detectTapGestures

                        val nextZoom = when {
                            transform.zoom < 1.5f -> 2f
                            transform.zoom < 3f -> 4f
                            else -> 1f
                        }

                        if (nextZoom == 1f) {
                            transform = ViewerTransform()
                        } else {
                            val center = Offset(
                                containerSize.width / 2f,
                                containerSize.height / 2f
                            )
                            val ratio = nextZoom / transform.zoom

                            transform = clampTransform(
                                ViewerTransform(
                                    zoom = nextZoom,
                                    panX = position.x - center.x -
                                        (position.x - center.x - transform.panX) * ratio,
                                    panY = position.y - center.y -
                                        (position.y - center.y - transform.panY) * ratio
                                ),
                                bitmap,
                                containerSize
                            )
                        }

                        onZoomChanged?.invoke(transform.zoom)
                    },
                    onTap = { position ->
                        if (bitmap != null && onImageCoordinateTap != null) {
                            screenToImageCoordinates(
                                position,
                                bitmap,
                                containerSize,
                                transform
                            )?.let { imagePosition ->
                                onImageCoordinateTap(imagePosition.x, imagePosition.y)
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Current frame",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.zoom
                        scaleY = transform.zoom
                        translationX = transform.panX
                        translationY = transform.panY
                    }
            )

            if (trackingPoints.isNotEmpty() || anchorSegments.isNotEmpty()) {
                // Compact 3DEqualizer-inspired track reticle: fine arms, a
                // clear center gap and a tiny center target. Keep dimensions in
                // screen-independent dp so markers stay unobtrusive on phones.
                val markerHalfSize = with(LocalDensity.current) { trackingPointSizeDp.coerceIn(2f, 5.5f).dp.toPx() }
                val markerStroke = with(LocalDensity.current) { (trackingPointSizeDp * 0.34f).coerceIn(0.9f, 1.5f).dp.toPx() }
                val markerOutline = markerStroke + with(LocalDensity.current) { 1.1.dp.toPx() }
                val anchorStroke = with(LocalDensity.current) { anchorStrokeDp.coerceIn(1f, 5f).dp.toPx() }
                Canvas(
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = transform.zoom
                        scaleY = transform.zoom
                        translationX = transform.panX
                        translationY = transform.panY
                    }
                ) {
                    val fitScale = min(size.width / bitmap.width, size.height / bitmap.height)
                    val imageLeft = (size.width - bitmap.width * fitScale) * 0.5f
                    val imageTop = (size.height - bitmap.height * fitScale) * 0.5f
                    val coordinateScaleX = if (trackingCoordinateWidth > 0) bitmap.width.toFloat() / trackingCoordinateWidth else 1f
                    val coordinateScaleY = if (trackingCoordinateHeight > 0) bitmap.height.toFloat() / trackingCoordinateHeight else 1f
                    anchorSegments.forEach { segment ->
                        val startPoint = Offset(
                            imageLeft + segment.startX * coordinateScaleX * fitScale,
                            imageTop + segment.startY * coordinateScaleY * fitScale
                        )
                        val endPoint = Offset(
                            imageLeft + segment.endX * coordinateScaleX * fitScale,
                            imageTop + segment.endY * coordinateScaleY * fitScale
                        )
                        drawLine(
                            color = Color(segment.colorArgb),
                            start = startPoint,
                            end = endPoint,
                            strokeWidth = anchorStroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                    trackingPoints.forEach { point ->
                        val x = imageLeft + point.x.toFloat() * coordinateScaleX * fitScale
                        val y = imageTop + point.y.toFloat() * coordinateScaleY * fitScale
                        if (x >= imageLeft && x <= imageLeft + bitmap.width * fitScale &&
                            y >= imageTop && y <= imageTop + bitmap.height * fitScale
                        ) {
                            val center = Offset(x, y)
                            // Four separated arms keep the exact feature center visible,
                            // similar to a compact match-point reticle used in 3D trackers.
                            val arms = listOf(
                                Offset(center.x - markerHalfSize, center.y) to Offset(center.x + markerHalfSize, center.y),
                                Offset(center.x, center.y - markerHalfSize) to Offset(center.x, center.y + markerHalfSize)
                            )
                            arms.forEach { (start, end) ->
                                if (trackingPointOutline) drawLine(
                                    color = Color.Black.copy(alpha = 0.92f),
                                    start = start, end = end, strokeWidth = markerOutline,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Square
                                )
                                drawLine(
                                    color = trackingPointColor,
                                    start = start, end = end, strokeWidth = markerStroke,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Square
                                )
                            }
                        }
                    }
                }
            }

            if (showZoomLabel) {
                Text(
                    text = "${(transform.zoom * 100f).toInt()}%",
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        } else if (isLoading) {
            CircularProgressIndicator()
        } else {
            Text(
                text = "No frame loaded",
                color = TextSecondary
            )
        }
    }
}

private fun clampTransform(
    transform: ViewerTransform,
    bitmap: Bitmap?,
    containerSize: IntSize
): ViewerTransform {
    if (
        bitmap == null ||
        containerSize.width <= 0 ||
        containerSize.height <= 0
    ) {
        return transform
    }

    val imageWidth = bitmap.width.toFloat()
    val imageHeight = bitmap.height.toFloat()
    val containerWidth = containerSize.width.toFloat()
    val containerHeight = containerSize.height.toFloat()

    val fitScale = min(
        containerWidth / imageWidth,
        containerHeight / imageHeight
    )

    val fittedWidth = imageWidth * fitScale
    val fittedHeight = imageHeight * fitScale
    val scaledWidth = fittedWidth * transform.zoom
    val scaledHeight = fittedHeight * transform.zoom

    val maxPanX = max(0f, (scaledWidth - containerWidth) / 2f)
    val maxPanY = max(0f, (scaledHeight - containerHeight) / 2f)

    return transform.copy(
        zoom = transform.zoom.coerceIn(
            ViewerTransform.MIN_ZOOM,
            ViewerTransform.MAX_ZOOM
        ),
        panX = transform.panX.coerceIn(-maxPanX, maxPanX),
        panY = transform.panY.coerceIn(-maxPanY, maxPanY)
    )
}

private fun screenToImageCoordinates(
    position: Offset,
    bitmap: Bitmap,
    containerSize: IntSize,
    transform: ViewerTransform
): Offset? {
    if (containerSize.width <= 0 || containerSize.height <= 0) return null

    val imageWidth = bitmap.width.toFloat()
    val imageHeight = bitmap.height.toFloat()
    val containerWidth = containerSize.width.toFloat()
    val containerHeight = containerSize.height.toFloat()

    val fitScale = min(
        containerWidth / imageWidth,
        containerHeight / imageHeight
    )
    if (fitScale <= 0f) return null

    val fittedWidth = imageWidth * fitScale
    val fittedHeight = imageHeight * fitScale
    val baseLeft = (containerWidth - fittedWidth) / 2f
    val baseTop = (containerHeight - fittedHeight) / 2f

    val centerX = containerWidth / 2f
    val centerY = containerHeight / 2f

    val unscaledX =
        (position.x - centerX - transform.panX) / transform.zoom + centerX
    val unscaledY =
        (position.y - centerY - transform.panY) / transform.zoom + centerY

    val localX = unscaledX - baseLeft
    val localY = unscaledY - baseTop

    if (
        localX < 0f ||
        localY < 0f ||
        localX > fittedWidth ||
        localY > fittedHeight
    ) {
        return null
    }

    return Offset(
        x = (localX / fitScale).coerceIn(0f, imageWidth),
        y = (localY / fitScale).coerceIn(0f, imageHeight)
    )
}
