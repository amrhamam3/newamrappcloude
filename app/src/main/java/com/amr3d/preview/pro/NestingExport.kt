package com.amr3d.preview.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * تصدير حقيقي لنتيجة الترصيص لصيغتين بس، كل واحدة لغرض مختلف تمامًا:
 *
 * - DXF: ملف متجهي دقيق (بالمليمتر الحقيقي) بيتفتح مباشرة في برامج التحكم
 *   بماكينات الليزر/الراوتر/البلازما (نفس صيغة القراءة المستخدمة في DXFParser.kt
 *   بالظبط: أزواج Group Code/Value + LWPOLYLINE)، عشان يتقطع فورًا من غير أي
 *   تحويل وسيط.
 * - PDF: صفحة واحدة بسيطة للعرض على الموبايل (مقاس A4 عرضي)، فيها كل الألواح
 *   مصغّرة بنفس شكلها الحقيقي زي ما ظاهر في المعاينة، عشان العميل يقدر يشوف
 *   نتيجة الرص من غير ما يحتاج برنامج CAD.
 *
 * الاتنين بيستخدموا بالظبط نفس معادلة تحويل الإحداثيات المستخدمة في NestingEngine
 * (transformed) وفي NestingPreviewView (drawPiece)، عشان الملف المُصدَّر يطابق
 * الشكل المعروض على الشاشة 100%.
 */
object NestingExport {

    private fun transform(points: List<NestingPoint>, rotationDeg: Double, tx: Double, ty: Double): List<NestingPoint> {
        val r = Math.toRadians(rotationDeg)
        val c = cos(r); val s = sin(r)
        return points.map { NestingPoint(it.x * c - it.y * s + tx, it.x * s + it.y * c + ty) }
    }

    // ====================== DXF ======================

    /** بتبني ملف DXF ASCII كامل: إطار كل لوح على طبقة BOARD_OUTLINE، وكل قطعة
     * (شكلها الخارجي + أي فتحات داخلية) على طبقة PARTS_CUT — بنفس تنسيق DXF
     * الأساسي (HEADER/TABLES/ENTITIES) اللي DXFParser.kt في المشروع قادر يقرأه. */
    fun buildDxf(result: NestingResult): String {
        val sb = StringBuilder()
        sb.append("0\nSECTION\n2\nHEADER\n0\nENDSEC\n")
        sb.append("0\nSECTION\n2\nTABLES\n0\nENDSEC\n")
        sb.append("0\nSECTION\n2\nENTITIES\n")

        var boardOffsetX = 0.0
        for (board in result.boards) {
            // إطار اللوح (مستطيل مغلق)
            sb.append("0\nLWPOLYLINE\n8\nBOARD_OUTLINE\n90\n4\n70\n1\n")
            val bx = boardOffsetX
            appendVertex(sb, bx, 0.0)
            appendVertex(sb, bx + board.width, 0.0)
            appendVertex(sb, bx + board.width, board.height)
            appendVertex(sb, bx, board.height)

            for (piece in board.pieces) {
                val outer = transform(piece.polygon.outer, piece.rotationDeg, piece.x + bx, piece.y)
                if (outer.size >= 2) {
                    sb.append("0\nLWPOLYLINE\n8\nPARTS_CUT\n90\n${outer.size}\n70\n1\n")
                    outer.forEach { appendVertex(sb, it.x, it.y) }
                }
                for (hole in piece.polygon.holes) {
                    val h = transform(hole, piece.rotationDeg, piece.x + bx, piece.y)
                    if (h.size >= 2) {
                        sb.append("0\nLWPOLYLINE\n8\nPARTS_CUT\n90\n${h.size}\n70\n1\n")
                        h.forEach { appendVertex(sb, it.x, it.y) }
                    }
                }
            }
            boardOffsetX += board.width + 100.0 // فاصل 100مم بين كل لوح والتاني في نفس الملف
        }

        sb.append("0\nENDSEC\n0\nEOF\n")
        return sb.toString()
    }

    private fun appendVertex(sb: StringBuilder, x: Double, y: Double) {
        sb.append("10\n").append(fmt(x)).append('\n')
        sb.append("20\n").append(fmt(y)).append('\n')
    }

    private fun fmt(v: Double): String = "%.4f".format(v)

    /** بتكتب ملف DXF فعلي على القرص وترجع مساره. */
    fun saveDxfFile(context: Context, result: NestingResult): File {
        val dir = File(context.getExternalFilesDir(null), "Nesting")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "Nesting_${System.currentTimeMillis()}.dxf")
        FileOutputStream(file).use { it.write(buildDxf(result).toByteArray(Charsets.UTF_8)) }
        return file
    }

    // ====================== PDF ======================

    /** بتكتب ملف PDF فعلي (صفحة A4 عرضي لكل لوح) بنفس شكل المعاينة بالظبط —
     * إطار اللوح بلون boardBorderColor وخطوط القطع بالبرتقالي — عشان العميل
     * يفتحه على الموبايل ويشوف نتيجة الرص من غير أي برنامج متخصص. */
    fun savePdfFile(context: Context, result: NestingResult, boardBorderColor: Int): File {
        val document = PdfDocument()
        val pageWidthPt = 842 // A4 landscape @ 72dpi
        val pageHeightPt = 595

        val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = boardBorderColor
        }
        val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = Color.rgb(255, 138, 30)
        }
        val bgPaint = Paint().apply { color = Color.rgb(5, 7, 12) }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f
            isFakeBoldText = true
        }
        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(200, 205, 215)
            textSize = 11f
        }

        val boardsPerPage = 1
        var pageNumber = 1
        for (board in result.boards) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidthPt, pageHeightPt, pageNumber).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawRect(0f, 0f, pageWidthPt.toFloat(), pageHeightPt.toFloat(), bgPaint)

            val margin = 40f
            val headerHeight = 50f
            val availW = pageWidthPt - margin * 2
            val availH = pageHeightPt - margin * 2 - headerHeight
            val scale = minOf(availW / board.width.toFloat(), availH / board.height.toFloat())

            canvas.drawText("Amr3D Nesting — Board ${board.index}/${result.boards.size}", margin, margin + 14f, textPaint)
            canvas.drawText(
                "%.0f × %.0f mm  |  Utilization: %.1f%%  |  Cut length: %.2f m".format(
                    board.width, board.height, result.utilization, result.totalCuttingLengthMm / 1000.0
                ),
                margin, margin + 32f, infoPaint
            )

            canvas.save()
            canvas.translate(margin, margin + headerHeight)
            canvas.scale(scale, scale)

            boardPaint.strokeWidth = 2.5f / scale
            piecePaint.strokeWidth = 1.2f / scale

            canvas.drawRect(0f, 0f, board.width.toFloat(), board.height.toFloat(), boardPaint)

            for (piece in board.pieces) {
                val outer = transform(piece.polygon.outer, piece.rotationDeg, piece.x, piece.y)
                if (outer.size >= 2) {
                    val path = Path()
                    outer.forEachIndexed { i, p ->
                        if (i == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat())
                    }
                    path.close()
                    canvas.drawPath(path, piecePaint)
                }
            }
            canvas.restore()
            document.finishPage(page)
            pageNumber++
        }

        val dir = File(context.getExternalFilesDir(null), "Nesting")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "Nesting_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }
}
