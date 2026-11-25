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

    private val lineColor = ContextCompat.getColor(context, R.color.md_grey_500)

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

    /**
     * Slot sats in paint order, [null] for an invisible padding slot.
     * The fee rides the output side (Sparrow's fee is the last output
     * branch); the aggregate "N more..." row rides whichever side it
     * summarizes. Call on the main thread; the card repaints itself.
     */
    fun setSlots(inputs: List<Long?>, outputs: List<Long?>, totalSats: Long) {
        inputSlots = inputs
        outputSlots = outputs
        this.totalSats = totalSats
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
            drawCurve(canvas, startX, y, centerX, centerY)
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
            drawCurve(canvas, centerX, centerY, endX, y)
            if (radius > 0f) {
                canvas.drawCircle(endX, y, radius, circlePaint)
            }
        }
    }

    /**
     * One connector, Sparrow's control points: the handles sit at 0.8
     * and 0.2 of the run, each on its own endpoint's y - the same bend
     * TransactionDiagram draws with controlX1 0.8 / controlX2 0.2.
     */
    private fun drawCurve(canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float) {
        val run = endX - startX
        path.reset()
        path.moveTo(startX, startY)
        path.cubicTo(
            startX + 0.8f * run, startY,
            startX + 0.2f * run, endY,
            endX, endY,
        )
        canvas.drawPath(path, curvePaint)
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
