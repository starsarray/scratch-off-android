package com.example.scratchoff.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.example.scratchoff.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class ArcadeScratchGameView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class TicketMode {
        TRIPLE_ROW,
        DOUBLE_PAIR,
        MINI_LINE,
        ORCHARD,
        QUICK_BONUS,
    }

    private enum class DragMode {
        NONE,
        SCRATCH,
        LEFT_LIST,
        RIGHT_LIST,
    }

    private data class SymbolSpec(
        val id: String,
        val label: String,
        val color: Int,
        val payout: Int,
        val baseWeight: Float,
    )

    private data class TicketDefinition(
        val titleRes: Int,
        val subtitleRes: Int,
        val cost: Int,
        val unlockWealth: Int,
        val frameColor: Int,
        val accentColor: Int,
        val baseWinChance: Float,
        val aspectRatio: Float,
        val sizeScale: Float,
        val mode: TicketMode,
        val ruleTextRes: Int,
        val symbols: List<SymbolSpec>,
    )

    private data class TicketProgress(
        var level: Int = 0,
        var exp: Int = 0,
    )

    private data class UpgradeSpec(
        val titleRes: Int,
        val effectTextRes: Int,
        val baseCost: Int,
        var level: Int,
    )

    private data class ScratchSession(
        val slots: List<String>,
        val winSymbol: String?,
        val payout: Int,
        val won: Boolean,
        val resultText: String,
        val bonusActive: Boolean = false,
    )

    private val density = resources.displayMetrics.density
    private val random = Random(System.currentTimeMillis())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var uiScale = 1f
    private var currentTicketIndex = 3
    private var currentSession = ScratchSession(
        slots = listOf("apple", "leaf", "basket", "gold", "leaf", "apple"),
        winSymbol = null,
        payout = 0,
        won = false,
        resultText = text(R.string.game_scratch_auto_settle),
    )

    private var cash = 56_706
    private var careerWealth = 82_000
    private val targetWealth = 300_000
    private var scratchProgress = 0f
    private var ticketSettled = false
    private var lastResultText = text(R.string.game_scratch_auto_settle)
    private var isScratching = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragMode = DragMode.NONE
    private var downY = 0f
    private var dragLastY = 0f
    private var listDragged = false
    private var leftScrollOffset = 0f
    private var rightScrollOffset = 0f
    private var leftMaxScroll = 0f
    private var rightMaxScroll = 0f

    private val sectionTitleSize get() = sp(14f)
    private val itemTitleSize get() = sp(12f)
    private val bodyTextSize get() = sp(9f)
    private val detailTextSize get() = sp(8.2f)

    private val tickets = listOf(
        TicketDefinition(
            titleRes = R.string.ticket_daily_jobs_title,
            subtitleRes = R.string.ticket_daily_jobs_subtitle,
            cost = 1,
            unlockWealth = 0,
            frameColor = Color.rgb(242, 222, 188),
            accentColor = Color.rgb(119, 182, 80),
            baseWinChance = 0.24f,
            aspectRatio = 1.82f,
            sizeScale = 0.92f,
            mode = TicketMode.TRIPLE_ROW,
            ruleTextRes = R.string.ticket_daily_jobs_rule,
            symbols = listOf(
                SymbolSpec("leaf", "L", Color.rgb(120, 190, 88), 8, 45f),
                SymbolSpec("coin", "C", Color.rgb(241, 180, 57), 18, 30f),
                SymbolSpec("bag", "B", Color.rgb(70, 193, 180), 36, 18f),
                SymbolSpec("gem", "G", Color.rgb(86, 186, 244), 88, 7f),
            ),
        ),
        TicketDefinition(
            titleRes = R.string.ticket_double_match_title,
            subtitleRes = R.string.ticket_double_match_subtitle,
            cost = 10,
            unlockWealth = 150,
            frameColor = Color.rgb(255, 196, 67),
            accentColor = Color.rgb(21, 112, 168),
            baseWinChance = 0.28f,
            aspectRatio = 1.70f,
            sizeScale = 0.96f,
            mode = TicketMode.DOUBLE_PAIR,
            ruleTextRes = R.string.ticket_double_match_rule,
            symbols = listOf(
                SymbolSpec("star", "S", Color.rgb(38, 112, 176), 60, 38f),
                SymbolSpec("coin", "C", Color.rgb(235, 173, 44), 120, 30f),
                SymbolSpec("safe", "F", Color.rgb(64, 192, 146), 260, 20f),
                SymbolSpec("gem", "G", Color.rgb(96, 206, 255), 700, 12f),
            ),
        ),
        TicketDefinition(
            titleRes = R.string.ticket_mini_line_title,
            subtitleRes = R.string.ticket_mini_line_subtitle,
            cost = 100,
            unlockWealth = 1_200,
            frameColor = Color.rgb(187, 218, 239),
            accentColor = Color.rgb(45, 77, 132),
            baseWinChance = 0.31f,
            aspectRatio = 2.05f,
            sizeScale = 0.90f,
            mode = TicketMode.MINI_LINE,
            ruleTextRes = R.string.ticket_mini_line_rule,
            symbols = listOf(
                SymbolSpec("star", "S", Color.rgb(57, 107, 167), 400, 36f),
                SymbolSpec("ticket", "T", Color.rgb(232, 166, 45), 800, 29f),
                SymbolSpec("cash", "$", Color.rgb(54, 187, 129), 1_800, 22f),
                SymbolSpec("gem", "G", Color.rgb(104, 212, 255), 4_800, 13f),
            ),
        ),
        TicketDefinition(
            titleRes = R.string.ticket_orchard_title,
            subtitleRes = R.string.ticket_orchard_subtitle,
            cost = 2_000,
            unlockWealth = 12_000,
            frameColor = Color.rgb(122, 185, 85),
            accentColor = Color.rgb(129, 72, 42),
            baseWinChance = 0.34f,
            aspectRatio = 1.72f,
            sizeScale = 1.00f,
            mode = TicketMode.ORCHARD,
            ruleTextRes = R.string.ticket_orchard_rule,
            symbols = listOf(
                SymbolSpec("apple", "A", Color.rgb(200, 79, 56), 6_000, 38f),
                SymbolSpec("leaf", "L", Color.rgb(98, 181, 84), 12_000, 28f),
                SymbolSpec("basket", "B", Color.rgb(210, 132, 60), 22_000, 20f),
                SymbolSpec("gold", "G", Color.rgb(234, 191, 64), 50_000, 14f),
            ),
        ),
        TicketDefinition(
            titleRes = R.string.ticket_quick_bonus_title,
            subtitleRes = R.string.ticket_quick_bonus_subtitle,
            cost = 10_000,
            unlockWealth = 60_000,
            frameColor = Color.rgb(238, 127, 24),
            accentColor = Color.rgb(245, 214, 75),
            baseWinChance = 0.38f,
            aspectRatio = 1.80f,
            sizeScale = 1.05f,
            mode = TicketMode.QUICK_BONUS,
            ruleTextRes = R.string.ticket_quick_bonus_rule,
            symbols = listOf(
                SymbolSpec("coin", "C", Color.rgb(227, 121, 48), 5_000, 34f),
                SymbolSpec("sun", "Y", Color.rgb(242, 192, 42), 10_000, 28f),
                SymbolSpec("cash", "$", Color.rgb(58, 196, 132), 25_000, 22f),
                SymbolSpec("star", "S", Color.rgb(69, 208, 164), 50_000, 16f),
            ),
        ),
    )

    private val ticketProgress = MutableList(tickets.size) { TicketProgress() }.apply {
        this[1] = TicketProgress(level = 1, exp = 1)
        this[2] = TicketProgress(level = 2, exp = 2)
        this[3] = TicketProgress(level = 3, exp = 3)
    }

    private val upgrades = mutableListOf(
        UpgradeSpec(R.string.upgrade_luck_title, R.string.upgrade_luck_effect, 8_000, 2),
        UpgradeSpec(R.string.upgrade_range_title, R.string.upgrade_range_effect, 2_500, 1),
        UpgradeSpec(R.string.upgrade_coin_title, R.string.upgrade_coin_effect, 5_000, 1),
    )

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        isFilterBitmap = true
    }
    private val scratchPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val topHudRect = RectF()
    private val leftPanelRect = RectF()
    private val centerBoardRect = RectF()
    private val ticketRect = RectF()
    private val scratchRect = RectF()
    private val bonusRect = RectF()
    private val infoRect = RectF()
    private val rightPanelRect = RectF()
    private val statusRect = RectF()
    private val actionRect = RectF()
    private val leftListRect = RectF()
    private val rightListRect = RectF()

    private val ticketRows = mutableListOf<Pair<Int, RectF>>()
    private val upgradeRows = mutableListOf<Pair<Int, RectF>>()
    private val slotRects = mutableListOf<RectF>()

    private var scratchBitmap: Bitmap? = null
    private var scratchCanvas: Canvas? = null

    init {
        isFocusable = true
        prepareTicket(resetCost = false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        uiScale = (min(w / 1800f, h / 900f) * 0.84f).coerceIn(0.50f, 0.92f)
        layoutScene(w.toFloat(), h.toFloat())
        rebuildScratchLayer()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)
        drawTopHud(canvas)
        drawLeftPanel(canvas)
        drawCenterBoard(canvas)
        drawTicket(canvas)
        drawTicketActionArea(canvas)
        drawInfoPanel(canvas)
        drawRightPanel(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                downY = y
                dragLastY = y
                listDragged = false
                dragMode = DragMode.NONE

                if (actionRect.contains(x, y)) {
                    onActionPressed()
                    return true
                }

                if (scratchRect.contains(x, y) && !ticketSettled && isTicketUnlocked(currentTicketIndex)) {
                    dragMode = DragMode.SCRATCH
                    isScratching = true
                    lastTouchX = x
                    lastTouchY = y
                    scratchAt(x, y)
                    invalidate()
                    return true
                }

                if (leftListRect.contains(x, y)) {
                    dragMode = DragMode.LEFT_LIST
                    return true
                }

                if (rightListRect.contains(x, y)) {
                    dragMode = DragMode.RIGHT_LIST
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (dragMode) {
                    DragMode.SCRATCH -> {
                        if (isScratching) {
                            for (i in 0 until event.historySize) {
                                val hx = event.getHistoricalX(i)
                                val hy = event.getHistoricalY(i)
                                scratchBetween(lastTouchX, lastTouchY, hx, hy)
                                lastTouchX = hx
                                lastTouchY = hy
                            }
                            scratchBetween(lastTouchX, lastTouchY, event.x, event.y)
                            lastTouchX = event.x
                            lastTouchY = event.y
                            updateScratchProgress()
                            invalidate()
                        }
                        return true
                    }

                    DragMode.LEFT_LIST -> {
                        if (leftMaxScroll > 0f) {
                            if (listDragged || abs(event.y - downY) > touchSlop) {
                                listDragged = true
                                leftScrollOffset = (leftScrollOffset + (dragLastY - event.y)).coerceIn(0f, leftMaxScroll)
                                dragLastY = event.y
                                invalidate()
                            } else {
                                dragLastY = event.y
                            }
                        }
                        return true
                    }

                    DragMode.RIGHT_LIST -> {
                        if (rightMaxScroll > 0f) {
                            if (listDragged || abs(event.y - downY) > touchSlop) {
                                listDragged = true
                                rightScrollOffset = (rightScrollOffset + (dragLastY - event.y)).coerceIn(0f, rightMaxScroll)
                                dragLastY = event.y
                                invalidate()
                            } else {
                                dragLastY = event.y
                            }
                        }
                        return true
                    }

                    DragMode.NONE -> Unit
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (dragMode) {
                    DragMode.SCRATCH -> {
                        if (isScratching) {
                            isScratching = false
                            updateScratchProgress()
                            invalidate()
                        }
                    }

                    DragMode.LEFT_LIST -> {
                        if (!listDragged && event.actionMasked == MotionEvent.ACTION_UP) {
                            handleTicketSelection(event.x, event.y)
                        }
                    }

                    DragMode.RIGHT_LIST -> {
                        if (!listDragged && event.actionMasked == MotionEvent.ACTION_UP) {
                            handleUpgradeSelection(event.x, event.y)
                        }
                    }

                    DragMode.NONE -> Unit
                }
                dragMode = DragMode.NONE
                isScratching = false
                listDragged = false
                return true
            }
        }
        return true
    }

    private fun handleTicketSelection(x: Float, y: Float) {
        ticketRows.firstOrNull { (_, rect) -> rect.contains(x, y) }?.let { (index, _) ->
            if (isTicketUnlocked(index)) {
                currentTicketIndex = index
                layoutScene(width.toFloat(), height.toFloat())
                prepareTicket(resetCost = false)
            } else {
                lastResultText = text(R.string.game_need_total_wealth, formatMoney(tickets[index].unlockWealth))
                invalidate()
            }
        }
    }

    private fun handleUpgradeSelection(x: Float, y: Float) {
        upgradeRows.firstOrNull { (_, rect) -> rect.contains(x, y) }?.let { (index, _) ->
            buyUpgrade(index)
        }
    }

    private fun onActionPressed() {
        if (ticketSettled) {
            prepareTicket(resetCost = true)
            return
        }

        if (scratchProgress >= 0.45f) {
            revealAllOverlay()
            settleTicket()
        } else {
            lastResultText = text(R.string.game_continue_to_threshold, formatPercent(revealThreshold() * 100f))
            invalidate()
        }
    }

    private fun layoutScene(w: Float, h: Float) {
        val margin = dp(16f)
        val leftWidth = (w * 0.22f).coerceIn(dp(210f), dp(300f))
        val rightWidth = (w * 0.19f).coerceIn(dp(188f), dp(238f))
        val panelBottom = h - dp(14f)

        topHudRect.set(dp(12f), dp(12f), leftWidth + dp(4f), dp(106f))
        leftPanelRect.set(dp(12f), dp(112f), leftWidth + dp(4f), panelBottom)
        centerBoardRect.set(leftWidth + margin, dp(12f), w - rightWidth - margin, panelBottom)
        rightPanelRect.set(w - rightWidth, dp(12f), w - dp(12f), panelBottom)

        val boardInset = dp(14f)
        val boardInner = RectF(
            centerBoardRect.left + boardInset,
            centerBoardRect.top + boardInset,
            centerBoardRect.right - boardInset,
            centerBoardRect.bottom - boardInset,
        )
        val infoWidth = (boardInner.width() * 0.255f).coerceIn(dp(196f), dp(248f))

        infoRect.set(
            boardInner.right - infoWidth,
            boardInner.top + dp(2f),
            boardInner.right,
            boardInner.bottom - dp(2f),
        )

        val ticketZone = RectF(boardInner.left, boardInner.top, infoRect.left - dp(8f), boardInner.bottom)
        val currentTicket = tickets[currentTicketIndex]
        val ticketCx = ticketZone.left + ticketZone.width() * 0.44f
        val preferredTicketWidth = min(
            ticketZone.width() * 1.02f,
            ticketZone.height() * currentTicket.aspectRatio * 0.88f * currentTicket.sizeScale,
        )
        val maxWidthByZone = (ticketZone.right - dp(4f) - ticketCx) * 2f
        val ticketWidth = min(preferredTicketWidth, maxWidthByZone)
            .coerceAtLeast(dp(300f))
            .coerceAtMost(ticketZone.width() - dp(14f))
        val ticketHeight = ticketWidth / currentTicket.aspectRatio
        val ticketCy = ticketZone.top + ticketZone.height() * 0.50f
        ticketRect.set(
            ticketCx - ticketWidth / 2f,
            ticketCy - ticketHeight / 2f,
            ticketCx + ticketWidth / 2f,
            ticketCy + ticketHeight / 2f,
        )
        val maxTicketRight = ticketZone.right - dp(4f)
        if (ticketRect.right > maxTicketRight) {
            ticketRect.offset(maxTicketRight - ticketRect.right, 0f)
        }
        val minTicketLeft = boardInner.left + dp(6f)
        if (ticketRect.left < minTicketLeft) {
            ticketRect.offset(minTicketLeft - ticketRect.left, 0f)
        }

        if (currentTicket.mode == TicketMode.QUICK_BONUS) {
            scratchRect.set(
                ticketRect.left + ticketWidth * 0.08f,
                ticketRect.top + ticketHeight * 0.34f,
                ticketRect.left + ticketWidth * 0.70f,
                ticketRect.bottom - ticketHeight * 0.14f,
            )
            bonusRect.set(
                ticketRect.right - ticketWidth * 0.19f,
                ticketRect.top + ticketHeight * 0.42f,
                ticketRect.right - ticketWidth * 0.07f,
                ticketRect.bottom - ticketHeight * 0.11f,
            )
        } else {
            scratchRect.set(
                ticketRect.left + ticketWidth * 0.07f,
                ticketRect.top + ticketHeight * 0.33f,
                ticketRect.right - ticketWidth * 0.07f,
                ticketRect.bottom - ticketHeight * 0.14f,
            )
            bonusRect.setEmpty()
        }

        val chipGap = dp(8f)
        val chipHeight = (ticketHeight * 0.12f).coerceIn(dp(38f), dp(46f))
        val chipTop = min(ticketRect.bottom + dp(10f), ticketZone.bottom - chipHeight)
        val chipRight = min(ticketRect.right - dp(6f), ticketZone.right - dp(6f))
        val controlWidth = min(ticketWidth * 0.48f, ticketZone.width() - dp(18f)).coerceIn(dp(208f), dp(276f))
        val chipLeft = (chipRight - controlWidth).coerceAtLeast(ticketZone.left + dp(8f))
        val actualWidth = chipRight - chipLeft
        val statusWidth = (actualWidth - chipGap) * 0.38f
        statusRect.set(chipLeft, chipTop, chipLeft + statusWidth, chipTop + chipHeight)
        actionRect.set(statusRect.right + chipGap, chipTop, chipRight, chipTop + chipHeight)

        val tabHeight = dp(34f)
        leftListRect.set(
            leftPanelRect.left + dp(6f),
            leftPanelRect.top + tabHeight + dp(10f),
            leftPanelRect.right - dp(6f),
            leftPanelRect.bottom - dp(10f),
        )
        rightListRect.set(
            rightPanelRect.left + dp(9f),
            rightPanelRect.top + dp(56f),
            rightPanelRect.right - dp(9f),
            rightPanelRect.bottom - dp(8f),
        )

        updateSlotRects()
    }

    private fun updateSlotRects() {
        slotRects.clear()
        val mode = tickets[currentTicketIndex].mode
        val width = scratchRect.width()
        val height = scratchRect.height()

        fun add(left: Float, top: Float, right: Float, bottom: Float) {
            slotRects += RectF(
                scratchRect.left + width * left,
                scratchRect.top + height * top,
                scratchRect.left + width * right,
                scratchRect.top + height * bottom,
            )
        }

        when (mode) {
            TicketMode.TRIPLE_ROW -> {
                add(0.05f, 0.22f, 0.29f, 0.82f)
                add(0.38f, 0.22f, 0.62f, 0.82f)
                add(0.71f, 0.22f, 0.95f, 0.82f)
            }

            TicketMode.DOUBLE_PAIR -> {
                add(0.10f, 0.12f, 0.42f, 0.44f)
                add(0.58f, 0.12f, 0.90f, 0.44f)
                add(0.10f, 0.56f, 0.42f, 0.88f)
                add(0.58f, 0.56f, 0.90f, 0.88f)
            }

            TicketMode.MINI_LINE -> {
                add(0.02f, 0.26f, 0.18f, 0.78f)
                add(0.22f, 0.26f, 0.38f, 0.78f)
                add(0.42f, 0.26f, 0.58f, 0.78f)
                add(0.62f, 0.26f, 0.78f, 0.78f)
                add(0.82f, 0.26f, 0.98f, 0.78f)
            }

            TicketMode.ORCHARD -> {
                add(0.07f, 0.08f, 0.30f, 0.40f)
                add(0.39f, 0.08f, 0.62f, 0.40f)
                add(0.71f, 0.08f, 0.94f, 0.40f)
                add(0.07f, 0.56f, 0.30f, 0.88f)
                add(0.39f, 0.56f, 0.62f, 0.88f)
                add(0.71f, 0.56f, 0.94f, 0.88f)
            }

            TicketMode.QUICK_BONUS -> {
                add(0.10f, 0.16f, 0.42f, 0.48f)
                add(0.52f, 0.16f, 0.84f, 0.48f)
                add(0.10f, 0.54f, 0.42f, 0.86f)
                add(0.52f, 0.54f, 0.84f, 0.86f)
            }
        }
    }

    private fun drawBackground(canvas: Canvas) {
        bgPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Color.rgb(18, 20, 30),
            Color.rgb(34, 38, 55),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        bgPaint.shader = null

        fillPaint.color = Color.argb(24, 255, 255, 255)
        val step = dp(20f)
        var x = 0f
        while (x < width) {
            canvas.drawRect(x, 0f, x + dp(1f), height.toFloat(), fillPaint)
            x += step
        }
    }

    private fun drawTopHud(canvas: Canvas) {
        drawPanel(canvas, topHudRect, Color.rgb(29, 39, 59), Color.rgb(91, 172, 226))
        drawText(canvas, text(R.string.game_hud_title), topHudRect.left + dp(18f), topHudRect.top + dp(33f), sectionTitleSize, Color.WHITE, true)
        drawText(canvas, text(R.string.game_level_format, playerLevel()), topHudRect.right - dp(78f), topHudRect.top + dp(33f), sectionTitleSize, Color.rgb(255, 210, 82), true)

        val barRect = RectF(
            topHudRect.left + dp(16f),
            topHudRect.bottom - dp(28f),
            topHudRect.right - dp(16f),
            topHudRect.bottom - dp(10f),
        )
        drawText(canvas, text(R.string.game_cash_format, formatMoney(cash)), topHudRect.left + dp(18f), barRect.top - dp(8f), bodyTextSize, Color.rgb(195, 204, 214), false)
        drawPanel(canvas, barRect, Color.rgb(12, 18, 30), Color.rgb(60, 98, 136))
        val progress = (careerWealth.toFloat() / targetWealth.toFloat()).coerceIn(0f, 1f)
        fillPaint.shader = LinearGradient(
            barRect.left,
            barRect.top,
            barRect.right,
            barRect.bottom,
            Color.rgb(255, 184, 78),
            Color.rgb(95, 215, 170),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(
            RectF(barRect.left, barRect.top, barRect.left + barRect.width() * progress, barRect.bottom),
            dp(12f),
            dp(12f),
            fillPaint,
        )
        fillPaint.shader = null
        drawText(canvas, "${formatMoney(careerWealth)} / ${formatMoney(targetWealth)}", barRect.left + dp(44f), barRect.top + dp(15f), itemTitleSize, Color.WHITE, true)

        val helpRect = RectF(width - dp(76f), dp(16f), width - dp(16f), dp(76f))
        drawPanel(canvas, helpRect, Color.rgb(34, 53, 78), Color.rgb(103, 184, 232))
        drawCenteredText(canvas, "?", helpRect, sp(28f), Color.WHITE, true)
    }

    private fun drawLeftPanel(canvas: Canvas) {
        drawPanel(canvas, leftPanelRect, Color.rgb(20, 30, 48), Color.rgb(57, 110, 164))

        val tabHeight = dp(34f)
        val tab1 = RectF(leftPanelRect.left, leftPanelRect.top, leftPanelRect.left + leftPanelRect.width() * 0.48f, leftPanelRect.top + tabHeight)
        val tab2 = RectF(tab1.right + dp(6f), leftPanelRect.top, leftPanelRect.right, leftPanelRect.top + tabHeight)
        drawPanel(canvas, tab1, Color.rgb(49, 145, 182), Color.rgb(103, 209, 239))
        drawPanel(canvas, tab2, Color.rgb(44, 53, 67), Color.rgb(78, 90, 108))
        drawCenteredText(canvas, text(R.string.game_tab_album), tab1, sectionTitleSize, Color.WHITE, true)
        drawCenteredText(canvas, text(R.string.game_tab_rules), tab2, sectionTitleSize, Color.WHITE, true)

        ticketRows.clear()
        val contentRect = leftListRect
        clipped(canvas, contentRect) {
            val gap = dp(10f)
            val rowHeight = dp(74f)
            val totalHeight = tickets.size * rowHeight + (tickets.size - 1) * gap
            leftMaxScroll = max(0f, totalHeight - contentRect.height())
            leftScrollOffset = leftScrollOffset.coerceIn(0f, leftMaxScroll)

            var y = contentRect.top - leftScrollOffset
            tickets.forEachIndexed { index, ticket ->
                val row = RectF(contentRect.left, y, contentRect.right, y + rowHeight)
                ticketRows += index to row
                val unlocked = isTicketUnlocked(index)
                val selected = index == currentTicketIndex
                val rowColor = when {
                    selected -> Color.rgb(47, 81, 135)
                    unlocked -> Color.rgb(34, 49, 74)
                    else -> Color.rgb(22, 30, 44)
                }
                drawPanel(canvas, row, rowColor, Color.rgb(74, 121, 184))
                drawMiniIcon(canvas, row.left + dp(30f), row.centerY(), ticket.frameColor, ticket.accentColor, unlocked)
                drawText(canvas, text(ticket.titleRes), row.left + dp(60f), row.top + row.height() * 0.30f, itemTitleSize, Color.WHITE, true)
                drawText(canvas, text(ticket.subtitleRes), row.left + dp(60f), row.top + row.height() * 0.50f, bodyTextSize, Color.rgb(180, 197, 216), false)

                if (unlocked) {
                    val progress = ticketLevelProgress(index)
                    drawText(canvas, "S${formatMoney(ticket.cost)}", row.left + dp(60f), row.top + row.height() * 0.70f, bodyTextSize, if (ticket.cost >= 2_000) Color.rgb(234, 91, 74) else Color.WHITE, false)
                    drawText(canvas, text(R.string.game_level_format, ticketProgress[index].level), row.left + dp(116f), row.top + row.height() * 0.70f, bodyTextSize, Color.rgb(194, 201, 212), false)
                    val barRect = RectF(row.left + dp(60f), row.bottom - dp(12f), row.right - dp(12f), row.bottom - dp(6f))
                    fillPaint.color = Color.rgb(31, 74, 36)
                    canvas.drawRoundRect(barRect, dp(6f), dp(6f), fillPaint)
                    fillPaint.color = Color.rgb(73, 214, 52)
                    canvas.drawRoundRect(RectF(barRect.left, barRect.top, barRect.left + barRect.width() * progress, barRect.bottom), dp(6f), dp(6f), fillPaint)
                } else {
                    drawWrappedText(
                        canvas,
                        text(R.string.game_need_total_wealth, formatMoney(ticket.unlockWealth)),
                        row.left + dp(60f),
                        row.top + row.height() * 0.60f,
                        row.width() - dp(72f),
                        dp(12f),
                        bodyTextSize,
                        Color.rgb(160, 170, 184),
                        false,
                        2,
                    )
                }
                y += rowHeight + gap
            }
        }
        drawScrollBar(canvas, contentRect, leftMaxScroll, leftScrollOffset)
    }

    private fun drawCenterBoard(canvas: Canvas) {
        fillPaint.shader = LinearGradient(
            centerBoardRect.left,
            centerBoardRect.top,
            centerBoardRect.right,
            centerBoardRect.bottom,
            Color.rgb(87, 66, 52),
            Color.rgb(59, 45, 37),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(centerBoardRect, dp(24f), dp(24f), fillPaint)
        fillPaint.shader = null
        strokePaint.color = Color.rgb(160, 124, 88)
        strokePaint.strokeWidth = dp(2f)
        canvas.drawRoundRect(centerBoardRect, dp(24f), dp(24f), strokePaint)

        fillPaint.color = Color.argb(68, 163, 122, 84)
        canvas.drawRoundRect(
            RectF(
                centerBoardRect.left + dp(20f),
                centerBoardRect.top + dp(14f),
                centerBoardRect.right - dp(20f),
                centerBoardRect.bottom - dp(20f),
            ),
            dp(20f),
            dp(20f),
            fillPaint,
        )
    }

    private fun drawTicket(canvas: Canvas) {
        val ticket = tickets[currentTicketIndex]
        val titleSize = sectionTitleSize
        val subtitleSize = bodyTextSize
        canvas.save()
        canvas.rotate(-2.2f, ticketRect.centerX(), ticketRect.centerY())

        fillPaint.color = Color.argb(90, 0, 0, 0)
        canvas.drawRoundRect(RectF(ticketRect.left + dp(10f), ticketRect.top + dp(12f), ticketRect.right + dp(10f), ticketRect.bottom + dp(12f)), dp(20f), dp(20f), fillPaint)
        fillPaint.shader = LinearGradient(ticketRect.left, ticketRect.top, ticketRect.right, ticketRect.bottom, ticket.frameColor, darken(ticket.frameColor, 0.82f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(ticketRect, dp(20f), dp(20f), fillPaint)
        fillPaint.shader = null
        drawDither(canvas, ticketRect, Color.argb(20, 255, 255, 255), dp(10f))

        drawText(canvas, text(R.string.game_ticket_header), ticketRect.left + ticketRect.width() * 0.10f, ticketRect.top + ticketRect.height() * 0.20f, titleSize, Color.rgb(61, 46, 34), true)
        drawText(canvas, text(ticket.subtitleRes), ticketRect.left + ticketRect.width() * 0.10f, ticketRect.top + ticketRect.height() * 0.28f, subtitleSize, Color.rgb(82, 64, 50), false)
        if (ticket.mode == TicketMode.QUICK_BONUS) {
            drawText(canvas, text(R.string.game_bonus_slot), ticketRect.right - ticketRect.width() * 0.25f, ticketRect.top + ticketRect.height() * 0.33f, itemTitleSize, ticket.accentColor, true)
        }

        fillPaint.color = Color.argb(68, 18, 18, 18)
        canvas.drawRoundRect(scratchRect, dp(10f), dp(10f), fillPaint)
        drawScratchContent(canvas)
        scratchBitmap?.let { canvas.drawBitmap(it, scratchRect.left, scratchRect.top, overlayPaint) }

        if (ticket.mode == TicketMode.QUICK_BONUS) {
            fillPaint.color = if (currentSession.bonusActive) Color.rgb(247, 199, 75) else Color.argb(90, 68, 58, 48)
            canvas.drawRoundRect(bonusRect, dp(12f), dp(12f), fillPaint)
            drawStar(canvas, bonusRect.centerX(), bonusRect.centerY(), min(bonusRect.width(), bonusRect.height()) * 0.24f, if (currentSession.bonusActive) Color.rgb(255, 240, 130) else ticket.accentColor)
        }

        canvas.restore()
    }

    private fun drawScratchContent(canvas: Canvas) {
        val ticket = tickets[currentTicketIndex]
        slotRects.forEachIndexed { index, rect ->
            fillPaint.color = Color.rgb(205, 219, 225)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), fillPaint)
            fillPaint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, Color.argb(35, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), fillPaint)
            fillPaint.shader = null

            if (index < currentSession.slots.size) {
                val symbol = ticket.symbols.first { it.id == currentSession.slots[index] }
                drawSymbolToken(canvas, symbol, rect.centerX(), rect.centerY(), min(rect.width(), rect.height()) * 0.28f)
            }
        }
    }

    private fun drawTicketActionArea(canvas: Canvas) {
        val ticket = tickets[currentTicketIndex]
        drawPanel(canvas, statusRect, Color.argb(224, 40, 45, 56), Color.rgb(105, 149, 196))
        drawPanel(
            canvas,
            actionRect,
            if (ticketSettled) Color.rgb(58, 156, 108) else Color.rgb(49, 59, 74),
            if (ticketSettled) Color.rgb(110, 233, 171) else Color.rgb(89, 112, 140),
        )

        drawCenteredTextFit(
            canvas,
            text(R.string.game_cost_format, formatMoney(ticket.cost)),
            statusRect.insetCopy(dp(8f), dp(6f)),
            itemTitleSize,
            bodyTextSize,
            Color.rgb(255, 224, 120),
            true,
        )

        val actionLabel = when {
            ticketSettled -> text(R.string.game_action_next)
            scratchProgress >= 0.45f -> text(R.string.game_action_settle)
            else -> text(R.string.game_action_continue)
        }
        drawCenteredTextFit(canvas, actionLabel, actionRect.insetCopy(dp(10f), dp(6f)), itemTitleSize, bodyTextSize, Color.WHITE, true)
    }

    private fun drawInfoPanel(canvas: Canvas) {
        val ticket = tickets[currentTicketIndex]
        val progress = ticketProgress[currentTicketIndex]
        val weights = normalizedWeights(ticket)
        val nextLevel = nextTicketLevelExp(progress.level)

        drawPanel(canvas, infoRect, Color.argb(224, 28, 37, 53), Color.rgb(76, 144, 189), gloss = false)
        val contentRect = infoRect.insetCopy(dp(10f), dp(10f))
        clipped(canvas, contentRect) {
            val symbolHeaderRect = RectF(contentRect.left, contentRect.top + dp(80f), contentRect.left + contentRect.width() * 0.36f, contentRect.top + dp(96f))
            val oddsHeaderRect = RectF(contentRect.left + contentRect.width() * 0.38f, contentRect.top + dp(80f), contentRect.left + contentRect.width() * 0.63f, contentRect.top + dp(96f))
            val payoutHeaderRect = RectF(contentRect.left + contentRect.width() * 0.64f, contentRect.top + dp(80f), contentRect.right, contentRect.top + dp(96f))

            drawText(canvas, text(ticket.titleRes), contentRect.left, contentRect.top + dp(17f), sectionTitleSize, Color.WHITE, true)
            drawText(canvas, text(ticket.subtitleRes), contentRect.left, contentRect.top + dp(31f), bodyTextSize, Color.rgb(176, 201, 220), false)
            drawWrappedText(canvas, text(ticket.ruleTextRes), contentRect.left, contentRect.top + dp(45f), contentRect.width(), dp(11f), bodyTextSize, Color.rgb(214, 223, 233), false, 2)

            drawText(canvas, text(R.string.game_card_level_format, progress.level), contentRect.left, contentRect.top + dp(69f), itemTitleSize, Color.rgb(255, 220, 128), true)
            drawText(canvas, text(R.string.game_exp_format, progress.exp, nextLevel), contentRect.left + contentRect.width() * 0.44f, contentRect.top + dp(69f), bodyTextSize, Color.rgb(196, 203, 214), false)

            drawCenteredTextFit(canvas, text(R.string.game_symbol_header), symbolHeaderRect, itemTitleSize, bodyTextSize, Color.rgb(152, 214, 235), true)
            drawCenteredTextFit(canvas, text(R.string.game_odds_header), oddsHeaderRect, itemTitleSize, bodyTextSize, Color.rgb(152, 214, 235), true)
            drawCenteredTextFit(canvas, text(R.string.game_amount_header), payoutHeaderRect, itemTitleSize, bodyTextSize, Color.rgb(152, 214, 235), true)

            val tableTop = contentRect.top + dp(100f)
            val gap = dp(6f)
            val rowHeight = ((contentRect.bottom - tableTop - gap * (ticket.symbols.size - 1)) / ticket.symbols.size).coerceIn(dp(30f), dp(42f))
            var y = tableTop
            ticket.symbols.forEachIndexed { index, symbol ->
                val row = RectF(contentRect.left, y, contentRect.right, y + rowHeight)
                val oddsRect = RectF(row.left + row.width() * 0.39f, row.top, row.left + row.width() * 0.62f, row.bottom)
                val payoutRect = RectF(row.left + row.width() * 0.62f, row.top, row.right - dp(4f), row.bottom)
                fillPaint.color = Color.argb(34, 255, 255, 255)
                canvas.drawRoundRect(row, dp(10f), dp(10f), fillPaint)
                drawSymbolToken(canvas, symbol, row.left + dp(13f), row.centerY(), dp(6.8f))
                drawText(canvas, symbol.label, row.left + dp(25f), row.centerY() + dp(3f), bodyTextSize, Color.WHITE, true)
                drawCenteredTextFit(canvas, "${formatPercent(weights[index])}%", oddsRect, bodyTextSize, detailTextSize, Color.WHITE, false)
                drawCenteredTextFit(canvas, formatMoney(symbol.payout), payoutRect, bodyTextSize, detailTextSize, Color.WHITE, false)
                y += rowHeight + gap
            }
        }
    }

    private fun drawRightPanel(canvas: Canvas) {
        drawPanel(canvas, rightPanelRect, Color.rgb(21, 54, 72), Color.rgb(73, 172, 205), gloss = false)
        drawCenteredText(canvas, text(R.string.game_upgrade_title), RectF(rightPanelRect.left, rightPanelRect.top + dp(6f), rightPanelRect.right, rightPanelRect.top + dp(40f)), sectionTitleSize, Color.WHITE, true)

        upgradeRows.clear()
        val contentRect = rightListRect
        clipped(canvas, contentRect) {
            val gap = dp(8f)
            val rowHeight = dp(72f)
            val totalHeight = upgrades.size * rowHeight + (upgrades.size - 1) * gap
            rightMaxScroll = max(0f, totalHeight - contentRect.height())
            rightScrollOffset = rightScrollOffset.coerceIn(0f, rightMaxScroll)

            var y = contentRect.top - rightScrollOffset
            upgrades.forEachIndexed { index, upgrade ->
                val row = RectF(contentRect.left, y, contentRect.right, y + rowHeight)
                val levelRect = RectF(row.right - dp(46f), row.bottom - dp(22f), row.right - dp(10f), row.bottom - dp(8f))
                upgradeRows += index to row
                drawPanel(canvas, row, Color.rgb(28, 76, 96), Color.rgb(82, 178, 205))
                drawUpgradeIcon(canvas, index, row.left + dp(21f), row.centerY())
                drawText(canvas, text(upgrade.titleRes), row.left + dp(42f), row.top + dp(17f), itemTitleSize, Color.WHITE, true)
                drawWrappedText(canvas, text(upgrade.effectTextRes), row.left + dp(42f), row.top + dp(31f), row.width() - dp(88f), dp(9f), detailTextSize, Color.rgb(194, 208, 217), false, 2)
                drawText(canvas, text(R.string.game_upgrade_cost_format, formatMoney(upgradeCost(index))), row.left + dp(42f), row.bottom - dp(11f), bodyTextSize, Color.rgb(236, 91, 71), false)
                fillPaint.color = Color.argb(58, 255, 255, 255)
                canvas.drawRoundRect(levelRect, dp(8f), dp(8f), fillPaint)
                drawCenteredTextFit(canvas, text(R.string.game_level_format, upgrade.level), levelRect, bodyTextSize, detailTextSize, Color.rgb(220, 232, 239), true)
                y += rowHeight + gap
            }
        }
        drawScrollBar(canvas, contentRect, rightMaxScroll, rightScrollOffset)
    }

    private fun prepareTicket(resetCost: Boolean) {
        val ticket = tickets[currentTicketIndex]
        if (!isTicketUnlocked(currentTicketIndex)) {
            lastResultText = text(R.string.game_need_total_wealth, formatMoney(ticket.unlockWealth))
            invalidate()
            return
        }
        if (resetCost) {
            if (cash < ticket.cost) {
                lastResultText = text(R.string.game_not_enough_cash_ticket)
                invalidate()
                return
            }
            cash -= ticket.cost
        }

        currentSession = generateSession(ticket, ticketProgress[currentTicketIndex], upgrades[0].level)
        scratchProgress = 0f
        ticketSettled = false
        lastResultText = text(R.string.game_scratch_auto_settle)
        rebuildScratchLayer()
        invalidate()
    }

    private fun buyUpgrade(index: Int) {
        val cost = upgradeCost(index)
        if (cash < cost) {
            lastResultText = text(R.string.game_not_enough_cash_upgrade)
            invalidate()
            return
        }
        cash -= cost
        upgrades[index].level += 1
        rebuildScratchLayer()
        invalidate()
    }

    private fun settleTicket() {
        if (ticketSettled) return
        ticketSettled = true

        val progress = ticketProgress[currentTicketIndex]
        progress.exp += 1
        while (progress.exp >= nextTicketLevelExp(progress.level)) {
            progress.exp -= nextTicketLevelExp(progress.level)
            progress.level += 1
        }

        if (currentSession.won) {
            cash += currentSession.payout
            careerWealth += currentSession.payout
            lastResultText = currentSession.resultText
        } else {
            lastResultText = currentSession.resultText
        }

        revealAllOverlay()
        invalidate()
    }

    private fun generateSession(ticket: TicketDefinition, progress: TicketProgress, luckLevel: Int): ScratchSession {
        val winChance = (ticket.baseWinChance + progress.level * 0.015f + luckLevel * 0.02f).coerceAtMost(0.78f)
        val willWin = random.nextFloat() < winChance
        val boosted = boostedWeights(ticket, progress.level, luckLevel)
        val chosen = ticket.symbols[weightedIndex(boosted)].id

        val slots = if (willWin) generateWinningSlots(ticket, chosen) else generateLosingSlots(ticket)
        val resultSymbol = findWinningSymbol(ticket.mode, slots)
        val bonusActive = ticket.mode == TicketMode.QUICK_BONUS &&
            resultSymbol != null &&
            random.nextFloat() < (0.16f + luckLevel * 0.03f).coerceAtMost(0.42f)

        return if (resultSymbol != null) {
            val symbol = ticket.symbols.first { it.id == resultSymbol }
            val matchCount = slots.count { it == resultSymbol }
            var payout = symbol.payout
            if (ticket.mode == TicketMode.ORCHARD && matchCount >= 4) payout = (payout * 1.6f).toInt()
            if (bonusActive) payout *= 2
            val detail = if (bonusActive) {
                text(R.string.game_result_win_bonus, formatMoney(payout), symbol.label)
            } else {
                text(R.string.game_result_win, formatMoney(payout), symbol.label)
            }
            ScratchSession(slots, resultSymbol, payout, true, detail, bonusActive)
        } else {
            ScratchSession(slots, null, 0, false, text(R.string.game_result_no_win))
        }
    }

    private fun generateWinningSlots(ticket: TicketDefinition, symbol: String): List<String> {
        val randomSymbol = { ticket.symbols[random.nextInt(ticket.symbols.size)].id }
        return when (ticket.mode) {
            TicketMode.TRIPLE_ROW -> List(3) { symbol }
            TicketMode.DOUBLE_PAIR -> {
                val result = MutableList(4) { randomSymbol() }
                val start = if (random.nextBoolean()) 0 else 2
                result[start] = symbol
                result[start + 1] = symbol
                result
            }

            TicketMode.MINI_LINE -> {
                val result = MutableList(5) { randomSymbol() }
                val start = random.nextInt(0, 3)
                result[start] = symbol
                result[start + 1] = symbol
                result[start + 2] = symbol
                result
            }

            TicketMode.ORCHARD -> {
                val result = MutableList(6) { randomSymbol() }
                val count = if (random.nextFloat() < 0.28f) 4 else 3
                repeat(count) { index -> result[index] = symbol }
                result.shuffled(random)
            }

            TicketMode.QUICK_BONUS -> {
                val result = MutableList(4) { randomSymbol() }
                val indices = listOf(0, 1, 2, 3).shuffled(random).take(3)
                indices.forEach { result[it] = symbol }
                result
            }
        }
    }

    private fun generateLosingSlots(ticket: TicketDefinition): List<String> {
        val ids = ticket.symbols.map { it.id }
        repeat(120) {
            val candidate = List(slotCount(ticket.mode)) { ids[random.nextInt(ids.size)] }
            if (findWinningSymbol(ticket.mode, candidate) == null) return candidate
        }
        return when (ticket.mode) {
            TicketMode.TRIPLE_ROW -> listOf(ids[0], ids[1], ids[2])
            TicketMode.DOUBLE_PAIR -> listOf(ids[0], ids[1], ids[1], ids[2])
            TicketMode.MINI_LINE -> listOf(ids[0], ids[1], ids[2], ids[0], ids[1])
            TicketMode.ORCHARD -> listOf(ids[0], ids[1], ids[2], ids[0], ids[1], ids[3])
            TicketMode.QUICK_BONUS -> listOf(ids[0], ids[1], ids[2], ids[3])
        }
    }

    private fun findWinningSymbol(mode: TicketMode, slots: List<String>): String? {
        return when (mode) {
            TicketMode.TRIPLE_ROW -> slots.firstOrNull()?.takeIf { slots.all { other -> other == it } }
            TicketMode.DOUBLE_PAIR -> when {
                slots[0] == slots[1] -> slots[0]
                slots[2] == slots[3] -> slots[2]
                else -> null
            }

            TicketMode.MINI_LINE -> {
                (0..2).firstNotNullOfOrNull { start ->
                    val symbol = slots[start]
                    symbol.takeIf { slots[start + 1] == symbol && slots[start + 2] == symbol }
                }
            }

            TicketMode.ORCHARD,
            TicketMode.QUICK_BONUS,
            -> slots.groupingBy { it }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.key
        }
    }

    private fun boostedWeights(ticket: TicketDefinition, ticketLevel: Int, luckLevel: Int): List<Float> {
        val boost = 1f + ticketLevel * 0.06f + luckLevel * 0.09f
        return ticket.symbols.mapIndexed { index, symbol ->
            val rarityBoost = 1f + index * 0.22f * boost
            symbol.baseWeight * rarityBoost
        }
    }

    private fun normalizedWeights(ticket: TicketDefinition): List<Float> {
        val weights = boostedWeights(ticket, ticketProgress[currentTicketIndex].level, upgrades[0].level)
        val total = weights.sum().takeIf { it > 0f } ?: 1f
        return weights.map { it / total * 100f }
    }

    private fun weightedIndex(weights: List<Float>): Int {
        val total = weights.sum().takeIf { it > 0f } ?: return 0
        var value = random.nextFloat() * total
        weights.forEachIndexed { index, weight ->
            value -= weight
            if (value <= 0f) return index
        }
        return weights.lastIndex
    }

    private fun rebuildScratchLayer() {
        if (scratchRect.width() <= 0f || scratchRect.height() <= 0f) return
        scratchBitmap?.recycle()
        val bitmap = Bitmap.createBitmap(
            scratchRect.width().toInt().coerceAtLeast(1),
            scratchRect.height().toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        scratchBitmap = bitmap
        scratchCanvas = Canvas(bitmap)
        drawScratchOverlay()
    }

    private fun drawScratchOverlay() {
        val bitmap = scratchBitmap ?: return
        val canvas = scratchCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val layerRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        fillPaint.color = Color.rgb(160, 168, 176)
        canvas.drawRoundRect(layerRect, dp(10f), dp(10f), fillPaint)
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            Color.rgb(212, 218, 224),
            Color.rgb(116, 124, 132),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(layerRect, dp(10f), dp(10f), fillPaint)
        fillPaint.shader = null
        fillPaint.color = Color.argb(210, 236, 240, 242)
        var x = -bitmap.height.toFloat()
        while (x < bitmap.width + bitmap.height) {
            canvas.drawLine(x, 0f, x + bitmap.height, bitmap.height.toFloat(), fillPaint)
            x += dp(18f)
        }

        fillPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            bitmap.height * 0.42f,
            Color.argb(150, 255, 255, 255),
            Color.argb(18, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(
            RectF(bitmap.width * 0.03f, bitmap.height * 0.04f, bitmap.width * 0.97f, bitmap.height * 0.44f),
            dp(9f),
            dp(9f),
            fillPaint,
        )
        fillPaint.shader = null

        val overlayRect = RectF(
            bitmap.width * 0.18f,
            bitmap.height * 0.18f,
            bitmap.width * 0.82f,
            bitmap.height * 0.82f,
        )
        val overlaySize = min(sp(18f), min(bitmap.width * 0.14f, bitmap.height * 0.32f))
        drawCenteredTextFit(canvas, text(R.string.game_scratch_here), overlayRect, overlaySize, max(sp(9f), overlaySize * 0.58f), Color.WHITE, true)
    }

    private fun scratchAt(x: Float, y: Float) {
        val radius = scratchRadius()
        scratchCanvas?.drawCircle(x - scratchRect.left, y - scratchRect.top, radius, scratchPaint)
    }

    private fun scratchBetween(startX: Float, startY: Float, endX: Float, endY: Float) {
        val radius = scratchRadius()
        val forceMultiplier = 1f + upgrades[2].level * 0.18f
        val distance = hypot(endX - startX, endY - startY)
        val step = max(radius * 0.34f / forceMultiplier, 2f)
        val stamps = max(1, ceil(distance / step).toInt())
        for (i in 0..stamps) {
            val t = i / stamps.toFloat()
            scratchAt(startX + (endX - startX) * t, startY + (endY - startY) * t)
        }
    }

    private fun updateScratchProgress() {
        val bitmap = scratchBitmap ?: return
        val gap = max(1, dp(4f).toInt())
        var total = 0
        var cleared = 0
        for (y in 0 until bitmap.height step gap) {
            for (x in 0 until bitmap.width step gap) {
                total++
                if (Color.alpha(bitmap.getPixel(x, y)) == 0) cleared++
            }
        }
        scratchProgress = if (total == 0) 0f else cleared.toFloat() / total.toFloat()
        if (scratchProgress >= revealThreshold()) settleTicket()
    }

    private fun revealAllOverlay() {
        scratchBitmap?.eraseColor(Color.TRANSPARENT)
        scratchProgress = 1f
    }

    private fun drawPanel(canvas: Canvas, rect: RectF, fill: Int, stroke: Int, gloss: Boolean = true) {
        fillPaint.color = Color.argb(72, 0, 0, 0)
        canvas.drawRoundRect(RectF(rect.left + dp(4f), rect.top + dp(6f), rect.right + dp(4f), rect.bottom + dp(6f)), dp(18f), dp(18f), fillPaint)
        fillPaint.color = fill
        canvas.drawRoundRect(rect, dp(18f), dp(18f), fillPaint)
        strokePaint.color = stroke
        strokePaint.strokeWidth = dp(1.6f)
        canvas.drawRoundRect(rect, dp(18f), dp(18f), strokePaint)
        if (gloss) {
            fillPaint.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom, Color.argb(55, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(rect.left + dp(2f), rect.top + dp(2f), rect.right - dp(2f), rect.centerY()), dp(18f), dp(18f), fillPaint)
            fillPaint.shader = null
        }
    }

    private fun text(resId: Int): String = resources.getString(resId)

    private fun text(resId: Int, vararg args: Any): String = resources.getString(resId, *args)

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.typeface = Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
        textPaint.setShadowLayer(dp(2f), dp(1f), dp(1f), Color.argb(140, 0, 0, 0))
        canvas.drawText(text, x, y, textPaint)
        textPaint.clearShadowLayer()
    }

    private fun drawCenteredText(canvas: Canvas, text: String, rect: RectF, size: Float, color: Int, bold: Boolean) {
        prepareTextPaint(size, color, bold)
        val x = rect.centerX() - textPaint.measureText(text) / 2f
        val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, x, y, textPaint)
        textPaint.clearShadowLayer()
    }

    private fun drawCenteredTextFit(canvas: Canvas, text: String, rect: RectF, maxSize: Float, minSize: Float, color: Int, bold: Boolean) {
        val step = density * uiScale * 0.5f
        var size = maxSize
        prepareTextPaint(size, color, bold)
        while (size > minSize && textPaint.measureText(text) > rect.width()) {
            size = (size - step).coerceAtLeast(minSize)
            prepareTextPaint(size, color, bold)
        }
        val x = rect.centerX() - textPaint.measureText(text) / 2f
        val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, x, y, textPaint)
        textPaint.clearShadowLayer()
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        maxWidth: Float,
        lineHeight: Float,
        size: Float,
        color: Int,
        bold: Boolean,
        maxLines: Int,
    ): Float {
        prepareTextPaint(size, color, bold)
        val lines = breakIntoLines(text, maxWidth, maxLines)
        var y = top
        lines.forEach { line ->
            canvas.drawText(line, left, y, textPaint)
            y += lineHeight
        }
        textPaint.clearShadowLayer()
        return lines.size * lineHeight
    }

    private fun drawScrollBar(canvas: Canvas, viewport: RectF, maxScroll: Float, offset: Float) {
        if (maxScroll <= 0f) return
        val track = RectF(
            viewport.right - dp(5f),
            viewport.top + dp(6f),
            viewport.right - dp(2f),
            viewport.bottom - dp(6f),
        )
        fillPaint.color = Color.argb(60, 255, 255, 255)
        canvas.drawRoundRect(track, dp(3f), dp(3f), fillPaint)

        val thumbHeight = (viewport.height() * (viewport.height() / (viewport.height() + maxScroll))).coerceAtLeast(dp(36f))
        val travel = track.height() - thumbHeight
        val progress = if (maxScroll == 0f) 0f else offset / maxScroll
        val thumbTop = track.top + travel * progress
        val thumb = RectF(track.left, thumbTop, track.right, thumbTop + thumbHeight)
        fillPaint.color = Color.argb(190, 123, 209, 239)
        canvas.drawRoundRect(thumb, dp(3f), dp(3f), fillPaint)
    }

    private fun prepareTextPaint(size: Float, color: Int, bold: Boolean) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.typeface = Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
        textPaint.setShadowLayer(dp(2f), dp(1f), dp(1f), Color.argb(140, 0, 0, 0))
    }

    private fun breakIntoLines(text: String, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length && lines.size < maxLines) {
            var count = textPaint.breakText(text, start, text.length, true, maxWidth, null)
            if (count <= 0) break
            var end = start + count
            if (end < text.length && text[end] != ' ' && text[end - 1] != ' ') {
                val lastSpace = text.lastIndexOf(' ', end - 1)
                if (lastSpace > start) end = lastSpace
            }
            var line = text.substring(start, end).trim()
            start = end
            while (start < text.length && text[start] == ' ') start++
            if (lines.size == maxLines - 1 && start < text.length) {
                line = ellipsize(line, maxWidth)
                start = text.length
            }
            if (line.isNotEmpty()) lines += line
        }
        return if (lines.isEmpty()) listOf(ellipsize(text, maxWidth)) else lines
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && textPaint.measureText(text.substring(0, end) + "...") > maxWidth) {
            end--
        }
        return if (end <= 0) "..." else text.substring(0, end) + "..."
    }

    private fun drawDither(canvas: Canvas, rect: RectF, color: Int, gap: Float) {
        fillPaint.color = color
        var y = rect.top + gap
        var row = 0
        while (y < rect.bottom) {
            var x = rect.left + if (row % 2 == 0) gap else gap * 2f
            while (x < rect.right) {
                canvas.drawRect(x, y, x + dp(2f), y + dp(2f), fillPaint)
                x += gap * 2f
            }
            y += gap
            row++
        }
    }

    private fun drawMiniIcon(canvas: Canvas, cx: Float, cy: Float, color: Int, accent: Int, unlocked: Boolean) {
        fillPaint.color = if (unlocked) color else Color.rgb(38, 42, 50)
        canvas.drawRoundRect(RectF(cx - dp(18f), cy - dp(16f), cx + dp(18f), cy + dp(16f)), dp(6f), dp(6f), fillPaint)
        if (unlocked) {
            drawStar(canvas, cx, cy, dp(10f), accent)
        } else {
            strokePaint.color = Color.rgb(135, 140, 148)
            strokePaint.strokeWidth = dp(2.4f)
            canvas.drawRect(cx - dp(8f), cy - dp(2f), cx + dp(8f), cy + dp(10f), strokePaint)
        }
    }

    private fun drawUpgradeIcon(canvas: Canvas, index: Int, cx: Float, cy: Float) {
        when (index) {
            0 -> {
                val r = dp(6.8f)
                fillPaint.color = Color.rgb(74, 198, 67)
                canvas.drawCircle(cx - dp(5.4f), cy - dp(5.4f), r, fillPaint)
                canvas.drawCircle(cx + dp(5.4f), cy - dp(5.4f), r, fillPaint)
                canvas.drawCircle(cx - dp(5.4f), cy + dp(5.4f), r, fillPaint)
                canvas.drawCircle(cx + dp(5.4f), cy + dp(5.4f), r, fillPaint)
            }

            1 -> {
                strokePaint.color = Color.WHITE
                strokePaint.strokeWidth = dp(2.2f)
                canvas.drawRect(cx - dp(11f), cy - dp(11f), cx + dp(11f), cy + dp(11f), strokePaint)
                drawText(canvas, "R", cx - dp(4.2f), cy + dp(5.2f), sp(10.5f), Color.WHITE, true)
            }

            else -> {
                fillPaint.color = Color.rgb(228, 118, 44)
                canvas.drawCircle(cx, cy, dp(11.5f), fillPaint)
                drawText(canvas, "C", cx - dp(4.2f), cy + dp(5.2f), sp(10.5f), Color.rgb(42, 34, 24), true)
            }
        }
    }

    private fun drawSymbolToken(canvas: Canvas, symbol: SymbolSpec, cx: Float, cy: Float, radius: Float) {
        fillPaint.color = symbol.color
        when (symbol.id) {
            "star" -> drawStar(canvas, cx, cy, radius, symbol.color)
            "gem" -> {
                val path = Path().apply {
                    moveTo(cx, cy - radius)
                    lineTo(cx + radius, cy)
                    lineTo(cx, cy + radius)
                    lineTo(cx - radius, cy)
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }

            "apple" -> {
                canvas.drawCircle(cx - radius * 0.30f, cy, radius * 0.72f, fillPaint)
                canvas.drawCircle(cx + radius * 0.30f, cy, radius * 0.72f, fillPaint)
                strokePaint.color = Color.rgb(69, 99, 41)
                strokePaint.strokeWidth = dp(2f)
                canvas.drawLine(cx, cy - radius * 1.1f, cx + radius * 0.08f, cy - radius * 0.46f, strokePaint)
            }

            "leaf" -> {
                val path = Path().apply {
                    moveTo(cx, cy - radius)
                    quadTo(cx + radius, cy - radius * 0.25f, cx, cy + radius)
                    quadTo(cx - radius, cy - radius * 0.25f, cx, cy - radius)
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }

            "basket" -> {
                canvas.drawRoundRect(RectF(cx - radius, cy - radius * 0.25f, cx + radius, cy + radius), radius * 0.22f, radius * 0.22f, fillPaint)
                strokePaint.color = darken(symbol.color, 0.7f)
                strokePaint.strokeWidth = dp(2f)
                canvas.drawArc(RectF(cx - radius * 0.75f, cy - radius, cx + radius * 0.75f, cy + radius * 0.45f), 200f, 140f, false, strokePaint)
            }

            else -> {
                canvas.drawCircle(cx, cy, radius, fillPaint)
                drawText(canvas, symbol.label, cx - radius * 0.35f, cy + radius * 0.34f, radius * 1.08f, Color.rgb(34, 35, 38), true)
            }
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val path = Path()
        for (i in 0 until 10) {
            val angle = Math.toRadians((i * 36 - 90).toDouble())
            val pointRadius = if (i % 2 == 0) radius else radius * 0.42f
            val x = cx + kotlin.math.cos(angle).toFloat() * pointRadius
            val y = cy + kotlin.math.sin(angle).toFloat() * pointRadius
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        fillPaint.color = color
        canvas.drawPath(path, fillPaint)
    }

    private fun clipped(canvas: Canvas, rect: RectF, block: () -> Unit) {
        canvas.save()
        canvas.clipRect(rect)
        block()
        canvas.restore()
    }

    private fun RectF.insetCopy(dx: Float, dy: Float): RectF = RectF(left + dx, top + dy, right - dx, bottom - dy)

    private fun isTicketUnlocked(index: Int): Boolean = careerWealth >= tickets[index].unlockWealth

    private fun ticketLevelProgress(index: Int): Float {
        val progress = ticketProgress[index]
        return progress.exp.toFloat() / nextTicketLevelExp(progress.level).toFloat()
    }

    private fun nextTicketLevelExp(level: Int): Int = 3 + level * 2

    private fun slotCount(mode: TicketMode): Int = when (mode) {
        TicketMode.TRIPLE_ROW -> 3
        TicketMode.DOUBLE_PAIR -> 4
        TicketMode.MINI_LINE -> 5
        TicketMode.ORCHARD -> 6
        TicketMode.QUICK_BONUS -> 4
    }

    private fun scratchRadius(): Float = dp(14f + upgrades[1].level * 4.5f)

    private fun revealThreshold(): Float = (0.60f - upgrades[2].level * 0.045f).coerceAtLeast(0.36f)

    private fun upgradeCost(index: Int): Int {
        val level = upgrades[index].level
        return (upgrades[index].baseCost * 1.55.pow(level.toDouble())).toInt()
    }

    private fun playerLevel(): Int {
        val ticketLevels = ticketProgress.sumOf { it.level }
        val upgradeLevels = upgrades.sumOf { it.level }
        return 1 + ticketLevels + upgradeLevels + careerWealth / 20_000
    }

    private fun formatMoney(value: Int): String = "%,d".format(value).replace(",", " ")

    private fun formatPercent(value: Float): Int = value.roundToInt()

    private fun darken(color: Int, factor: Float): Int {
        return Color.rgb(
            (Color.red(color) * factor).toInt(),
            (Color.green(color) * factor).toInt(),
            (Color.blue(color) * factor).toInt(),
        )
    }

    private fun dp(value: Float): Float = value * density * uiScale

    private fun sp(value: Float): Float = value * density * uiScale
}
