package com.shilapi.xcertplay

import android.graphics.Matrix

/**
 * Crop-box rendering for the Desay SV fly-screen. The X/Y/W/H settings select a
 * rectangle in the SOURCE stream (which part of the map you see); that rectangle is
 * scaled to FILL the 800x480 cluster panel, so there is never a black frame around
 * the content (this is what the CarPlay-box apps do). An optional screen margin
 * insets the content window to compensate cluster bezels that physically mask the
 * panel edges — the margin stays black, but it sits in the invisible zone anyway.
 */
object DesaysvCrop {

    /**
     * TextureView transform mapping the crop rectangle of a streamW x streamH buffer
     * onto a viewW x viewH view inset by margin. TextureView stretches the buffer to
     * the view bounds by default, so the matrix is expressed in that view space.
     *
     * stretch = false keeps the source aspect: uniform scale, overflow cropped
     * (fill). stretch = true scales X/Y independently: the crop rectangle fills the
     * window exactly and distorts when the aspects differ.
     */
    fun matrix(
        cropX: Float, cropY: Float, cropW: Float, cropH: Float,
        streamW: Int, streamH: Int,
        viewW: Int, viewH: Int,
        margin: Int, stretch: Boolean,
    ): Matrix {
        val winW = (viewW - 2 * margin).coerceAtLeast(1).toFloat()
        val winH = (viewH - 2 * margin).coerceAtLeast(1).toFloat()
        val cw = cropW.coerceAtLeast(1f)
        val ch = cropH.coerceAtLeast(1f)
        val matrix = Matrix()
        if (stretch) {
            val sx = winW * streamW / (cw * viewW)
            val sy = winH * streamH / (ch * viewH)
            val tx = margin - cropX * (viewW.toFloat() / streamW) * sx
            val ty = margin - cropY * (viewH.toFloat() / streamH) * sy
            matrix.setScale(sx, sy)
            matrix.postTranslate(tx, ty)
        } else {
            // Uniform cover: keep the SOURCE aspect so the panel never shows a
            // stretched picture. TextureView stretches the whole buffer to the view
            // bounds by default, so we fold the buffer->view aspect into the transform
            // with separate X/Y scales. k is the uniform scale (buffer px -> window
            // px); kx/ky are that scale expressed in view space (post TextureView
            // stretch). Final buffer-pixel scale is k on both axes -> no distortion.
            // Overflow (when source aspect != panel aspect) is cropped, like a
            // background-cover; the centred content such as lane guidance stays put.
            val k = maxOf(winW / cw, winH / ch)
            val kx = k * (streamW.toFloat() / viewW)
            val ky = k * (streamH.toFloat() / viewH)
            val centerX = cropX + cw / 2f
            val centerY = cropY + ch / 2f
            val tx = viewW / 2f - k * centerX
            val ty = viewH / 2f - k * centerY
            matrix.setScale(kx, ky)
            matrix.postTranslate(tx, ty)
        }
        return matrix
    }
}
