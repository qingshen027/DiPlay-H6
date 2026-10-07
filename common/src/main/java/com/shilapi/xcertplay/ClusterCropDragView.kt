// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Drag-to-crop editor for the instrument-cluster fly-screen.
 *
 * The rectangle is the crop box in the 1792x720 (DesaysvCluster.STREAM_*) source stream; the panel output is
 * always 800x480, so a smaller box = a zoomed-in picture (this is exactly the
 * box-app behaviour: it scales the *picture*, not the 800x480 panel).
 *
 * Two gestures share the box:
 *  - drag inside the **central area** of the box  -> MOVE (pan, size unchanged)
 *  - drag near an **edge or corner** of the box     -> RESIZE (anchor the opposite edge/corner)
 *  - when the box fills the whole frame, tapping anywhere inside it RESIZES
 *    (anchored at the opposite corner) so a full-frame box is never "stuck".
 * Every change is reported through [onCropChanged] so the caller can persist it
 * and the live cluster follows.
 */
class ClusterCropDragView(
    context: Context,
    private var cropX: Int,
    private var cropY: Int,
    private var cropW: Int,
    private var cropH: Int,
    private val onCropChanged: (x: Int, y: Int, w: Int, h: Int) -> Unit,
) : View(context) {

    private val streamW = DesaysvCluster.STREAM_WIDTH   // 800
    private val streamH = DesaysvCluster.STREAM_HEIGHT  // 480
    private val minSize = 64
    // Distance (stream px) from an edge inside which a tap switches to RESIZE instead of MOVE.
    private val edgeBand = 56f

    private val paintBg = Color.argb(255, 38, 44, 58)
    private val paintDim = Paint().apply { color = Color.argb(190, 8, 12, 20) }
    private val paintBorder = Color.argb(255, 120, 224, 255)
    private val paintHandle = Color.argb(255, 120, 224, 255)
    private val paintGrid = Color.argb(70, 150, 200, 255)
    private val paintText = Color.argb(255, 241, 245, 252)

    private lateinit var borderPaint: Paint
    private lateinit var handlePaint: Paint
    private lateinit var gridPaint: Paint
    private lateinit var textPaint: Paint

    private var sx = 1f
    private var sy = 1f

    private enum class Mode { NONE, MOVE, RESIZE }
    private var mode = Mode.NONE
    // RESIZE: which edges follow the finger. The opposite edges stay pinned at their
    // down-time coordinates (fxL/fxR/fyT/fyB), which is what makes the box resize
    // around an anchor instead of drifting.
    private var aLeft = false
    private var aRight = false
    private var aTop = false
    private var aBottom = false
    private var fxL = 0f
    private var fxR = 0f
    private var fyT = 0f
    private var fyB = 0f
    // MOVE: finger offset captured at down-time so the box pans under the finger.
    private var mvDX = 0f
    private var mvDY = 0f

    init {
        borderPaint = Paint().apply { color = paintBorder; strokeWidth = dp(3f); style = Paint.Style.STROKE }
        handlePaint = Paint().apply { color = paintHandle }
        gridPaint = Paint().apply { color = paintGrid; strokeWidth = dp(1f) }
        textPaint = Paint().apply { color = paintText; textSize = dp(13f) }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Respect BOTH the width and height the parent offers: pick the largest
        // 5:3 rectangle that fits, so sx == sy and touch maps 1:1 onto the drawing.
        val maxW = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val maxH = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        val hByW = (maxW * streamH / streamW.toFloat()).roundToInt()
        val (w, h) = if (hByW <= maxH) {
            maxW to hByW
        } else {
            (maxH * streamW / streamH.toFloat()).roundToInt() to maxH
        }
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sx = w / streamW.toFloat()
        sy = h / streamH.toFloat()
    }

    private fun rx() = cropX * sx
    private fun ry() = cropY * sy
    private fun rw() = cropW * sx
    private fun rh() = cropH * sy

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(paintBg)
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, gridPaint)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, gridPaint)
        }
        val left = rx(); val top = ry(); val right = left + rw(); val bottom = top + rh()
        canvas.drawRect(0f, 0f, left, h, paintDim)
        canvas.drawRect(right, 0f, w, h, paintDim)
        canvas.drawRect(left, 0f, right, top, paintDim)
        canvas.drawRect(left, bottom, right, h, paintDim)
        canvas.drawRect(left, top, right, bottom, borderPaint)
        val hs = dp(9f)
        for ((hx, hy) in listOf(
            Pair(left, top), Pair(right, top), Pair(left, bottom), Pair(right, bottom)
        )) {
            canvas.drawCircle(hx, hy, hs, handlePaint)
        }
        val modeText = when (mode) {
            Mode.MOVE -> "移动"
            Mode.RESIZE -> "缩放"
            else -> ""
        }
        if (modeText.isNotEmpty()) {
            canvas.drawText(modeText, right - dp(40f), top + dp(18f), textPaint)
        }
        canvas.drawText("X$cropX Y$cropY  ${cropW}×${cropH}", left + dp(6f), top + dp(18f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tx = event.x / sx
        val ty = event.y / sy
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Touch tolerance in screen px (40dp) so the corner handles are easy to grab.
                val tolPx = 40f * resources.displayMetrics.density
                val tolX = tolPx / sx
                val tolY = tolPx / sy
                val left = cropX.toFloat(); val top = cropY.toFloat()
                val right = (cropX + cropW).toFloat(); val bottom = (cropY + cropH).toFloat()
                // 1) exact corner handle -> that corner moves, opposite corner is pinned
                val hitCorner = when {
                    tx in (left - tolX)..(left + tolX) && ty in (top - tolY)..(top + tolY) -> 1
                    tx in (right - tolX)..(right + tolX) && ty in (top - tolY)..(top + tolY) -> 2
                    tx in (left - tolX)..(left + tolX) && ty in (bottom - tolY)..(bottom + tolY) -> 3
                    tx in (right - tolX)..(right + tolX) && ty in (bottom - tolY)..(bottom + tolY) -> 4
                    else -> 0
                }
                if (hitCorner != 0) {
                    fxL = left; fxR = right; fyT = top; fyB = bottom
                    when (hitCorner) {
                        1 -> { aLeft = true; aTop = true }      // drag top-left, anchor bottom-right
                        2 -> { aRight = true; aTop = true }     // drag top-right, anchor bottom-left
                        3 -> { aLeft = true; aBottom = true }    // drag bottom-left, anchor top-right
                        else -> { aRight = true; aBottom = true } // drag bottom-right, anchor top-left
                    }
                    mode = Mode.RESIZE
                    return true
                }
                // 2) tap inside the box
                if (tx in left..right && ty in top..bottom) {
                    val full = cropX <= 0 && cropY <= 0 &&
                            cropX + cropW >= streamW - 1 && cropY + cropH >= streamH - 1
                    if (full) {
                        // Full-frame box: shrink-from-anywhere (box-app style). The tapped
                        // half decides which corner is active; the opposite corner is anchored.
                        fxL = left; fxR = right; fyT = top; fyB = bottom
                        aLeft = tx < (left + right) / 2f
                        aRight = tx >= (left + right) / 2f
                        aTop = ty < (top + bottom) / 2f
                        aBottom = ty >= (top + bottom) / 2f
                        mode = Mode.RESIZE
                        return true
                    }
                    val nearLeft = tx - left < edgeBand
                    val nearRight = right - tx < edgeBand
                    val nearTop = ty - top < edgeBand
                    val nearBottom = bottom - ty < edgeBand
                    if (nearLeft || nearRight || nearTop || nearBottom) {
                        fxL = left; fxR = right; fyT = top; fyB = bottom
                        aLeft = nearLeft; aRight = nearRight; aTop = nearTop; aBottom = nearBottom
                        mode = Mode.RESIZE
                        return true
                    }
                    // central area -> pan the whole box, size unchanged
                    mvDX = tx - left
                    mvDY = ty - top
                    mode = Mode.MOVE
                    return true
                }
                mode = Mode.NONE
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.MOVE) {
                    cropX = (tx - mvDX).roundToInt().coerceIn(0, streamW - cropW)
                    cropY = (ty - mvDY).roundToInt().coerceIn(0, streamH - cropH)
                    commit()
                    return true
                }
                if (mode == Mode.RESIZE) {
                    var L = fxL; var R = fxR; var T = fyT; var B = fyB
                    if (aLeft) {
                        L = tx.coerceIn(0f, fxR - minSize)
                    } else if (aRight) {
                        R = tx.coerceIn(fxL + minSize, streamW.toFloat())
                    }
                    if (aTop) {
                        T = ty.coerceIn(0f, fyB - minSize)
                    } else if (aBottom) {
                        B = ty.coerceIn(fyT + minSize, streamH.toFloat())
                    }
                    cropX = L.toInt(); cropY = T.toInt()
                    cropW = (R - L).toInt(); cropH = (B - T).toInt()
                    commit()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun commit() {
        cropX = cropX.coerceIn(0, streamW - minSize)
        cropY = cropY.coerceIn(0, streamH - minSize)
        cropW = cropW.coerceIn(minSize, streamW - cropX)
        cropH = cropH.coerceIn(minSize, streamH - cropY)
        invalidate()
        onCropChanged(cropX, cropY, cropW, cropH)
    }

    /** Restores the full-frame crop (0,0,streamW,streamH) — default display-all state. */
    fun reset() {
        cropX = 0; cropY = 0; cropW = streamW; cropH = streamH
        invalidate()
        onCropChanged(cropX, cropY, cropW, cropH)
    }
}
