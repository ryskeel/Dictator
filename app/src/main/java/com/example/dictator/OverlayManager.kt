package com.example.dictator

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator

class OverlayManager(
    private val context: Context,
    private val onStopRequested: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var pulseAnimator: ValueAnimator? = null

    fun show(keyboardHeightPx: Int) {
        if (overlayView != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_pill, null)
        view.setOnClickListener { onStopRequested() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = keyboardHeightPx + dpToPx(16)
        }

        windowManager.addView(view, params)
        overlayView = view

        startPulse(view.findViewById(R.id.pulseDot))
    }

    fun hide() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    private fun startPulse(dot: View) {
        pulseAnimator = ValueAnimator.ofFloat(1f, 0.3f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
