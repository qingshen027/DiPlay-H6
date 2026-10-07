// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Full-screen editor for the instrument-cluster fly-screen display area.
 *
 * Opened from the main settings by the "仪表显示区域" button. Shows the 1792x720
 * source-stream crop box (drag to move, drag a corner to resize) plus a 0.8–1.5 zoom
 * ratio, with Reset and Save. Every change is persisted live through AirPlayPersistence,
 * so the physical cluster follows immediately while a CarPlay session is active.
 */
class ClusterCropActivity : Activity() {
    private lateinit var cropView: ClusterCropDragView
    private lateinit var zoomLabel: TextView
    private lateinit var coordLabel: TextView
    private var zoom = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.argb(255, 22, 28, 38))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.cluster_crop_open)
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.cluster_crop_editor_hint)
            textSize = 13f
            setTextColor(Color.argb(200, 200, 210, 230))
            setPadding(0, 0, 0, dp(8))
        })
        coordLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.argb(235, 166, 200, 255))
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(coordLabel)

        val sw = DesaysvCluster.STREAM_WIDTH
        val sh = DesaysvCluster.STREAM_HEIGHT
        val lx = AirPlayPersistence.loadDesaysvClusterX(this)
        val ly = AirPlayPersistence.loadDesaysvClusterY(this)
        val lw = AirPlayPersistence.loadDesaysvClusterW(this)
        val lh = AirPlayPersistence.loadDesaysvClusterH(this)
        // Default is the full frame; the view auto-switches a full-frame tap to RESIZE
        // (shrink-from-anywhere), so a full-frame box is never stuck. Persisted values
        // override this when the user has already adjusted the area.
        val init = intArrayOf(lx, ly, lw, lh)
        cropView = ClusterCropDragView(
            this,
            init[0], init[1], init[2], init[3],
        ) { x, y, w, h ->
            AirPlayPersistence.saveDesaysvClusterX(this, x)
            AirPlayPersistence.saveDesaysvClusterY(this, y)
            AirPlayPersistence.saveDesaysvClusterW(this, w)
            AirPlayPersistence.saveDesaysvClusterH(this, h)
            coordLabel.text = "X$x  Y$y  ${w}×${h}"
        }
        coordLabel.text = "X${init[0]}  Y${init[1]}  ${init[2]}×${init[3]}"
        val cropFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) }
            addView(cropView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        }
        root.addView(cropFrame)

        root.addView(TextView(this).apply {
            text = getString(R.string.cluster_zoom)
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(4))
        })
        zoomLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.argb(220, 180, 200, 255))
        }
        root.addView(zoomLabel)
        zoom = AirPlayPersistence.loadDesaysvClusterScale(this)
        val seek = SeekBar(this).apply { max = 220; progress = (zoom - 80).coerceIn(0, 220) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                zoom = 80 + p
                AirPlayPersistence.saveDesaysvClusterScale(this@ClusterCropActivity, zoom)
                updateZoomLabel()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        btnRow.addView(Button(this).apply {
            text = getString(R.string.cluster_crop_reset)
            setOnClickListener {
                cropView.reset()
                zoom = 100
                AirPlayPersistence.saveDesaysvClusterScale(this@ClusterCropActivity, 100)
                seek.progress = 20
                updateZoomLabel()
            }
        })
        btnRow.addView(Button(this).apply {
            text = getString(R.string.cluster_crop_save)
            setOnClickListener { finish() }
        })
        root.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })

        setContentView(root)
    }

    private fun updateZoomLabel() {
        zoomLabel.text = "%.2f×".format(zoom / 100f)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
