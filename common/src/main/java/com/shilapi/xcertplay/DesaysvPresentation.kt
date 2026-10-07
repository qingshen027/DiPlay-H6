package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup

/**
 * Feeds the iPhone's instrument-cluster stream (111) onto the vendor "PresentationScreen"
 * virtual display (800x480, owned by com.presentation.mypresentation). The stock
 * GWRtpService continuously encodes that display and RTP-pushes it to the LCD
 * instrument cluster — this is the real fly-screen path on this head unit.
 *
 * The display carries FLAG_OWN_CONTENT_ONLY, so DisplayManager hides it from other
 * apps (hasAccess filter). We locate its displayId through DisplayManagerGlobal and
 * build the Display object reflectively when DisplayManager refuses to hand it out.
 * Android 8.1 has no hidden-API enforcement, so reflection is safe here.
 */
object DesaysvPresentation {
    private const val TAG = DesaysvCluster.TAG
    private const val DISPLAY_NAME = "PresentationScreen"

    fun findDisplay(context: Context): Display? = runCatching {
        val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val dmg = dmgClass.getMethod("getInstance").invoke(null)
        val ids = dmgClass.getMethod("getDisplayIds").invoke(dmg) as IntArray
        val getDisplayInfo = dmgClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
        for (id in ids) {
            val info = getDisplayInfo.invoke(dmg, id) ?: continue
            val name = runCatching { info.javaClass.getField("name").get(info) as? String }.getOrNull()
            if (name != DISPLAY_NAME) continue
            Log.i(TAG, "found $DISPLAY_NAME displayId=$id")
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.getDisplay(id)?.let { return@runCatching it }
            // Access-filtered for our uid: construct the Display object directly.
            val displayInfoClass = Class.forName("android.view.DisplayInfo")
            val ctor = Display::class.java.getDeclaredConstructor(
                dmgClass, Int::class.javaPrimitiveType, displayInfoClass,
                android.content.res.Resources::class.java,
            )
            ctor.isAccessible = true
            return@runCatching ctor.newInstance(dmg, id, info, context.resources) as Display
        }
        Log.w(TAG, "$DISPLAY_NAME not among displays ${ids.toList()}")
        null
    }.onFailure { Log.w(TAG, "findDisplay failed", it) }.getOrNull()

    /**
     * TextureView on the vendor display; the ALT stream renders into it. The view is
     * full-frame; setCrop maps a crop rectangle of the source stream onto the panel
     * with a matrix (DesaysvCrop), filling it completely — no black frame ever — with
     * an optional screen margin for physically masked panel edges.
     */
    class ClusterPresentation(
        context: Context,
        display: Display,
        private val onSurface: (Surface?) -> Unit,
    ) : Presentation(context, display) {

        var activeSurface: Surface? = null
            private set

        private val textureView = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    val surface = Surface(texture)
                    activeSurface = surface
                    Log.i(TAG, "cluster presentation surface ready ${width}x${height}")
                    onSurface(surface)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    onSurface(null)
                    activeSurface?.release()
                    activeSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }

        private val container = android.widget.FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(textureView, android.widget.FrameLayout.LayoutParams(-1, -1))
        }

        /**
         * Shows the crop rectangle of the source stream scaled to fill the panel
         * (inset by margin). Never letterboxes: the content always covers the window.
         */
        fun setCrop(
            cropX: Float, cropY: Float, cropW: Float, cropH: Float,
            margin: Int, streamW: Int, streamH: Int, stretch: Boolean,
        ) {
            textureView.setTransform(
                DesaysvCrop.matrix(
                    cropX, cropY, cropW, cropH, streamW, streamH,
                    DesaysvCluster.WIDTH, DesaysvCluster.HEIGHT, margin, stretch,
                ),
            )
            Log.i(TAG, "cluster crop $cropX,$cropY ${cropW}x${cropH} margin=$margin stream=${streamW}x${streamH} stretch=$stretch")
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(container, ViewGroup.LayoutParams(-1, -1))
        }
    }
}
