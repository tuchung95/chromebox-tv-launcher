package local.chromebox.tvlauncher

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Android TV layouts are designed for a 960 x 540 dp screen, which a 1080p TV reaches at
 * 320 dpi. ChromeOS reports a much lower density, so everything would look small from the
 * sofa. This returns a configuration that makes the full display exactly 960 dp wide.
 */
object TvDensity {
    private const val DESIGN_WIDTH_DP = 960f

    fun overrideFor(context: Context): Configuration? {
        val widthPx = displayWidthPx(context) ?: return null
        val dpi = (widthPx * DisplayMetrics.DENSITY_DEFAULT / DESIGN_WIDTH_DP).roundToInt()
        if (dpi <= 0) return null
        return Configuration().apply { densityDpi = dpi }
    }

    private fun displayWidthPx(context: Context): Int? {
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return null
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            maxOf(bounds.width(), bounds.height())
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            maxOf(size.x, size.y)
        }
    }
}
