package com.amr3d.preview.pro

import kotlin.math.*

// ====================== Data Classes ======================

data class NestingPoint(val x: Double, val y: Double)

data class NestingPolygon(
    val outer: List<NestingPoint>,
    val holes: List<List<NestingPoint>> = emptyList()
)

data class NestingPiece(
    val index: Int,
    val polygon: NestingPolygon,
    val x: Double,
    val y: Double,
    val rotationDeg: Double,
    val boundsWidth: Double,
    val boundsHeight: Double
)

data class NestingBoard(
    val index: Int,
    val width: Double,
    val height: Double,
    val pieces: List<NestingPiece>,
    // ---- REVERTED (compatibility) ----
    // The previous revision changed this to Long, which breaks any
    // existing call site that expects an ARGB Int (Canvas/Paint APIs
    // take Int color values). Restored to Int to match the rest of
    // the project unless there's a project-wide reason to use Long.
    val color: Int = 0xFF0D0F14.toInt()
)

data class NestingResult(
    val boards: List<NestingBoard>,
    val totalRequested: Int,
    val totalPlaced: Int,
    val sourceWidth: Double,
    val sourceHeight: Double,
    val sourceArea: Double,
    val elapsedMs: Long
) {
    val boardArea: Double get() = boards.sumOf { it.width * it.height }
    val usedArea: Double get() = sourceArea * totalPlaced
    val utilization: Double get() = if (boardArea > 0.0) usedArea / boardArea * 100.0 else 0.0
    val wasteArea: Double get() = (boardArea - usedArea).coerceAtLeast(0.0)

    /** إجمالي طول مسار القص الفعلي بالمليمتر لكل القطع المرصوصة في كل الألواح،
     * شامل محيط الشكل الخارجي (outer) ومحيط أي فتحات داخلية (holes) لأنها
     * كمان محتاجة تتقطع بالليزر/البلازما. الدوران والإزاحة (x, y, rotationDeg)
     * ملهمش تأثير على طول المحيط، فبنحسبه على شكل القطعة الأصلي زي ما هو
     * من غير أي حاجة لتحويله لإحداثيات اللوح (transformed) الأول. */
    val totalCuttingLengthMm: Double
        get() = boards.sumOf { board ->
            board.pieces.sumOf { piece ->
                polygonPerimeter(piece.polygon.outer) +
                    piece.polygon.holes.sumOf { polygonPerimeter(it) }
            }
        }

    /** عدد "مسارات" القص المنفصلة (شكل خارجي + كل فتحة) — كل مسار محتاج
     * نقطة اختراق (Pierce) واحدة بالليزر قبل ما يبدأ يقطع فيها. */
    val totalCutPaths: Int
        get() = boards.sumOf { board -> board.pieces.sumOf { 1 + it.polygon.holes.size } }

    /** تقدير زمن القص بالليزر بالثانية = (طول المسار ÷ سرعة القطع) + (عدد
     * المسارات × زمن الاختراق لكل مسار). القيم الافتراضية (35 مم/ث للقطع،
     * 0.8 ث للاختراق) قيم شائعة تقريبية لقطع الصاج الرفيع بالليزر الليفي —
     * ممكن تتغيّر حسب سُمك ونوع الخامة الفعلية. */
    fun estimatedCuttingTimeSeconds(
        cutSpeedMmPerSec: Double = 35.0,
        pierceSeconds: Double = 0.8
    ): Double = totalCuttingLengthMm / cutSpeedMmPerSec.coerceAtLeast(0.001) +
        totalCutPaths * pierceSeconds
}

/** محيط مضلع مغلق (بيفترض إن آخر نقطة بترجع تلقائيًا لأول نقطة). */
private fun polygonPerimeter(points: List<NestingPoint>): Double {
    if (points.size < 2) return 0.0
    var sum = 0.0
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += hypot(b.x - a.x, b.y - a.y)
    }
    return sum
}

data class NestingConfig(
    val boardWidth: Double = 1220.0,
    val boardHeight: Double = 2440.0,
    val copies: Int = 1,
    val rotationStepDeg: Double = 15.0,
    val rotationMode: RotationMode = RotationMode.FREE,
    val grainAxis: GrainAxis = GrainAxis.FREE,
    val clearanceMm: Double = 0.0,
    // ---- REVERTED (compatibility, see NestingBoard.color) ----
    val boardColor: Int = 0xFF0D0F14.toInt(),
    val edgeTopMm: Double = 0.0,
    val edgeBottomMm: Double = 0.0,
    val edgeLeftMm: Double = 0.0,
    val edgeRightMm: Double = 0.0
)

enum class RotationMode { FREE, HORIZONTAL, VERTICAL }
enum class GrainAxis { FREE, HORIZONTAL, VERTICAL }

enum class NestingStage { NESTING, SAVING, PREVIEW }

data class NestingProgress(
    val placed: Int,
    val total: Int,
    val boardIndex: Int,
    val percent: Int,
    val stage: NestingStage = NestingStage.NESTING,
    val stagePercent: Int = percent,
    val stageLabel: String = "جاري الرص"
)

// ====================== Shape Builder ======================

object NestingShapeBuilder {

    private const val EPS = 0.05

    fun fromModel(model: DxfModel): NestingPolygon? {
        val segments = mutableListOf<Pair<NestingPoint, NestingPoint>>()

        for (l in model.lines) {
            val a = NestingPoint(l.x1.toDouble(), l.y1.toDouble())
            val b = NestingPoint(l.x2.toDouble(), l.y2.toDouble())
            if (distance(a, b) > EPS) segments += a to b
        }

        for (c in model.circles) {
            val pts = circlePoints(c.cx.toDouble(), c.cy.toDouble(), c.r.toDouble(), 96)
            for (i in pts.indices) segments += pts[i] to pts[(i + 1) % pts.size]
        }

        for (a in model.arcs) {
            val span = normalizedSpan(a.startDeg.toDouble(), a.endDeg.toDouble())
            val steps = max(8, min(500, ceil(abs(span) / 7.5).toInt()))
            val pts = (0..steps).map { i ->
                val d = a.startDeg.toDouble() + span * i / steps
                val r = Math.toRadians(d)
                NestingPoint(a.cx.toDouble() + a.r.toDouble() * cos(r), a.cy.toDouble() + a.r.toDouble() * sin(r))
            }
            for (i in 0 until pts.size - 1) segments += pts[i] to pts[i + 1]
        }

        if (segments.isEmpty()) return null

        val loops = traceFaces(segments)
            .map { cleanLoop(it) }
            .filter { it.size >= 3 && abs(signedArea(it)) > 0.01 }

        if (loops.isEmpty()) return null

        val outer = loops.maxByOrNull { abs(signedArea(it)) } ?: return null

        val holes = loops
            .filter { it !== outer }
            .filter { signedArea(it) * signedArea(outer) < 0.0 }
            .filter { it.isNotEmpty() && pointInPolygon(it[0], outer) }
            .map { normalizeWinding(it, wantPositive = signedArea(outer) < 0.0) }

        val woundOuter = normalizeWinding(outer, true)
        val minX = woundOuter.minOfOrNull { it.x } ?: 0.0
        val minY = woundOuter.minOfOrNull { it.y } ?: 0.0

        val outerNorm = woundOuter.map { NestingPoint(it.x - minX, it.y - minY) }
        val holeNorm = holes.map { h -> h.map { NestingPoint(it.x - minX, it.y - minY) } }

        return NestingPolygon(outer = outerNorm, holes = holeNorm)
    }

    private fun traceFaces(segments: List<Pair<NestingPoint, NestingPoint>>): List<List<NestingPoint>> {
        val points = mutableListOf<NestingPoint>()
        val cellSize = EPS * 2.0
        val grid = HashMap<Long, MutableList<Int>>()

        fun cellKey(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
        fun cellOf(p: NestingPoint) = floor(p.x / cellSize).toInt() to floor(p.y / cellSize).toInt()

        fun pointId(p: NestingPoint): Int {
            val (cx, cy) = cellOf(p)
            for (dx in -1..1) for (dy in -1..1) {
                val bucket = grid[cellKey(cx + dx, cy + dy)] ?: continue
                for (idx in bucket) if (distance(points[idx], p) <= EPS) return idx
            }
            points += p
            val newIdx = points.lastIndex
            grid.getOrPut(cellKey(cx, cy)) { mutableListOf() } += newIdx
            return newIdx
        }

        data class Edge(val a: Int, val b: Int)
        val edges = segments.map { Edge(pointId(it.first), pointId(it.second)) }
        if (edges.isEmpty()) return emptyList()

        data class Half(val from: Int, val to: Int, val edge: Int)
        val half = mutableListOf<Half>()
        val outgoing = Array(points.size) { mutableListOf<Int>() }

        for ((ei, e) in edges.withIndex()) {
            val h0 = half.size
            half += Half(e.a, e.b, ei)
            half += Half(e.b, e.a, ei)
            outgoing[e.a] += h0
            outgoing[e.b] += h0 + 1
        }

        val order = outgoing.map { list ->
            list.sortedWith(compareBy {
                atan2(points[half[it].to].y - points[half[it].from].y,
                    points[half[it].to].x - points[half[it].from].x)
            })
        }

        val next = IntArray(half.size) { -1 }
        for (h in half.indices) {
            val v = half[h].to
            val list = order[v]
            val reverse = list.indexOfFirst { half[it].to == half[h].from }
            if (reverse >= 0) next[h] = list[(reverse - 1 + list.size) % list.size]
        }

        val visited = BooleanArray(half.size)
        val faces = mutableListOf<List<NestingPoint>>()

        for (start in half.indices) {
            if (visited[start] || next[start] < 0) continue
            val loop = mutableListOf<NestingPoint>()
            var h = start
            var guard = 0
            while (!visited[h] && guard++ < half.size + 4) {
                visited[h] = true
                loop += points[half[h].from]
                h = next[h]
                if (h == start) break
            }
            if (h == start && loop.size >= 3) faces += loop
        }
        return faces
    }

    private fun cleanLoop(loop: List<NestingPoint>): List<NestingPoint> {
        val out = mutableListOf<NestingPoint>()
        for (p in loop) if (out.isEmpty() || distance(out.last(), p) > EPS) out += p
        if (out.size > 1 && distance(out.first(), out.last()) <= EPS) out.removeAt(out.lastIndex)
        return out
    }

    private fun normalizeWinding(p: List<NestingPoint>, wantPositive: Boolean): List<NestingPoint> {
        val a = signedArea(p)
        return if ((a > 0) == wantPositive) p else p.asReversed()
    }

    private fun circlePoints(cx: Double, cy: Double, r: Double, n: Int) =
        (0 until n).map {
            val a = 2.0 * PI * it / n
            NestingPoint(cx + r * cos(a), cy + r * sin(a))
        }

    private fun normalizedSpan(start: Double, end: Double): Double {
        var d = end - start
        while (d <= -360.0) d += 360.0
        while (d > 360.0) d -= 360.0
        return d
    }

    private fun signedArea(p: List<NestingPoint>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return s * 0.5
    }

    private fun distance(a: NestingPoint, b: NestingPoint) = hypot(a.x - b.x, a.y - b.y)

    private fun pointInPolygon(p: NestingPoint, poly: List<NestingPoint>): Boolean {
        var inside = false
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            if ((a.y > p.y) != (b.y > p.y)) {
                val x = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
                if (p.x < x) inside = !inside
            }
        }
        return inside
    }
}

// ====================== Nesting Engine - Smart Utilization Version ======================

object NestingEngine {

    private const val GEOM_EPS = 1e-5

    /** Canonical DXF orientation: rotate the contour to the angle with the smallest bounding width. */
    private fun orientToMinimumWidth(shape: NestingPolygon): NestingPolygon {
        if (shape.outer.size < 3) return shape
        var bestAngle = 0.0
        var bestWidth = Double.POSITIVE_INFINITY
        var bestHeight = Double.POSITIVE_INFINITY
        for (deg in 0 until 180) {
            val a = Math.toRadians(deg.toDouble())
            val c = cos(a); val sn = sin(a)
            val rotated = shape.outer.map { NestingPoint(it.x * c - it.y * sn, it.x * sn + it.y * c) }
            val minX = rotated.minOf { it.x }; val maxX = rotated.maxOf { it.x }
            val minY = rotated.minOf { it.y }; val maxY = rotated.maxOf { it.y }
            val w = maxX - minX; val h = maxY - minY
            if (w < bestWidth - 1e-6 || (abs(w - bestWidth) <= 1e-6 && h < bestHeight)) {
                bestWidth = w; bestHeight = h; bestAngle = a
            }
        }
        val c = cos(bestAngle); val sn = sin(bestAngle)
        val outerRaw = shape.outer.map { NestingPoint(it.x * c - it.y * sn, it.x * sn + it.y * c) }
        val minX = outerRaw.minOf { it.x }; val minY = outerRaw.minOf { it.y }
        fun transform(loop: List<NestingPoint>): List<NestingPoint> =
            loop.map { NestingPoint(it.x * c - it.y * sn - minX, it.x * sn + it.y * c - minY) }
        return shape.copy(outer = transform(shape.outer), holes = shape.holes.map(::transform))
    }

    // ---- REVERTED to the original conservative limits ----
    // The previous revision raised all of these at once (128/128/9000,
    // 6 seeds, 48 rotations, 5 refinement steps, k in -3..3). Combined
    // with the added smart-absorb pass calling findBestPlacement
    // repeatedly, that made worst-case cost roughly 5-6x higher than
    // before with no upper bound — a real freeze risk on larger copy
    // counts. Restoring the original values here; the smart-absorb
    // feature gets its own time budget instead (see below).
    private const val MAX_SOURCE_ANCHORS = 32
    private const val MAX_TARGET_ANCHORS = 48
    private const val MAX_CANDIDATES_PER_ROTATION = 1200
    private const val MAX_ANGLE_SEEDS = 2
    private const val MAX_COARSE_ROTATIONS = 18

    // Wall-clock budget for the smart last-board-absorb pass. It runs
    // *after* normal placement already succeeded, so if it runs out of
    // time it simply stops trying to move more pieces — the result is
    // never worse than skipping the pass entirely, just less optimal.
    private const val SMART_ABSORB_BUDGET_MS = 3000L

    data class CachedPolygon(val points: List<NestingPoint>, val bounds: B)
    data class CachedPiece(val piece: NestingPiece, val polygon: CachedPolygon)
    data class Candidate(val piece: NestingPiece, val score: Double)

    fun nest(
        shape: NestingPolygon,
        config: NestingConfig,
        onProgress: (NestingProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): NestingResult = nestInternal(orientToMinimumWidth(shape), config, onProgress, isCancelled)

    private fun nestInternal(
        shape: NestingPolygon,
        config: NestingConfig,
        onProgress: (NestingProgress) -> Unit,
        isCancelled: () -> Boolean
    ): NestingResult {
        val start = System.currentTimeMillis()
        val copies = config.copies.coerceAtLeast(1)

        if (shape.outer.size < 3) {
            return NestingResult(emptyList(), copies, 0, 0.0, 0.0, 0.0, System.currentTimeMillis() - start)
        }

        val sourceArea = (abs(area(shape.outer)) - shape.holes.sumOf { abs(area(it)) }).coerceAtLeast(0.0)
        val boards = mutableListOf<NestingBoard>()
        var remaining = copies
        var pieceIndex = 1

        val feasibleW = config.boardWidth - config.edgeLeftMm - config.edgeRightMm
        val feasibleH = config.boardHeight - config.edgeTopMm - config.edgeBottomMm
        if (feasibleW <= 0.0 || feasibleH <= 0.0) {
            return NestingResult(emptyList(), copies, 0, bounds(shape.outer).w, bounds(shape.outer).h, sourceArea, System.currentTimeMillis() - start)
        }

        val sourceAnchors = buildSourceAnchors(shape.outer, MAX_SOURCE_ANCHORS)

        // ========== المرحلة 1: الرص العادي ==========
        while (remaining > 0 && !isCancelled()) {
            val placed = mutableListOf<NestingPiece>()
            val boardIndex = boards.size + 1

            while (remaining > 0 && !isCancelled()) {
                val completed = copies - remaining
                val best = findBestPlacement(shape, sourceAnchors, placed, config, { phase ->
                    val p = (completed + phase.coerceIn(0.0, 0.99)) / copies * 100.0
                    onProgress(NestingProgress(completed, copies, boardIndex, p.roundToInt().coerceIn(0, 99)))
                }, isCancelled)

                if (best == null) break
                placed += best.copy(index = pieceIndex++)
                remaining--
                val done = copies - remaining
                onProgress(NestingProgress(done, copies, boardIndex, (done * 100 / copies).coerceIn(0, 100)))
            }

            if (placed.isEmpty()) break

            val optimized = optimizeBoard(placed, shape, sourceAnchors, config, isCancelled) { stage ->
                val placedDone = copies - remaining
                val overall = (placedDone.toDouble() / copies.coerceAtLeast(1) * 70.0 + stage * 20.0).roundToInt().coerceIn(70, 90)
                onProgress(NestingProgress(placedDone, copies, boardIndex, overall, NestingStage.SAVING, (stage * 100.0).roundToInt().coerceIn(0, 100), "جاري الحصول على أفضل توفير"))
            }
            boards += NestingBoard(boardIndex, config.boardWidth, config.boardHeight, optimized, config.boardColor)
        }

        // ========== المرحلة 2: التوفير الذكي (امتصاص اللوح الأخير) ==========
        // Bounded by SMART_ABSORB_BUDGET_MS — see smartAbsorbLastBoard.
        if (boards.size >= 2 && !isCancelled()) {
            smartAbsorbLastBoard(boards, shape, sourceAnchors, config, isCancelled, onProgress, copies)
        }

        val totalPlaced = boards.sumOf { it.pieces.size }
        if (totalPlaced == copies) {
            onProgress(NestingProgress(totalPlaced, copies, boards.lastOrNull()?.index ?: 1, 100, NestingStage.SAVING, 100, "جاري الحصول على أفضل توفير"))
        }

        return NestingResult(
            boards = boards.filter { it.pieces.isNotEmpty() },
            totalRequested = copies,
            totalPlaced = totalPlaced,
            sourceWidth = bounds(shape.outer).w,
            sourceHeight = bounds(shape.outer).h,
            sourceArea = sourceArea,
            elapsedMs = System.currentTimeMillis() - start
        )
    }

    /**
     * التوفير الذكي: يحاول ينقل قطع اللوح الأخير إلى الألواح السابقة.
     *
     * Bounded by a wall-clock time budget (SMART_ABSORB_BUDGET_MS): once
     * the budget is exceeded, remaining pieces simply stay on the last
     * board instead of being tried. This guarantees the pass can never
     * hang indefinitely on large copy counts — worst case it behaves as
     * if the pass were skipped for the pieces it didn't get to.
     * Reports approximate progress (99% band) so the UI doesn't appear
     * frozen after the main placement loop already hit "done".
     */
    private fun smartAbsorbLastBoard(
        boards: MutableList<NestingBoard>,
        shape: NestingPolygon,
        sourceAnchors: List<NestingPoint>,
        config: NestingConfig,
        isCancelled: () -> Boolean,
        onProgress: (NestingProgress) -> Unit,
        totalCopies: Int
    ) {
        if (boards.size < 2) return

        val lastBoard = boards.last()
        if (lastBoard.pieces.isEmpty()) return

        val absorbStart = System.currentTimeMillis()
        fun budgetExceeded() = System.currentTimeMillis() - absorbStart > SMART_ABSORB_BUDGET_MS

        val piecesToMove = lastBoard.pieces.toMutableList()
        val remainingPieces = mutableListOf<NestingPiece>()
        var processed = 0

        for (piece in piecesToMove) {
            if (isCancelled() || budgetExceeded()) {
                // Out of time/cancelled: leave this and all further
                // pieces where they are rather than risk hanging.
                remainingPieces += piece
                continue
            }

            var moved = false

            for (i in 0 until boards.size - 1) {
                if (isCancelled() || budgetExceeded()) break

                val board = boards[i]
                val currentPlaced = board.pieces.toMutableList()

                val candidate = findBestPlacement(
                    shape, sourceAnchors, currentPlaced, config,
                    {}, isCancelled, refinementEnabled = true
                )

                if (candidate != null) {
                    currentPlaced += candidate.copy(index = piece.index)
                    // Lightweight repack: skip the full 5-strategy sweep
                    // here since this already runs inside a piece x board
                    // loop — the main optimizeBoard() call after normal
                    // placement still gets the full treatment.
                    val optimized = optimizeBoard(currentPlaced, shape, sourceAnchors, config, isCancelled, lightweight = true)
                    boards[i] = board.copy(pieces = optimized)
                    moved = true
                    break
                }
            }

            if (!moved) remainingPieces += piece

            processed++
            val frac = processed.toDouble() / piecesToMove.size.coerceAtLeast(1)
            val pct = (99.0 + frac).coerceAtMost(100.0).roundToInt().coerceIn(99, 100)
            onProgress(NestingProgress(totalCopies, totalCopies, boards.size, pct, NestingStage.SAVING, frac.roundToInt().coerceIn(0, 100), "جاري الحصول على أفضل توفير"))
        }

        if (remainingPieces.isEmpty()) {
            // Every piece from the last board found a home elsewhere.
            boards.removeAt(boards.lastIndex)
        } else {
            val optimizedLast = optimizeBoard(remainingPieces, shape, sourceAnchors, config, isCancelled, lightweight = true)
            boards[boards.lastIndex] = lastBoard.copy(pieces = optimizedLast)
        }

        for (i in boards.indices) {
            boards[i] = boards[i].copy(index = i + 1)
        }
    }

    private fun optimizeBoard(
        initial: List<NestingPiece>,
        shape: NestingPolygon,
        sourceAnchors: List<NestingPoint>,
        config: NestingConfig,
        isCancelled: () -> Boolean,
        lightweight: Boolean = false,
        onProgress: (Double) -> Unit = {}
    ): List<NestingPiece> {
        if (initial.size < 2 || isCancelled()) return initial

        var bestResult = initial
        var bestScore = boardScore(initial)

        val allStrategies = listOf(
            initial.sortedWith(compareByDescending<NestingPiece> { placedBounds(it).w * placedBounds(it).h }.thenBy { it.index }),
            initial.sortedWith(compareByDescending<NestingPiece> { placedBounds(it).maxY }.thenByDescending { placedBounds(it).w * placedBounds(it).h }),
            initial.sortedWith(compareByDescending<NestingPiece> { placedBounds(it).w }.thenByDescending { placedBounds(it).h }),
            initial.sortedWith(compareByDescending<NestingPiece> { placedBounds(it).h }.thenByDescending { placedBounds(it).w }),
            initial.sortedBy { it.index }
        )

        // Lightweight mode (used by the smart-absorb pass, which already
        // calls this once per moved piece per candidate board) tries only
        // the two strongest strategies instead of all five, to keep the
        // absorb pass inside its time budget.
        val strategies = if (lightweight) allStrategies.take(2) else allStrategies

        for ((strategyIndex, order) in strategies.withIndex()) {
            if (isCancelled()) break
            onProgress(strategyIndex.toDouble() / strategies.size.coerceAtLeast(1))
            val rebuilt = mutableListOf<NestingPiece>()
            for (old in order) {
                if (isCancelled()) return bestResult
                val candidate = findBestPlacement(shape, sourceAnchors, rebuilt, config, {}, isCancelled, true)
                if (candidate == null) break
                rebuilt += candidate.copy(index = old.index)
            }
            if (rebuilt.size == initial.size) {
                val score = boardScore(rebuilt)
                if (score < bestScore - 1e-4) {
                    bestScore = score
                    bestResult = rebuilt.sortedBy { it.index }
                }
            }
        }
        onProgress(1.0)
        return bestResult
    }

    private fun findBestPlacement(
        shape: NestingPolygon,
        sourceAnchors: List<NestingPoint>,
        placed: List<NestingPiece>,
        config: NestingConfig,
        onSearchProgress: (Double) -> Unit,
        isCancelled: () -> Boolean,
        refinementEnabled: Boolean = true
    ): NestingPiece? {
        val rotations = allowedRotations(config)
        if (rotations.isEmpty()) return null

        val cachedPlaced = placed.map {
            CachedPiece(it, CachedPolygon(transformed(it.polygon.outer, it.rotationDeg, it.x, it.y), placedBounds(it)))
        }

        if (placed.isEmpty()) {
            var best: NestingPiece? = null
            var bestScore = Double.POSITIVE_INFINITY
            for (r in rotations) {
                if (isCancelled()) return null
                val rotated = transformed(shape.outer, r, 0.0, 0.0)
                val rb = bounds(rotated)
                if (rb.w > config.boardWidth - config.edgeLeftMm - config.edgeRightMm + GEOM_EPS) continue
                if (rb.h > config.boardHeight - config.edgeTopMm - config.edgeBottomMm + GEOM_EPS) continue
                val p = NestingPiece(0, shape, config.edgeLeftMm - rb.minX, config.edgeTopMm - rb.minY, r, rb.w, rb.h)
                if (insideBoard(p, config)) {
                    val s = placementScore(p, cachedPlaced, config)
                    if (s < bestScore) {
                        best = p
                        bestScore = s
                    }
                }
            }
            return best
        }

        val coarseBest = mutableListOf<Candidate>()
        for ((idx, rotation) in rotations.withIndex()) {
            if (isCancelled()) return null
            val candidate = bestForRotation(shape, sourceAnchors, placed, cachedPlaced, config, rotation, isCancelled)
            if (candidate != null) {
                insertTopCandidate(coarseBest, Candidate(candidate, placementScore(candidate, cachedPlaced, config)), MAX_ANGLE_SEEDS)
            }
            onSearchProgress((idx + 1).toDouble() / rotations.size * 0.80)
        }

        if (coarseBest.isEmpty()) return null
        if (config.rotationMode != RotationMode.FREE || !refinementEnabled) {
            return coarseBest.minWithOrNull(compareBy<Candidate> { it.score }.thenBy { scoreY(it.piece) }.thenBy { scoreX(it.piece) })?.piece
        }

        var globalBest = coarseBest.minWithOrNull(compareBy { it.score })!!.piece
        var globalScore = placementScore(globalBest, cachedPlaced, config)
        val base = config.rotationStepDeg.coerceIn(4.0, 45.0)
        // ---- REVERTED to 3 refinement steps / k in -2..2 (see class-level note) ----
        val steps = doubleArrayOf(base / 2.0, base / 4.0)

        for (step in steps) {
            for (seed in coarseBest) {
                for (k in -2..2) {
                    if (isCancelled()) return null
                    val angle = normalizeAngle(seed.piece.rotationDeg + k * step)
                    val candidate = bestForRotation(shape, sourceAnchors, placed, cachedPlaced, config, angle, isCancelled)
                    if (candidate != null) {
                        val score = placementScore(candidate, cachedPlaced, config)
                        if (score < globalScore) {
                            globalBest = candidate
                            globalScore = score
                        }
                    }
                }
            }
        }
        onSearchProgress(0.98)
        return globalBest
    }

    private fun bestForRotation(
        shape: NestingPolygon,
        sourceAnchors: List<NestingPoint>,
        placed: List<NestingPiece>,
        cachedPlaced: List<CachedPiece>,
        config: NestingConfig,
        rotation: Double,
        isCancelled: () -> Boolean
    ): NestingPiece? {
        val rotated = transformed(shape.outer, rotation, 0.0, 0.0)
        val rb = bounds(rotated)
        val minX = config.edgeLeftMm
        val minY = config.edgeTopMm
        val maxX = config.boardWidth - config.edgeRightMm
        val maxY = config.boardHeight - config.edgeBottomMm

        if (rb.w > maxX - minX + GEOM_EPS || rb.h > maxY - minY + GEOM_EPS) return null

        val targets = buildTargetAnchors(cachedPlaced, MAX_TARGET_ANCHORS)
        val candidates = ArrayList<NestingPiece>()
        val seen = HashSet<Pair<Long, Long>>()

        fun addCandidate(x: Double, y: Double) {
            if (candidates.size >= MAX_CANDIDATES_PER_ROTATION || isCancelled()) return
            val qx = (x * 1000).roundToLong()
            val qy = (y * 1000).roundToLong()
            if (!seen.add(qx to qy)) return
            val c = NestingPiece(0, shape, x, y, rotation, rb.w, rb.h)
            if (insideBoard(c, config) && !overlapsAnyCached(c, cachedPlaced, config.clearanceMm)) {
                candidates += c
            }
        }

        addCandidate(minX - rb.minX, minY - rb.minY)
        addCandidate(maxX - rb.maxX, minY - rb.minY)
        addCandidate(minX - rb.minX, maxY - rb.maxY)
        addCandidate(maxX - rb.maxX, maxY - rb.maxY)

        for (a in sourceAnchors) {
            for (t in targets) {
                val x = t.x - a.x
                val y = t.y - a.y
                addCandidate(x, y)
                val gap = config.clearanceMm.coerceAtLeast(0.0)
                if (gap > GEOM_EPS) {
                    addCandidate(x + gap, y); addCandidate(x - gap, y)
                    addCandidate(x, y + gap); addCandidate(x, y - gap)
                }
                if (candidates.size >= MAX_CANDIDATES_PER_ROTATION) break
            }
            if (candidates.size >= MAX_CANDIDATES_PER_ROTATION) break
        }

        for (cp in cachedPlaced) {
            val b = cp.polygon.bounds
            val xs = doubleArrayOf(b.minX - rb.maxX, b.maxX - rb.minX, b.minX - rb.minX, b.maxX - rb.maxX)
            val ys = doubleArrayOf(b.minY - rb.maxY, b.maxY - rb.minY, b.minY - rb.minY, b.maxY - rb.maxY)
            for (x in xs) { addCandidate(x, b.minY - rb.minY); addCandidate(x, b.maxY - rb.maxY) }
            for (y in ys) { addCandidate(b.minX - rb.minX, y); addCandidate(b.maxX - rb.maxX, y) }
            if (candidates.size >= MAX_CANDIDATES_PER_ROTATION) break
        }

        return candidates.minWithOrNull(
            compareBy<NestingPiece> { placementScore(it, cachedPlaced, config) }
                .thenBy { scoreY(it) }.thenBy { scoreX(it) }
        )
    }

    private fun buildSourceAnchors(points: List<NestingPoint>, maxCount: Int): List<NestingPoint> {
        if (points.size <= maxCount) return points
        val keep = LinkedHashSet<Int>()
        listOf(
            points.indices.minByOrNull { points[it].x },
            points.indices.maxByOrNull { points[it].x },
            points.indices.minByOrNull { points[it].y },
            points.indices.maxByOrNull { points[it].y }
        ).filterNotNull().forEach { keep += it }

        val edgeLengths = mutableListOf<Pair<Int, Double>>()
        for (i in points.indices) {
            val a = points[i]; val b = points[(i + 1) % points.size]
            edgeLengths += i to hypot(a.x - b.x, a.y - b.y)
        }
        edgeLengths.sortByDescending { it.second }
        val extra = (maxCount * 0.28).toInt().coerceAtLeast(6)
        for (i in 0 until min(extra, edgeLengths.size)) {
            keep += edgeLengths[i].first
            keep += (edgeLengths[i].first + 1) % points.size
        }

        val remaining = (maxCount - keep.size).coerceAtLeast(0)
        if (remaining > 0) {
            val step = points.size.toDouble() / remaining
            for (i in 0 until remaining) keep += floor(i * step).toInt().coerceIn(0, points.lastIndex)
        }
        return keep.map { points[it] }
    }

    private fun buildTargetAnchors(placed: List<CachedPiece>, maxTotal: Int): List<NestingPoint> {
        if (placed.isEmpty()) return emptyList()
        val perPiece = max(8, maxTotal / placed.size)
        val out = ArrayList<NestingPoint>()
        for (cp in placed) {
            out += sampleAnchors(cp.polygon.points, perPiece)
            val b = cp.polygon.bounds
            out += listOf(NestingPoint(b.minX, b.minY), NestingPoint(b.maxX, b.minY),
                NestingPoint(b.minX, b.maxY), NestingPoint(b.maxX, b.maxY))
            if (out.size >= maxTotal) break
        }
        return out
    }

    private fun sampleAnchors(points: List<NestingPoint>, maxCount: Int): List<NestingPoint> {
        if (points.size <= maxCount) return points
        val keep = LinkedHashSet<Int>()
        listOf(
            points.indices.minByOrNull { points[it].x },
            points.indices.maxByOrNull { points[it].x },
            points.indices.minByOrNull { points[it].y },
            points.indices.maxByOrNull { points[it].y }
        ).filterNotNull().forEach { keep += it }
        val remaining = (maxCount - keep.size).coerceAtLeast(0)
        if (remaining > 0) {
            val step = points.size.toDouble() / remaining
            for (i in 0 until remaining) keep += floor(i * step).toInt().coerceIn(0, points.lastIndex)
        }
        return keep.map { points[it] }
    }

    private fun placementScore(candidate: NestingPiece, placed: List<CachedPiece>, config: NestingConfig): Double {
        val b = placedBounds(candidate)
        var minX = config.edgeLeftMm
        var minY = config.edgeTopMm
        var maxX = config.edgeLeftMm
        var maxY = config.edgeTopMm
        for (p in placed) {
            val q = p.polygon.bounds
            minX = min(minX, q.minX); minY = min(minY, q.minY)
            maxX = max(maxX, q.maxX); maxY = max(maxY, q.maxY)
        }
        minX = min(minX, b.minX); minY = min(minY, b.minY)
        maxX = max(maxX, b.maxX); maxY = max(maxY, b.maxY)

        val envelopeW = (maxX - minX).coerceAtLeast(0.0)
        val envelopeH = (maxY - minY).coerceAtLeast(0.0)

        // Compact-envelope scoring: first minimize occupied envelope area,
        // then prefer a low/right-tight placement. This is substantially
        // better for sheet utilization than the old height-dominant score,
        // which could create long empty strips beside concave DXF parts.
        val envelopeArea = envelopeW * envelopeH
        val aspectPenalty = abs(envelopeW - envelopeH) * 80.0
        val bottomLeft = (b.minY - config.edgeTopMm) * 40.0 + (b.minX - config.edgeLeftMm) * 1.5
        val contact = nearestPlacedDistance(candidate, placed)
        val contactPenalty = if (contact.isFinite()) contact * 12.0 else 0.0

        return envelopeArea * 1200.0 + aspectPenalty + bottomLeft + contactPenalty
    }

    private fun nearestPlacedDistance(candidate: NestingPiece, placed: List<CachedPiece>): Double {
        if (placed.isEmpty()) return 0.0
        val cb = placedBounds(candidate)
        var best = Double.POSITIVE_INFINITY
        for (p in placed) {
            val pb = p.polygon.bounds
            val dx = when {
                cb.maxX < pb.minX -> pb.minX - cb.maxX
                pb.maxX < cb.minX -> cb.minX - pb.maxX
                else -> 0.0
            }
            val dy = when {
                cb.maxY < pb.minY -> pb.minY - cb.maxY
                pb.maxY < cb.minY -> cb.minY - pb.maxY
                else -> 0.0
            }
            best = min(best, hypot(dx, dy))
            if (best <= GEOM_EPS) break
        }
        return best
    }

    private fun boardScore(pieces: List<NestingPiece>): Double {
        if (pieces.isEmpty()) return Double.POSITIVE_INFINITY
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (p in pieces) {
            val b = placedBounds(p)
            minX = min(minX, b.minX); minY = min(minY, b.minY)
            maxX = max(maxX, b.maxX); maxY = max(maxY, b.maxY)
        }
        val w = maxX - minX; val h = maxY - minY
        return h * 3.4 + w * 1.1 + (w * h) * 0.0012
    }

    private fun allowedRotations(c: NestingConfig): List<Double> {
        if (c.grainAxis == GrainAxis.HORIZONTAL) return listOf(0.0, 180.0)
        if (c.grainAxis == GrainAxis.VERTICAL) return listOf(90.0, 270.0)
        return when (c.rotationMode) {
            RotationMode.HORIZONTAL -> listOf(0.0, 180.0)
            RotationMode.VERTICAL -> listOf(90.0, 270.0)
            RotationMode.FREE -> {
                val step = c.rotationStepDeg.coerceIn(2.0, 45.0)
                val count = ceil(360.0 / step).toInt().coerceAtMost(MAX_COARSE_ROTATIONS)
                val actualStep = 360.0 / count
                (0 until count).map { normalizeAngle(it * actualStep) }
            }
        }
    }

    private fun insideBoard(p: NestingPiece, c: NestingConfig): Boolean {
        val b = placedBounds(p)
        return b.minX >= c.edgeLeftMm - GEOM_EPS &&
                b.minY >= c.edgeTopMm - GEOM_EPS &&
                b.maxX <= c.boardWidth - c.edgeRightMm + GEOM_EPS &&
                b.maxY <= c.boardHeight - c.edgeBottomMm + GEOM_EPS
    }

    private fun overlapsAnyCached(p: NestingPiece, others: List<CachedPiece>, clearance: Double): Boolean {
        val pa = transformed(p.polygon.outer, p.rotationDeg, p.x, p.y)
        val ba = bounds(pa)
        val pad = clearance.coerceAtLeast(0.0)
        for (other in others) {
            val bb = other.polygon.bounds
            if (ba.maxX < bb.minX - pad - GEOM_EPS || bb.maxX < ba.minX - pad - GEOM_EPS ||
                ba.maxY < bb.minY - pad - GEOM_EPS || bb.maxY < ba.minY - pad - GEOM_EPS) continue
            if (polygonsOverlap(pa, other.polygon.points, pad)) return true
        }
        return false
    }

    private fun polygonsOverlap(a: List<NestingPoint>, b: List<NestingPoint>, clearance: Double): Boolean {
        val ba = bounds(a); val bb = bounds(b); val pad = clearance.coerceAtLeast(0.0)
        if (ba.maxX < bb.minX - pad - GEOM_EPS || bb.maxX < ba.minX - pad - GEOM_EPS ||
            ba.maxY < bb.minY - pad - GEOM_EPS || bb.maxY < ba.minY - pad - GEOM_EPS) return false

        for (i in a.indices) {
            val a1 = a[i]; val a2 = a[(i + 1) % a.size]
            for (j in b.indices) {
                val b1 = b[j]; val b2 = b[(j + 1) % b.size]
                if (properCross(a1, a2, b1, b2) || collinearOverlap(a1, a2, b1, b2)) return true
            }
        }
        if (a.any { pointInStrict(it, b) } || b.any { pointInStrict(it, a) }) return true
        if (pad > GEOM_EPS && minSegmentDistance(a, b) < pad - GEOM_EPS) return true
        return false
    }

    private fun minSegmentDistance(a: List<NestingPoint>, b: List<NestingPoint>): Double {
        var best = Double.POSITIVE_INFINITY
        for (i in a.indices) {
            val a1 = a[i]; val a2 = a[(i + 1) % a.size]
            for (j in b.indices) {
                val d = segmentDistance(a1, a2, b[j], b[(j + 1) % b.size])
                if (d < best) { best = d; if (best <= GEOM_EPS) return 0.0 }
            }
        }
        return best
    }

    private fun segmentDistance(a: NestingPoint, b: NestingPoint, c: NestingPoint, d: NestingPoint): Double {
        if (properCross(a, b, c, d) || collinearOverlap(a, b, c, d) ||
            onSegment(a, b, c) || onSegment(a, b, d) || onSegment(c, d, a) || onSegment(c, d, b)) return 0.0
        return min(min(pointSegmentDistance(a, c, d), pointSegmentDistance(b, c, d)),
            min(pointSegmentDistance(c, a, b), pointSegmentDistance(d, a, b)))
    }

    private fun pointSegmentDistance(p: NestingPoint, a: NestingPoint, b: NestingPoint): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val denom = dx * dx + dy * dy
        if (denom <= GEOM_EPS) return hypot(p.x - a.x, p.y - a.y)
        val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / denom
        val u = t.coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + u * dx), p.y - (a.y + u * dy))
    }

    private fun properCross(a1: NestingPoint, a2: NestingPoint, b1: NestingPoint, b2: NestingPoint): Boolean {
        val c1 = cross(a1, a2, b1); val c2 = cross(a1, a2, b2)
        val c3 = cross(b1, b2, a1); val c4 = cross(b1, b2, a2)
        return ((c1 > GEOM_EPS && c2 < -GEOM_EPS) || (c1 < -GEOM_EPS && c2 > GEOM_EPS)) &&
                ((c3 > GEOM_EPS && c4 < -GEOM_EPS) || (c3 < -GEOM_EPS && c4 > GEOM_EPS))
    }

    private fun collinearOverlap(a1: NestingPoint, a2: NestingPoint, b1: NestingPoint, b2: NestingPoint): Boolean {
        if (abs(cross(a1, a2, b1)) > GEOM_EPS || abs(cross(a1, a2, b2)) > GEOM_EPS ||
            abs(cross(b1, b2, a1)) > GEOM_EPS || abs(cross(b1, b2, a2)) > GEOM_EPS) return false
        val dx = abs(a2.x - a1.x); val dy = abs(a2.y - a1.y)
        val overlap = if (dx >= dy)
            min(max(a1.x, a2.x), max(b1.x, b2.x)) - max(min(a1.x, a2.x), min(b1.x, b2.x))
        else
            min(max(a1.y, a2.y), max(b1.y, b2.y)) - max(min(a1.y, a2.y), min(b1.y, b2.y))
        return overlap > GEOM_EPS
    }

    private fun pointInStrict(p: NestingPoint, poly: List<NestingPoint>): Boolean {
        var inside = false
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            if (onSegment(a, b, p)) return false
            if ((a.y > p.y) != (b.y > p.y)) {
                val x = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
                if (p.x < x) inside = !inside
            }
        }
        return inside
    }

    private fun cross(a: NestingPoint, b: NestingPoint, p: NestingPoint) =
        (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    private fun onSegment(a: NestingPoint, b: NestingPoint, p: NestingPoint) =
        abs(cross(a, b, p)) <= GEOM_EPS &&
                p.x >= min(a.x, b.x) - GEOM_EPS && p.x <= max(a.x, b.x) + GEOM_EPS &&
                p.y >= min(a.y, b.y) - GEOM_EPS && p.y <= max(a.y, b.y) + GEOM_EPS

    private fun transformed(poly: List<NestingPoint>, deg: Double, tx: Double, ty: Double): List<NestingPoint> {
        val r = Math.toRadians(deg)
        val c = cos(r); val s = sin(r)
        return poly.map { NestingPoint(it.x * c - it.y * s + tx, it.x * s + it.y * c + ty) }
    }

    data class B(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        val w get() = maxX - minX
        val h get() = maxY - minY
    }

    private fun bounds(p: List<NestingPoint>) = B(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })
    private fun placedBounds(p: NestingPiece) = bounds(transformed(p.polygon.outer, p.rotationDeg, p.x, p.y))
    private fun area(p: List<NestingPoint>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return s * 0.5
    }

    private fun scoreY(p: NestingPiece) = placedBounds(p).minY
    private fun scoreX(p: NestingPiece) = placedBounds(p).minX
    private fun normalizeAngle(a: Double): Double {
        var v = a % 360.0
        if (v < 0) v += 360.0
        return v
    }

    private fun insertTopCandidate(list: MutableList<Candidate>, candidate: Candidate, maxSize: Int) {
        list += candidate
        list.sortBy { it.score }
        if (list.size > maxSize) list.removeAt(list.lastIndex)
    }
}
