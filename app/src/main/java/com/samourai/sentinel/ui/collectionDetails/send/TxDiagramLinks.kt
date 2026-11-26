package com.samourai.sentinel.ui.collectionDetails.send

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.samourai.sentinel.R

/**
 * #644 slice 1: Sparrow-style connectors between the diagram's sides.
 *
 * A vertical axis down the middle; one cubic bezier per visible slot,
 * inputs fanning in from the left edge, outputs fanning out to the
 * right. Each curve ends in a size-indicator circle whose radius is
 * that slot's share of the total (Sparrow's
 * RELATIVE_SIZE_MAX_RADIUS shape, capped at 7dp) - the shape of the
 * transaction at a glance.
 *
 * Slots are data, not views: [setSlots] takes the sats of each painted
 * row, in paint order, with null for an invisible padding slot - the
 * same trick as Sparrow's InvisibleBlockTransactionHashIndex, so the
 * shorter side can be padded to the taller side's row count and both
 * columns share one row geometry. A null slot draws neither curve nor
 * circle. The screen owns row heights; this view assumes uniform
 * slots, so row i's center sits at (i + 0.5) / count of its height.
 *
 * QA #666 item 5: [setSlots]'s [highlightSlot] is the one output slot
 * - the fee branch, while the high-fee warning fires - whose curve and
 * circle paint amber instead of grey. The radius stays the size
 * encoding either way; the screen decides which slot, this view paints
 * the color it is handed.
 *
 * Pure canvas: no state besides the slot lists, one reused [Path],
 * no string resources - TalkBack reads the card's contentDescription,
 * which the screen composes from the same rows.
 */
internal class TxDiagramLinks @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var inputSlots: List<Long?> = emptyList()
    private var outputSlots: List<Long?> = emptyList()
    private var totalSats: Long = 0L

    /** QA #666 item 5: the amber slot, or -1 when none paints. */
    private var highlightSlot: Int = -1

    private val lineColor = ContextCompat.getColor(context, R.color.md_grey_500)
    private val amberColor = ContextCompat.getColor(context, R.color.md_amber_500)

    private val path = Path()

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.FILL
    }

    // QA #666 item 5: the fee branch's amber pair - same stroke, cap
    // and fill as the grey pair, warning color. Pre-allocated with
    // the rest: onDraw never allocates.
    private val highlightCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = amberColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val highlightCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = amberColor
        style = Paint.Style.FILL
    }

    /**
     * Slot sats in paint order, [null] for an invisible padding slot.
     * The fee rides the output side (Sparrow's fee is the last output
     * branch); the aggregate "N more..." row rides whichever side it
     * summarizes. [highlightSlot] is the one output slot that paints
     * amber - the fee branch while the high-fee warning fires - or -1
     * when none does. Call on the main thread; the card repaints
     * itself.
     */
    fun setSlots(
        inputs: List<Long?>,
        outputs: List<Long?>,
        totalSats: Long,
        highlightSlot: Int = -1,
    ) {
        inputSlots = inputs
        outputSlots = outputs
        this.totalSats = totalSats
        this.highlightSlot = highlightSlot
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val centerX = width * 0.5f
        val centerY = height * 0.5f

        canvas.drawLine(centerX, 0f, centerX, height.toFloat(), axisPaint)

        // Inputs: left edge in to the axis, circle at the curve's start.
        val inputCount = inputSlots.size
        inputSlots.forEachIndexed { index, sats ->
            if (sats == null) return@forEachIndexed
            val radius = circleRadius(sats)
            val y = rowCenter(index, inputCount)
            val startX = radius
            drawCurve(canvas, startX, y, centerX, centerY, highlight = false)
            if (radius > 0f) {
                canvas.drawCircle(startX, y, radius, circlePaint)
            }
        }

        // Outputs: axis out to the right edge, circle at the curve's end.
        val outputCount = outputSlots.size
        outputSlots.forEachIndexed { index, sats ->
            if (sats == null) return@forEachIndexed
            val radius = circleRadius(sats)
            val y = rowCenter(index, outputCount)
            val endX = width - radius
            // QA #666 item 5: the highlighted slot's whole branch -
            // curve and circle - goes amber together.
            val highlight = index == highlightSlot
            drawCurve(canvas, centerX, centerY, endX, y, highlight)
            if (radius > 0f) {
                canvas.drawCircle(
                    endX, y, radius,
                    if (highlight) highlightCirclePaint else circlePaint,
                )
            }
        }
    }

    /**
     * One connector, Sparrow's control points: the handles sit at 0.8
     * and 0.2 of the run, each on its own endpoint's y - the same bend
     * TransactionDiagram draws with controlX1 0.8 / controlX2 0.2.
     */
    private fun drawCurve(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        highlight: Boolean,
    ) {
        val run = endX - startX
        path.reset()
        path.moveTo(startX, startY)
        path.cubicTo(
            startX + 0.8f * run, startY,
            startX + 0.2f * run, endY,
            endX, endY,
        )
        canvas.drawPath(path, if (highlight) highlightCurvePaint else curvePaint)
    }

    /** Share of the total, Sparrow's shape: share * 6 + 1, capped at 7dp. */
    private fun circleRadius(sats: Long): Float {
        if (totalSats <= 0L || sats <= 0L) return 0f
        val raw = (sats * (MAX_RADIUS_DP - 1) / totalSats) + 1
        return dp(raw.coerceAtMost(MAX_RADIUS_DP).toFloat())
    }

    private fun rowCenter(index: Int, count: Int): Float =
        if (count == 0) 0f else height * (index + 0.5f) / count

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** Sparrow's RELATIVE_SIZE_MAX_RADIUS. */
        const val MAX_RADIUS_DP = 7L
    }
}
