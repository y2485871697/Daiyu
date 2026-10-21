package app.daiyu.android.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import android.app.Dialog
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter

/** Uses the same spring solver for View-backed media and approval dialogs. */
internal fun Dialog.startDaiyuEnterMotion() {
    val view = window?.decorView ?: return
    window?.setWindowAnimations(0)
    if (!ValueAnimator.areAnimatorsEnabled()) return
    val metrics = view.resources.displayMetrics
    val largeScreen = metrics.widthPixels / metrics.density >= 840f && metrics.heightPixels / metrics.density >= 480f
    val animation = TargetBasedAnimation(dialogEnterSpec(largeScreen), Float.VectorConverter, 0f, 1f)
    fun applyProgress(progress: Float) {
        if (largeScreen) {
            view.scaleX = 0.8f + 0.2f * progress
            view.scaleY = view.scaleX
            view.alpha = progress
        } else view.translationY = (1f - progress) * metrics.heightPixels
    }
    val targetDim = window?.attributes?.dimAmount ?: 0f
    window?.setDimAmount(0f)
    applyProgress(0f)
    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ((animation.durationNanos + 999_999L) / 1_000_000L).coerceAtLeast(300L)
        interpolator = LinearInterpolator()
        addUpdateListener {
            applyProgress(animation.getValueFromNanos((currentPlayTime * 1_000_000L).coerceAtMost(animation.durationNanos)))
            val dim = DecelerateEasing(1.5f).transform((currentPlayTime / 300f).coerceIn(0f, 1f))
            window?.setDimAmount(targetDim * dim)
        }
    }
    val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            animator.cancel()
        }
    }
    view.addOnAttachStateChangeListener(attachListener)
    animator.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            applyProgress(1f)
            window?.setDimAmount(targetDim)
            view.removeOnAttachStateChangeListener(attachListener)
            animator.removeAllUpdateListeners()
        }
    })
    animator.start()
}
