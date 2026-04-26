package com.example.scratchoff.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import com.example.scratchoff.R
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class ScratchCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private var scratchBitmap: Bitmap? = null
    private var scratchCanvas: Canvas? = null

    private var brushRadiusPx = 22f * density
    private var cornerRadiusPx = 28f * density
    private var overlayHintText = context.getString(R.string.scratch_hint)
    private var revealThreshold = 0.58f
    private var overlayAlpha = 255
    private var revealPercent = 0f
    private var revealCompleted = false
    private var isScratchEnabled = true

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var moveEvents = 0

    private var revealAnimator: ValueAnimator? = null
    private var onScratchProgressChanged: ((Float, Boolean) -> Unit)? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        isFilterBitmap = true
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(34, 255, 255, 255)
        strokeWidth = 12f * density
    }

    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD)
        textSize = 20f * density
        setShadowLayer(8f * density, 0f, 2f * density, Color.argb(80, 0, 0, 0))
    }

    private val clipPath = Path()

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    fun setBrushSizeDp(sizeDp: Float) {
        brushRadiusPx = sizeDp * density
    }

    fun setCornerRadiusDp(radiusDp: Float) {
        cornerRadiusPx = radiusDp * density
        rebuildScratchLayer(width, height)
    }

    fun setRevealThreshold(threshold: Float) {
        revealThreshold = threshold.coerceIn(0.05f, 0.95f)
    }

    fun setOverlayHintText(text: String) {
        overlayHintText = text
        drawOverlayArtwork()
        invalidate()
    }

    fun setOnScratchProgressChanged(listener: (Float, Boolean) -> Unit) {
        onScratchProgressChanged = listener
    }

    fun resetScratch() {
        revealAnimator?.cancel()
        overlayAlpha = 255
        revealPercent = 0f
        revealCompleted = false
        isScratchEnabled = true
        rebuildScratchLayer(width, height)
        onScratchProgressChanged?.invoke(0f, false)
    }

    fun revealAll() {
        if (revealCompleted) {
            return
        }

        revealCompleted = true
        isScratchEnabled = false
        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofInt(overlayAlpha, 0).apply {
            duration = 240L
            addUpdateListener {
                overlayAlpha = it.animatedValue as Int
                invalidate()
            }
            doOnEnd {
                overlayAlpha = 0
                revealPercent = 1f
                onScratchProgressChanged?.invoke(1f, true)
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildScratchLayer(w, h)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val bitmap = scratchBitmap ?: return
        if (overlayAlpha <= 0) {
            return
        }

        bitmapPaint.alpha = overlayAlpha
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return isScratchEnabled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = scratchBitmap ?: return false
        if (!isScratchEnabled || width == 0 || height == 0 || bitmap.isRecycled) {
            return false
        }

        parent?.requestDisallowInterceptTouchEvent(true)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                moveEvents = 0
                scratchAt(event.x, event.y)
                invalidateDirty(event.x, event.y, event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dirtyRect = RectF(
                    lastTouchX - brushRadiusPx,
                    lastTouchY - brushRadiusPx,
                    lastTouchX + brushRadiusPx,
                    lastTouchY + brushRadiusPx,
                )

                for (index in 0 until event.historySize) {
                    val historicalX = event.getHistoricalX(index)
                    val historicalY = event.getHistoricalY(index)
                    union(dirtyRect, scratchBetween(lastTouchX, lastTouchY, historicalX, historicalY))
                    lastTouchX = historicalX
                    lastTouchY = historicalY
                }

                union(dirtyRect, scratchBetween(lastTouchX, lastTouchY, event.x, event.y))
                lastTouchX = event.x
                lastTouchY = event.y
                invalidateDirty(dirtyRect)

                moveEvents++
                if (moveEvents % 5 == 0) {
                    dispatchProgress(forceCallback = false)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dispatchProgress(forceCallback = true)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun rebuildScratchLayer(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }

        scratchBitmap?.recycle()
        scratchBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        scratchCanvas = Canvas(scratchBitmap!!)
        drawOverlayArtwork()
        invalidate()
    }

    private fun drawOverlayArtwork() {
        val bitmap = scratchBitmap ?: return
        val canvas = scratchCanvas ?: return
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val radius = min(cornerRadiusPx, min(w, h) / 2f)
        val layerRect = RectF(0f, 0f, w, h)

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        overlayPaint.shader = LinearGradient(
            0f,
            0f,
            w,
            h,
            intArrayOf(
                Color.parseColor("#FFBFC4CB"),
                Color.parseColor("#FF9CA4AE"),
                Color.parseColor("#FFD0D5DC"),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(layerRect, radius, radius, overlayPaint)

        clipPath.reset()
        clipPath.addRoundRect(layerRect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        val stripeStep = 26f * density
        var startX = -h
        while (startX < w + h) {
            canvas.drawLine(startX, 0f, startX + h, h, stripePaint)
            startX += stripeStep
        }

        val sparkleGap = 34f * density
        var row = 0
        var y = sparkleGap * 0.5f
        while (y < h) {
            var x = if (row % 2 == 0) sparkleGap * 0.6f else sparkleGap
            while (x < w) {
                canvas.drawCircle(x, y, 2.3f * density, sparklePaint)
                x += sparkleGap * 1.6f
            }
            y += sparkleGap
            row++
        }

        canvas.restore()

        hintPaint.textSize = min(w, h) * 0.11f
        val baseline = h / 2f - (hintPaint.descent() + hintPaint.ascent()) / 2f
        canvas.drawText(overlayHintText, w / 2f, baseline, hintPaint)
    }

    private fun scratchAt(x: Float, y: Float) {
        scratchCanvas?.drawCircle(x, y, brushRadiusPx, clearPaint)
    }

    private fun scratchBetween(startX: Float, startY: Float, endX: Float, endY: Float): RectF {
        val canvas = scratchCanvas ?: return RectF()
        val dx = endX - startX
        val dy = endY - startY
        val distance = hypot(dx, dy)
        val step = max(brushRadiusPx * 0.35f, 2f)
        val stamps = max(1, ceil(distance / step).toInt())

        for (index in 0..stamps) {
            val progress = index / stamps.toFloat()
            val x = startX + dx * progress
            val y = startY + dy * progress
            canvas.drawCircle(x, y, brushRadiusPx, clearPaint)
        }

        return RectF(
            min(startX, endX) - brushRadiusPx - 4f,
            min(startY, endY) - brushRadiusPx - 4f,
            max(startX, endX) + brushRadiusPx + 4f,
            max(startY, endY) + brushRadiusPx + 4f,
        )
    }

    private fun dispatchProgress(forceCallback: Boolean) {
        val currentPercent = calculateRevealPercent()
        val shouldComplete = currentPercent >= revealThreshold
        val changedEnough = kotlin.math.abs(currentPercent - revealPercent) >= 0.01f
        revealPercent = currentPercent

        if ((changedEnough || forceCallback) && !revealCompleted) {
            onScratchProgressChanged?.invoke(currentPercent, false)
        }

        if (shouldComplete && !revealCompleted) {
            revealAll()
        }
    }

    private fun calculateRevealPercent(): Float {
        val bitmap = scratchBitmap ?: return 0f
        if (bitmap.width == 0 || bitmap.height == 0) {
            return 0f
        }

        val sampleGap = max(1, (4f * density).toInt())
        var total = 0
        var cleared = 0

        for (y in 0 until bitmap.height step sampleGap) {
            for (x in 0 until bitmap.width step sampleGap) {
                if (!isInsideRoundedRect(x + 0.5f, y + 0.5f, bitmap.width.toFloat(), bitmap.height.toFloat(), cornerRadiusPx)) {
                    continue
                }

                total++
                if (Color.alpha(bitmap.getPixel(x, y)) == 0) {
                    cleared++
                }
            }
        }

        return if (total == 0) 0f else cleared.toFloat() / total.toFloat()
    }

    private fun isInsideRoundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float): Boolean {
        val realRadius = min(radius, min(width, height) / 2f)

        if (x in realRadius..(width - realRadius)) {
            return y in 0f..height
        }

        if (y in realRadius..(height - realRadius)) {
            return x in 0f..width
        }

        val centerX = if (x < realRadius) realRadius else width - realRadius
        val centerY = if (y < realRadius) realRadius else height - realRadius
        val dx = x - centerX
        val dy = y - centerY
        return dx * dx + dy * dy <= realRadius * realRadius
    }

    private fun invalidateDirty(startX: Float, startY: Float, endX: Float, endY: Float) {
        invalidateDirty(
            RectF(
                min(startX, endX) - brushRadiusPx - 4f,
                min(startY, endY) - brushRadiusPx - 4f,
                max(startX, endX) + brushRadiusPx + 4f,
                max(startY, endY) + brushRadiusPx + 4f,
            ),
        )
    }

    private fun invalidateDirty(rect: RectF) {
        val dirty = Rect(
            rect.left.toInt().coerceAtLeast(0),
            rect.top.toInt().coerceAtLeast(0),
            rect.right.toInt().coerceAtMost(width),
            rect.bottom.toInt().coerceAtMost(height),
        )
        invalidate(dirty)
    }

    private fun union(target: RectF, source: RectF) {
        if (source.isEmpty) {
            return
        }
        target.union(source)
    }
}
