package com.trackstudio.app.ui.viewer

import android.graphics.Bitmap
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.trackstudio.app.ui.theme.PanelSurface
import com.trackstudio.app.ui.theme.TextSecondary
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

@Composable
fun ViewerCanvas(
    bitmap: Bitmap?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    requestedZoom: Float? = null,
    onZoomChanged: ((Float) -> Unit)? = null,
    onImageCoordinateTap: ((x: Float, y: Float) -> Unit)? = null,
    backgroundColor: Color = PanelSurface,
    showZoomLabel: Boolean = true
) {
    var containerSize by remember {
        mutableStateOf(IntSize.Zero)
    }

    var transform by remember {
        mutableStateOf(ViewerTransform())
    }

    /*
     * External zoom buttons.
     */
    LaunchedEffect(requestedZoom) {

        if (
            requestedZoom != null &&
            bitmap != null
        ) {
            transform =
                clampTransform(
                    ViewerTransform(
                        zoom = requestedZoom,
                        panX = transform.panX,
                        panY = transform.panY
                    ),
                    bitmap,
                    containerSize
                )
        }
    }

    /*
     * Reset when there is no image.
     */
    LaunchedEffect(bitmap) {

        if (bitmap == null) {
            transform =
                ViewerTransform()
        }
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .onSizeChanged { size ->

                containerSize = size

                transform =
                    clampTransform(
                        transform,
                        bitmap,
                        size
                    )
            }
            .pointerInput(
                bitmap,
                containerSize
            ) {

                detectTransformGestures {
                        centroid,
                        pan,
                        zoomChange,
                        _ ->

                    if (bitmap == null) {
                        return@detectTransformGestures
                    }

                    val oldZoom =
                        transform.zoom

                    val newZoom =
                        (
                            oldZoom * zoomChange
                        ).coerceIn(
                            ViewerTransform.MIN_ZOOM,
                            ViewerTransform.MAX_ZOOM
                        )

                    val zoomRatio =
                        if (oldZoom > 0f) {
                            newZoom / oldZoom
                        } else {
                            1f
                        }

                    val centerX =
                        containerSize.width /
                            2f

                    val centerY =
                        containerSize.height /
                            2f

                    /*
                     * Zoom around the gesture centroid.
                     */
                    val newPanX =
                        centroid.x -
                            (
                                centroid.x -
                                    centerX -
                                    transform.panX
                            ) * zoomRatio -
                            (
                                centroid.x -
                                    centerX
                            ) +
                            transform.panX +
                            pan.x

                    val newPanY =
                        centroid.y -
                            (
                                centroid.y -
                                    centerY -
                                    transform.panY
                            ) * zoomRatio -
                            (
                                centroid.y -
                                    centerY
                            ) +
                            transform.panY +
                            pan.y

                    val updated =
                        ViewerTransform(
                            zoom = newZoom,
                            panX = newPanX,
                            panY = newPanY
                        )

                    transform =
                        clampTransform(
                            updated,
                            bitmap,
                            containerSize
                        )

                    onZoomChanged?.invoke(
                        transform.zoom
                    )
                }
            }
            .pointerInput(
                bitmap,
                containerSize,
                transform
            ) {

                detectTapGestures(

                    onDoubleTap = { position ->

                        if (bitmap == null) {
                            return@detectTapGestures
                        }

                        val nextZoom =
                            when {
                                transform.zoom < 1.5f ->
                                    2f

                                transform.zoom < 3f ->
                                    4f

                                else ->
                                    1f
                            }

                        if (nextZoom == 1f) {

                            transform =
                                ViewerTransform()

                        } else {

                            val centerX =
                                containerSize.width /
                                    2f

                            val centerY =
                                containerSize.height /
                                    2f

                            val ratio =
                                nextZoom /
                                    transform.zoom

                            val newPanX =
                                position.x -
                                    centerX -
                                    (
                                        position.x -
                                            centerX -
                                            transform.panX
                                    ) * ratio

                            val newPanY =
                                position.y -
                                    centerY -
                                    (
                                        position.y -
                                            centerY -
                                            transform.panY
                                    ) * ratio

                            transform =
                                clampTransform(
                                    ViewerTransform(
                                        zoom = nextZoom,
                                        panX = newPanX,
                                        panY = newPanY
                                    ),
                                    bitmap,
                                    containerSize
                                )
                        }

                        onZoomChanged?.invoke(
                            transform.zoom
                        )
                    },

                    onTap = { position ->

                        if (
                            bitmap != null &&
                            onImageCoordinateTap != null
                        ) {

                            screenToImageCoordinates(
                                position,
                                bitmap,
                                containerSize,
                                transform
                            )?.let { imagePosition ->

                                onImageCoordinateTap(
                                    imagePosition.x,
                                    imagePosition.y
                                )
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {

        if (bitmap != null) {

            Image(
                bitmap =
                    bitmap.asImageBitmap(),

                contentDescription =
                    "Current frame",

                contentScale =
                    ContentScale.Fit,

                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {

                            scaleX =
                                transform.zoom

                            scaleY =
                                transform.zoom

                            translationX =
                                transform.panX

                            translationY =
                                transform.panY
                        }
            )

            if (showZoomLabel) {
                Text(
                    text =
                        "${(transform.zoom * 100f).toInt()}%",

                    color =
                        TextSecondary,

                    modifier =
                        Modifier.align(
                            Alignment.TopEnd
                        )
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

    val imageWidth =
        bitmap.width.toFloat()

    val imageHeight =
        bitmap.height.toFloat()

    val containerWidth =
        containerSize.width.toFloat()

    val containerHeight =
        containerSize.height.toFloat()

    val fitScale =
        min(
            containerWidth / imageWidth,
            containerHeight / imageHeight
        )

    val fittedWidth =
        imageWidth * fitScale

    val fittedHeight =
        imageHeight * fitScale

    val scaledWidth =
        fittedWidth * transform.zoom

    val scaledHeight =
        fittedHeight * transform.zoom

    val maxPanX =
        max(
            0f,
            (scaledWidth - containerWidth) /
                2f
        )

    val maxPanY =
        max(
            0f,
            (scaledHeight - containerHeight) /
                2f
        )

    return transform.copy(
        zoom =
            transform.zoom.coerceIn(
                ViewerTransform.MIN_ZOOM,
                ViewerTransform.MAX_ZOOM
            ),

        panX =
            transform.panX.coerceIn(
                -maxPanX,
                maxPanX
            ),

        panY =
            transform.panY.coerceIn(
                -maxPanY,
                maxPanY
            )
    )
}

private fun screenToImageCoordinates(
    position: Offset,
    bitmap: Bitmap,
    containerSize: IntSize,
    transform: ViewerTransform
): Offset? {

    if (
        containerSize.width <= 0 ||
        containerSize.height <= 0
    ) {
        return null
    }

    val imageWidth =
        bitmap.width.toFloat()

    val imageHeight =
        bitmap.height.toFloat()

    val containerWidth =
        containerSize.width.toFloat()

    val containerHeight =
        containerSize.height.toFloat()

    val fitScale =
        min(
            containerWidth / imageWidth,
            containerHeight / imageHeight
        )

    if (fitScale <= 0f) {
        return null
    }

    val fittedWidth =
        imageWidth * fitScale

    val fittedHeight =
        imageHeight * fitScale

    val baseLeft =
        (containerWidth - fittedWidth) /
            2f

    val baseTop =
        (containerHeight - fittedHeight) /
            2f

    val centerX =
        containerWidth / 2f

    val centerY =
        containerHeight / 2f

    /*
     * Reverse graphicsLayer transformation.
     */
    val unscaledX =
        (
            position.x -
                centerX -
                transform.panX
        ) / transform.zoom +
            centerX

    val unscaledY =
        (
            position.y -
                centerY -
                transform.panY
        ) / transform.zoom +
            centerY

    val localX =
        unscaledX - baseLeft

    val localY =
        unscaledY - baseTop

    if (
        localX < 0f ||
        localY < 0f ||
        localX > fittedWidth ||
        localY > fittedHeight
    ) {
        return null
    }

    return Offset(
        x = (
            localX / fitScale
        ).coerceIn(
            0f,
            imageWidth
        ),

        y = (
            localY / fitScale
        ).coerceIn(
            0f,
            imageHeight
        )
    )
}