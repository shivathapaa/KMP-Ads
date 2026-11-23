package io.github.shivathapaa.kmpads.compose

import androidx.compose.ui.geometry.Offset
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIEvent
import platform.UIKit.UIView
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How long an ad must stay still before it accepts touches again. */
internal val AdSettleTime = 150.milliseconds

/**
 * Hosts an ad view and keeps touches away from it while it moves.
 *
 * Cooperative interop hands an ad view any touch that does not start a scroll, including a tap that
 * lands while the list flings: the tap stops the list and is then delivered to the ad as a click. A
 * click on a moving ad is the accidental-click pattern AdMob treats as invalid traffic. This view
 * therefore refuses hit-testing until the slot has been still for [AdSettleTime], so such a tap only
 * stops the list. At rest, touches reach the ad exactly as before.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AdTouchGate(
    private val adView: UIView,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : UIView(frame = CGRectZero.readValue()) {
    private var lastPosition: Offset = Offset.Unspecified
    private var lastMove: TimeMark? = null

    init {
        addSubview(adView)
    }

    /** Records the slot's position in the window, after every layout pass that may have moved it. */
    fun onPositioned(position: Offset) {
        if (lastPosition != Offset.Unspecified && position != lastPosition) {
            lastMove = timeSource.markNow()
        }
        lastPosition = position
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        adView.setFrame(bounds)
    }

    override fun hitTest(point: CValue<CGPoint>, withEvent: UIEvent?): UIView? {
        val moved = lastMove
        if (moved != null && moved.elapsedNow() < AdSettleTime) return null
        return super.hitTest(point, withEvent)
    }
}
