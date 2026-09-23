package com.osm.toiletmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.ContextCompat

object ToiletMarkerHelper {

    fun createMarkerBitmap(context: Context, status: ToiletStatus, isSelected: Boolean = false): Bitmap {
        val density = context.resources.displayMetrics.density

        // Normal size: 28dp (radius 14dp). 60% larger: 44.8dp (radius 22.4dp).
        val circleRadiusDp = when (status) {
            ToiletStatus.OPEN -> 22.4f // 60% larger (44.8dp diameter)
            ToiletStatus.CLOSED -> 14f // Normal size (28dp diameter)
            ToiletStatus.UNKNOWN -> 14f // Normal size (28dp diameter)
        }
        val circleRadiusPx = circleRadiusDp * density

        val labelText = when (status) {
            ToiletStatus.OPEN -> "Open"
            ToiletStatus.CLOSED -> "Closed"
            ToiletStatus.UNKNOWN -> null
        }

        val alphaInt = when (status) {
            ToiletStatus.OPEN -> 255
            ToiletStatus.CLOSED -> 160 // Half transparent
            ToiletStatus.UNKNOWN -> 255
        }

        val textSizePx = if (status == ToiletStatus.OPEN) 11.5f * density else 10f * density
        val labelMarginTopPx = 4f * density
        val shadowExtraPx = if (isSelected) 10f * density else 2f * density

        // Measure text width if label is present
        val testPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            typeface = Typeface.DEFAULT_BOLD
        }
        val textWidth = if (labelText != null) testPaint.measureText(labelText) else 0f
        val textHeightApprox = if (labelText != null) textSizePx else 0f

        // Symmetrical padding so circle center remains at (width / 2, height / 2)
        val verticalContentHalf = circleRadiusPx + (if (labelText != null) (labelMarginTopPx + textHeightApprox + 2f * density) else 0f)
        val verticalPadding = maxOf(verticalContentHalf, circleRadiusPx + shadowExtraPx) + 4f * density
        val heightPx = (verticalPadding * 2f).toInt()

        val horizontalContentHalf = maxOf(circleRadiusPx + shadowExtraPx, (textWidth / 2f) + 4f * density) + 4f * density
        val widthPx = (horizontalContentHalf * 2f).toInt()

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val centerX = widthPx / 2f
        val centerY = heightPx / 2f

        // Draw black blurred shadow if selected
        if (isSelected) {
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 0, 0, 0)
                maskFilter = BlurMaskFilter(7f * density, BlurMaskFilter.Blur.NORMAL)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(centerX, centerY, circleRadiusPx + 2.5f * density, shadowPaint)
        }

        // Draw outer background circle
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (status) {
                ToiletStatus.OPEN -> Color.parseColor("#0077C8") // OSM Blue
                ToiletStatus.CLOSED -> Color.parseColor("#5A6B7C") // Muted Slate
                ToiletStatus.UNKNOWN -> Color.parseColor("#0077C8") // OSM Blue
            }
            alpha = alphaInt
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX, centerY, circleRadiusPx, bgPaint)

        // Draw white border for contrast
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = alphaInt
            style = Paint.Style.STROKE
            strokeWidth = if (status == ToiletStatus.OPEN) 2.5f * density else 1.5f * density
        }
        canvas.drawCircle(centerX, centerY, circleRadiusPx - (borderPaint.strokeWidth / 2f), borderPaint)

        // Draw toilet icon centered inside circle
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_toilet_fab)
        if (drawable != null) {
            drawable.mutate()
            drawable.alpha = alphaInt
            drawable.setTint(Color.WHITE)

            val iconSize = (circleRadiusPx * 1.25f).toInt()
            drawable.setBounds(
                (centerX - iconSize / 2f).toInt(),
                (centerY - iconSize / 2f).toInt(),
                (centerX + iconSize / 2f).toInt(),
                (centerY + iconSize / 2f).toInt()
            )
            drawable.draw(canvas)
        }

        // Draw label text below icon in black with white halo if present
        if (labelText != null) {
            val textY = centerY + circleRadiusPx + labelMarginTopPx + textSizePx

            // White halo outline for high contrast over map tiles
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = textSizePx
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                style = Paint.Style.STROKE
                strokeWidth = 2.5f * density
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                alpha = alphaInt
            }
            canvas.drawText(labelText, centerX, textY, haloPaint)

            // Black text
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = textSizePx
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                style = Paint.Style.FILL
                alpha = alphaInt
            }
            canvas.drawText(labelText, centerX, textY, textPaint)
        }

        return bitmap
    }
}
