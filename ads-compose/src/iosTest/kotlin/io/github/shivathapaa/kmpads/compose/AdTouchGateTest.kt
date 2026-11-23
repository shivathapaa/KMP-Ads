package io.github.shivathapaa.kmpads.compose

import androidx.compose.ui.geometry.Offset
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * A tap that lands on an ad while its list flings must only stop the list: the gate refuses touches
 * until the ad has been still for [AdSettleTime].
 */
@OptIn(ExperimentalForeignApi::class)
class AdTouchGateTest {
    private val time = TestTimeSource()
    private val ad = UIView()
    private val gate = AdTouchGate(ad, time).apply {
        setFrame(CGRectMake(0.0, 0.0, 320.0, 100.0))
        layoutIfNeeded()
    }

    private fun touched() = gate.hitTest(CGPointMake(160.0, 50.0), withEvent = null)

    @Test
    fun an_ad_at_rest_receives_touches() {
        gate.onPositioned(Offset(16f, 400f))
        gate.onPositioned(Offset(16f, 400f))

        assertEquals<Any?>(ad, touched())
    }

    @Test
    fun a_moving_ad_refuses_touches_until_it_has_settled() {
        gate.onPositioned(Offset(16f, 400f))
        // A scroll frame moves the slot.
        gate.onPositioned(Offset(16f, 380f))
        assertNull(touched())

        time += AdSettleTime - 1.milliseconds
        assertNull(touched())

        time += 1.milliseconds
        assertEquals<Any?>(ad, touched())
    }

    @Test
    fun the_first_placement_is_not_a_move() {
        gate.onPositioned(Offset(16f, 400f))

        assertEquals<Any?>(ad, touched())
    }

    @Test
    fun the_ad_view_fills_the_gate() {
        gate.setFrame(CGRectMake(0.0, 0.0, 390.0, 62.0))
        gate.layoutIfNeeded()

        assertEquals<Any?>(ad, gate.hitTest(CGPointMake(389.0, 61.0), withEvent = null))
    }
}
