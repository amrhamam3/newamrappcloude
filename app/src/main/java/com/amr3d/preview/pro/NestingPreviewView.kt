package com.amr3d.preview.pro

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class NestingPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var result: NestingResult? = null
        set(value) {
            field = value
            resetView()
            invalidate()
        }

    var showAllBoards: Boolean = true
        set(value) {
            field = value
            resetView()
            invalidate()
        }

    /** لون إطار اللوح الخارجي — مستقل تمامًا عن board.color (اللي بيوصف لون
     * خامة اللوح نفسه للتصدير، مش لون خط المعاينة). قبل كده الكود كان بيستخدم
     * board.color نفسه كلون للخط (boardPaint.color = board.color)، ولأن قيمته
     * الافتراضية في كل أنحاء المشروع تقريبًا سوداء تمامًا (0xFF0D0F14) وقريبة
     * جدًا من لون خلفية شاشة المعاينة (5,7,12)، كان الخط بيختفي بصريًا تمامًا
     * رغم إنه بيترسم فعلاً. اللون الافتراضي الجديد سماوي واضح يفرق بصريًا عن
     * برتقالي القطع (accent) وعن خلفية المعاينة الغامقة في كل الحالات. */
    var boardBorderColor: Int = Color.rgb(56, 189, 248)
        set(value) {
            field = value
            invalidate()
        }

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.rgb(70, 74, 84)
    }

    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.7f
        color = Color.rgb(255, 138, 30)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 174, 184)
        textSize = 13f
        typeface = Typeface.create(
            Typeface.DEFAULT,
            Typeface.BOLD
        )
    }

    private val boardLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 138, 30)
        textSize = 12f
        typeface = Typeface.create(
            Typeface.DEFAULT,
            Typeface.BOLD
        )
    }

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private var baseScale = 1f

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {

                    val old = scale

                    scale = (
                        scale * detector.scaleFactor
                    ).coerceIn(
                        baseScale * 0.25f,
                        baseScale * 12f
                    )

                    val fx = detector.focusX
                    val fy = detector.focusY

                    if (old > 0f) {
                        tx = fx - (fx - tx) * (scale / old)
                        ty = fy - (fy - ty) * (scale / old)
                    }

                    invalidate()

                    return true
                }
            }
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }

            // A second (or later) finger touched down. The focal point
            // ScaleGestureDetector reports jumps at this instant, so resync
            // lastX/lastY here to avoid a pan jump on the next MOVE.
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_MOVE -> {

                if (
                    !scaleDetector.isInProgress &&
                    dragging
                ) {
                    tx += event.x - lastX
                    ty += event.y - lastY

                    invalidate()
                }

                // Always resync, even while a pinch is in progress, so that
                // lifting a finger mid-pinch doesn't apply a large stale delta.
                lastX = event.x
                lastY = event.y
            }

            // A finger lifted but at least one pointer remains down. event.x/y
            // now refers to a different pointer than before, so resync to
            // avoid the view jumping on finger lift.
            MotionEvent.ACTION_POINTER_UP -> {
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
            }
        }

        return true
    }

    fun zoomIn() {
        zoomAt(
            1.25f,
            width / 2f,
            height / 2f
        )
    }

    fun zoomOut() {
        zoomAt(
            0.8f,
            width / 2f,
            height / 2f
        )
    }

    fun fitAll() {
        resetView()
    }

    private fun zoomAt(
        factor: Float,
        fx: Float,
        fy: Float
    ) {

        val old = scale

        scale = (
            scale * factor
        ).coerceIn(
            baseScale * 0.25f,
            baseScale * 12f
        )

        if (old > 0f) {
            tx = fx - (fx - tx) * (scale / old)
            ty = fy - (fy - ty) * (scale / old)
        }

        invalidate()
    }

    private fun resetView() {

        post {

            val r = result ?: return@post

            if (
                width <= 0 ||
                height <= 0
            ) {
                return@post
            }

            val boards =
                if (showAllBoards) {
                    r.boards
                } else {
                    r.boards.take(1)
                }

            if (boards.isEmpty()) {
                return@post
            }

            val gap = 70.0

            val totalW =
                boards.sumOf {
                    it.width
                } +
                gap * (boards.size - 1)

            val maxH =
                boards.maxOf {
                    it.height
                }

            if (
                totalW <= 0.0 ||
                maxH <= 0.0
            ) {
                return@post
            }

            val availW =
                width * 0.88f

            val availH =
                height * 0.82f

            baseScale =
                min(
                    availW / totalW.toFloat(),
                    availH / maxH.toFloat()
                ).coerceAtLeast(0.02f)

            scale = baseScale

            val contentW =
                totalW * scale.toDouble()

            val contentH =
                maxH * scale.toDouble()

            tx =
                (
                    (width.toDouble() - contentW) / 2.0
                ).toFloat()

            ty =
                (
                    (height.toDouble() - contentH) / 2.0 +
                    20.0
                ).toFloat()

            invalidate()
        }
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(
            w,
            h,
            oldw,
            oldh
        )

        resetView()
    }

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        canvas.drawColor(
            Color.rgb(5, 7, 12)
        )

        val r = result ?: return

        val boards =
            if (showAllBoards) {
                r.boards
            } else {
                r.boards.take(1)
            }

        if (boards.isEmpty()) {
            return
        }

        var x = 0.0

        for (board in boards) {

            canvas.save()

            canvas.translate(
                tx + x.toFloat() * scale,
                ty
            )

            canvas.scale(
                scale,
                scale
            )

            // خط أعرض شوية (3 بدل 2) + لون الإطار المستقل القابل للتغيير بدل
            // board.color القديم (شوف تعليق boardBorderColor) عشان يبان بوضوح
            // فوق خلفية المعاينة الغامقة مهما كان لون خامة اللوح نفسه.
            boardPaint.strokeWidth =
                3f / scale

            boardPaint.color =
                boardBorderColor

            piecePaint.strokeWidth =
                1.6f / scale

            val rect =
                RectF(
                    0f,
                    0f,
                    board.width.toFloat(),
                    board.height.toFloat()
                )

            canvas.drawRect(
                rect,
                boardPaint
            )

            canvas.drawText(
                "BOARD ${board.index}",
                8f,
                18f / scale,
                boardLabelPaint
            )

            for (piece in board.pieces) {
                drawPiece(
                    canvas,
                    piece
                )
            }

            canvas.restore()

            x += board.width + 70.0
        }
    }

    private fun drawPiece(
        canvas: Canvas,
        piece: NestingPiece
    ) {

        val path = Path()

        val r =
            Math.toRadians(
                piece.rotationDeg
            )

        val c =
            kotlin.math.cos(r)

        val s =
            kotlin.math.sin(r)

        piece.polygon.outer
            .forEachIndexed { i, p ->

                val px =
                    (
                        p.x * c -
                        p.y * s +
                        piece.x
                    ).toFloat()

                val py =
                    (
                        p.x * s +
                        p.y * c +
                        piece.y
                    ).toFloat()

                if (i == 0) {
                    path.moveTo(px, py)
                } else {
                    path.lineTo(px, py)
                }
            }

        path.close()

        canvas.drawPath(
            path,
            piecePaint
        )

        for (
            hole in piece.polygon.holes
        ) {

            val hp = Path()

            hole.forEachIndexed { i, p ->

                val px =
                    (
                        p.x * c -
                        p.y * s +
                        piece.x
                    ).toFloat()

                val py =
                    (
                        p.x * s +
                        p.y * c +
                        piece.y
                    ).toFloat()

                if (i == 0) {
                    hp.moveTo(px, py)
                } else {
                    hp.lineTo(px, py)
                }
            }

            hp.close()

            canvas.drawPath(
                hp,
                piecePaint
            )
        }

        val b = RectF()

        path.computeBounds(
            b,
            true
        )

        val old =
            labelPaint.textSize

        labelPaint.textSize =
            10f / scale

        canvas.drawText(
            piece.index.toString(),
            b.centerX(),
            b.centerY(),
            labelPaint
        )

        labelPaint.textSize = old
    }
}
