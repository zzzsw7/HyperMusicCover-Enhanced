package com.os4.musiccover

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * One app's notifications, listed by the module itself.
 *
 * The native stack can only be opened whole: its rows are MIUI's, and hiding some of them for a
 * filtered list was overridden on every relayout. This is a view of our own instead - the notes
 * the islands already hold for that app, drawn as a card (2026-11-17).
 */
internal object PkgListOverlay {

    private var layer: FrameLayout? = null

    fun show(host: ViewGroup, notes: List<LockIslands.Note>) {
        hide()
        if (notes.isEmpty()) return
        val ctx = host.context
        val d = ctx.resources.displayMetrics.density
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).roundToInt(), (14 * d).roundToInt(),
                (18 * d).roundToInt(), (14 * d).roundToInt())
            background = GradientDrawable().apply {
                cornerRadius = 28 * d
                setColor(Color.argb(238, 26, 26, 30))
            }
        }
        for (n in notes.take(MAX_ROWS)) {
            card.addView(row(ctx, n, d), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (10 * d).roundToInt()
            })
        }
        val wrap = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(90, 0, 0, 0))
            isClickable = true
            setOnClickListener { hide() }
        }
        wrap.addView(card, FrameLayout.LayoutParams(
            (300 * d).roundToInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = (200 * d).roundToInt()
        })
        host.addView(wrap, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = wrap
    }

    fun hide() {
        val v = layer ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        layer = null
    }

    fun isShown(): Boolean = layer != null

    private const val MAX_ROWS = 8

    private fun row(ctx: Context, n: LockIslands.Note, d: Float): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        n.icon?.let { icon ->
            row.addView(ImageView(ctx).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams((34 * d).roundToInt(), (34 * d).roundToInt()).apply {
                rightMargin = (12 * d).roundToInt()
            })
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = n.title
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
        })
        texts.addView(TextView(ctx).apply {
            text = n.text
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 13f
            maxLines = 2
        })
        row.addView(texts)
        return row
    }
}
