package com.amr3d.preview.pro

import kotlin.math.*

/**
 * Mixed nesting for explicit DXF shapes and user-defined cabinet/board rectangles.
 *
 * Important invariants:
 *  - A custom-unit job never depends on the Viewer session.
 *  - The stored NestingPiece keeps the ORIGINAL polygon plus x/y/rotation once.
 *    The preview therefore does not rotate an already-rotated polygon a second time.
 *  - Placement candidates are generated from board edges and every placed-piece
 *    bounding box, with a bounded grid fallback instead of the old sparse search.
 */
object MixedNestingEngine {
    data class InputPiece(
        val polygon: NestingPolygon,
        val rotationMode: RotationMode = RotationMode.FREE,
        val label: String = "Piece"
    )

    private data class WorkPiece(val source: InputPiece, val ordinal: Int, val canonicalAngle: Double)
    private data class Placed(val collisionPolygon: List<NestingPoint>, val piece: NestingPiece)

    private const val EPS = 1e-7

    fun nest(
        pieces: List<InputPiece>,
        boardWidth: Double,
        boardHeight: Double,
        edgeMargin: Double,
        partGap: Double,
        rotationStepDeg: Double,
        onProgress: (NestingProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        boardColor: Int = 0xFF0D0F14.toInt()
    ): NestingResult {
        val start = System.currentTimeMillis()
        val requested = pieces.size
        if (requested == 0 || boardWidth <= 0.0 || boardHeight <= 0.0) {
            return NestingResult(emptyList(), requested, 0, 0.0, 0.0, 0.0, elapsed(start))
        }

        val edge = edgeMargin.coerceAtLeast(0.0)
        val gap = partGap.coerceAtLeast(0.0)
        val usableW = boardWidth - 2.0 * edge
        val usableH = boardHeight - 2.0 * edge
        if (usableW <= 0.0 || usableH <= 0.0) {
            return NestingResult(emptyList(), requested, 0, 0.0, 0.0, 0.0, elapsed(start))
        }

        // Large pieces first improves both cabinet packing and mixed DXF packing.
        val work = pieces.mapIndexed { i, p -> WorkPiece(p, i, canonicalAngleMinWidth(p.polygon.outer)) }
            .sortedByDescending { abs(area(it.source.polygon.outer)) }

        val boards = mutableListOf<NestingBoard>()
        var current = mutableListOf<Placed>()
        var boardIndex = 1
        var placedCount = 0
        var processed = 0
        var nextPieceIndex = 1

        fun finishBoard() {
            if (current.isEmpty()) return
            boards += NestingBoard(boardIndex++, boardWidth, boardHeight, current.map { it.piece }, boardColor)
            current = mutableListOf()
        }

        for (workPiece in work) {
            if (isCancelled()) break

            var best = findBestPlacement(
                workPiece = workPiece,
                current = current,
                boardWidth = boardWidth,
                boardHeight = boardHeight,
                edgeMargin = edge,
                partGap = gap,
                rotationStepDeg = rotationStepDeg,
                isCancelled = isCancelled
            )

            // If the current board has no room, seal it and try a fresh board.
            if (best == null && current.isNotEmpty() && !isCancelled()) {
                finishBoard()
                best = findBestPlacement(
                    workPiece = workPiece,
                    current = current,
                    boardWidth = boardWidth,
                    boardHeight = boardHeight,
                    edgeMargin = edge,
                    partGap = gap,
                    rotationStepDeg = rotationStepDeg,
                    isCancelled = isCancelled
                )
            }

            processed++
            if (best != null) {
                val p = best.copy(piece = best.piece.copy(index = nextPieceIndex++))
                current += p
                placedCount++
            }

            // Progress is based on pieces actually processed, never on a fake 100%.
            val percent = (processed * 100 / requested).coerceIn(0, 99)
            onProgress(NestingProgress(placedCount, requested, boardIndex, percent, NestingStage.NESTING, percent, "جاري الرص"))
        }

        finishBoard()
        if (placedCount == requested && !isCancelled()) {
            onProgress(NestingProgress(placedCount, requested, boards.lastOrNull()?.index ?: 1, 100, NestingStage.SAVING, 0, "جاري الحصول على أفضل توفير"))
            compactBoards(boards, edge, gap, isCancelled) { pct ->
                onProgress(NestingProgress(placedCount, requested, boards.lastOrNull()?.index ?: 1, 100, NestingStage.SAVING, pct, "جاري الحصول على أفضل توفير"))
            }
            onProgress(NestingProgress(placedCount, requested, boards.lastOrNull()?.index ?: 1, 100, NestingStage.SAVING, 100, "جاري الحصول على أفضل توفير"))
        }

        val sourceArea = pieces.sumOf { abs(area(it.polygon.outer)) }
        return NestingResult(
            boards = boards,
            totalRequested = requested,
            totalPlaced = placedCount,
            sourceWidth = pieces.maxOfOrNull { bounds(it.polygon.outer).w } ?: 0.0,
            sourceHeight = pieces.maxOfOrNull { bounds(it.polygon.outer).h } ?: 0.0,
            // NestingResult treats sourceArea as the average per-piece area.
            // Mixed jobs therefore divide the total requested area by piece count
            // so utilization is not multiplied twice.
            sourceArea = if (requested > 0) sourceArea / requested else 0.0,
            elapsedMs = elapsed(start)
        )
    }

    private fun findBestPlacement(
        workPiece: WorkPiece,
        current: List<Placed>,
        boardWidth: Double,
        boardHeight: Double,
        edgeMargin: Double,
        partGap: Double,
        rotationStepDeg: Double,
        isCancelled: () -> Boolean
    ): Placed? {
        val source = workPiece.source
        val rotations = rotations(source.rotationMode, rotationStepDeg, workPiece.canonicalAngle)
        var best: Placed? = null
        var bestScore = Double.POSITIVE_INFINITY

        for (angle in rotations) {
            if (isCancelled()) return null
            val rotated = rotate(source.polygon.outer, angle)
            val rb = bounds(rotated)
            val normalized = translate(rotated, -rb.minX, -rb.minY)
            val pw = rb.w
            val ph = rb.h
            if (pw > boardWidth - 2.0 * edgeMargin + EPS || ph > boardHeight - 2.0 * edgeMargin + EPS) continue

            val candidates = candidatePositions(current, boardWidth, boardHeight, pw, ph, edgeMargin, partGap)
            for ((x, y) in candidates) {
                if (isCancelled()) return null
                val placedPoly = translate(normalized, x, y)
                if (!inside(placedPoly, boardWidth, boardHeight, edgeMargin)) continue
                if (current.any { polygonsOverlap(placedPoly, it.collisionPolygon, partGap) }) continue

                val score = score(placedPoly, boardWidth, boardHeight)
                if (score < bestScore) {
                    bestScore = score
                    best = Placed(
                        collisionPolygon = placedPoly,
                        piece = NestingPiece(
                            index = workPiece.ordinal + 1,
                            polygon = source.polygon,
                            x = x,
                            y = y,
                            rotationDeg = Math.toDegrees(angle),
                            boundsWidth = pw,
                            boundsHeight = ph
                        )
                    )
                }
            }
        }
        return best
    }

    /**
     * Small, bounded true-shape compaction pass. It keeps every contour and rotation
     * unchanged, but tries to pull pieces toward board edges and neighbouring contours
     * while rechecking the real polygon clearance.
     */
    private fun compactBoards(
        boards: MutableList<NestingBoard>,
        edge: Double,
        gap: Double,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ) {
        val start = System.currentTimeMillis()
        val budget = 1200L
        var done = 0
        val total = boards.sumOf { it.pieces.size }.coerceAtLeast(1)
        for (bi in boards.indices) {
            if (isCancelled() || System.currentTimeMillis() - start > budget) break
            val board = boards[bi]
            val mutable = board.pieces.toMutableList()
            for (i in mutable.indices) {
                if (isCancelled() || System.currentTimeMillis() - start > budget) break
                val piece = mutable[i]
                fun placedOuter(q: NestingPiece): List<NestingPoint> {
                    val qr = Math.toRadians(q.rotationDeg)
                    val qc = cos(qr); val qs = sin(qr)
                    val rotated = q.polygon.outer.map { pp -> NestingPoint(pp.x * qc - pp.y * qs, pp.x * qs + pp.y * qc) }
                    val rb = bounds(rotated)
                    return rotated.map { pp -> NestingPoint(pp.x - rb.minX + q.x, pp.y - rb.minY + q.y) }
                }
                val poly = placedOuter(piece)
                val others = mutable.filterIndexed { idx, _ -> idx != i }.map { q ->
                    Placed(placedOuter(q), q)
                }
                val b = bounds(poly)
                val candidates = listOf(
                    b.minX - edge to 0.0,
                    0.0 to (b.minY - edge),
                    (board.width - edge - b.maxX) to 0.0,
                    0.0 to (board.height - edge - b.maxY)
                )
                var bestPiece = piece
                var bestScore = score(poly, board.width, board.height)
                for ((dx, dy) in candidates) {
                    val moved = poly.map { NestingPoint(it.x + dx, it.y + dy) }
                    if (!inside(moved, board.width, board.height, edge)) continue
                    if (others.any { polygonsOverlap(moved, it.collisionPolygon, gap) }) continue
                    val sc = score(moved, board.width, board.height)
                    if (sc < bestScore) {
                        bestScore = sc
                        bestPiece = piece.copy(x = piece.x + dx, y = piece.y + dy)
                    }
                }
                mutable[i] = bestPiece
                done++
                onProgress((done * 100 / total).coerceIn(0, 100))
            }
            boards[bi] = board.copy(pieces = mutable)
        }
    }

    /** Finds the orientation whose axis-aligned width is minimal before nesting starts. */
    private fun canonicalAngleMinWidth(p: List<NestingPoint>): Double {
        if (p.size < 3) return 0.0
        var bestAngle = 0.0
        var bestWidth = Double.POSITIVE_INFINITY
        var bestHeight = Double.POSITIVE_INFINITY
        for (deg in 0 until 180) {
            val a = Math.toRadians(deg.toDouble())
            val b = bounds(rotate(p, a))
            if (b.w < bestWidth - 1e-6 || (abs(b.w - bestWidth) <= 1e-6 && b.h < bestHeight)) {
                bestWidth = b.w
                bestHeight = b.h
                bestAngle = a
            }
        }
        return bestAngle
    }

    private fun rotations(mode: RotationMode, stepDeg: Double, baseAngle: Double): List<Double> {
        return when (mode) {
            RotationMode.HORIZONTAL -> listOf(0.0)
            RotationMode.VERTICAL -> listOf(baseAngle + PI / 2.0)
            RotationMode.FREE -> {
                val step = stepDeg.coerceIn(1.0, 90.0)
                val count = floor(180.0 / step).toInt()
                (0..count).map { baseAngle + Math.toRadians(it * step) }.distinct()
            }
        }
    }

    private data class B(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        val w get() = maxX - minX
        val h get() = maxY - minY
    }

    private fun bounds(p: List<NestingPoint>): B = B(
        p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y }
    )

    private fun area(p: List<NestingPoint>): Double =
        p.indices.sumOf { i ->
            val a = p[i]
            val b = p[(i + 1) % p.size]
            a.x * b.y - b.x * a.y
        } / 2.0

    private fun rotate(p: List<NestingPoint>, a: Double): List<NestingPoint> {
        val c = cos(a)
        val s = sin(a)
        return p.map { NestingPoint(it.x * c - it.y * s, it.x * s + it.y * c) }
    }

    private fun translate(p: List<NestingPoint>, x: Double, y: Double) =
        p.map { NestingPoint(it.x + x, it.y + y) }

    /**
     * Candidate coordinates are the actual lower-left coordinates of the
     * rotated piece bounding box. This is much more stable than subtracting
     * the margin from already-absolute piece coordinates.
     */
    private fun candidatePositions(
        current: List<Placed>,
        boardWidth: Double,
        boardHeight: Double,
        pw: Double,
        ph: Double,
        edgeMargin: Double,
        partGap: Double
    ): List<Pair<Double, Double>> {
        val out = LinkedHashSet<Pair<Double, Double>>()
        fun add(x: Double, y: Double) {
            if (x >= edgeMargin - EPS && y >= edgeMargin - EPS &&
                x <= boardWidth - edgeMargin - pw + EPS &&
                y <= boardHeight - edgeMargin - ph + EPS
            ) out += x.coerceAtLeast(edgeMargin) to y.coerceAtLeast(edgeMargin)
        }

        add(edgeMargin, edgeMargin)
        add(boardWidth - edgeMargin - pw, edgeMargin)
        add(edgeMargin, boardHeight - edgeMargin - ph)
        add(boardWidth - edgeMargin - pw, boardHeight - edgeMargin - ph)

        // Contact candidates from every already placed bounding box.
        // The same single margin is the required separation between parts,
        // so geometry-derived contacts are generated exactly at that gap.
        val gap = partGap.coerceAtLeast(0.0)
        for (q in current) {
            val b = bounds(q.collisionPolygon)
            add(b.maxX + gap, b.minY)
            add(b.minX - pw - gap, b.minY)
            add(b.minX, b.maxY + gap)
            add(b.minX, b.minY - ph - gap)
            add(b.maxX + gap, b.maxY + gap)
            add(b.maxX + gap, b.minY - ph - gap)
            add(b.minX - pw - gap, b.maxY + gap)
            add(b.maxX - pw, b.maxY - ph)
        }

        // Bounded deterministic fallback. It is deliberately small enough not
        // to freeze the UI, while being dense enough to recover placements the
        // old four-candidate algorithm routinely missed.
        val step = max(4.0, min(16.0, min(pw, ph).coerceAtLeast(4.0) / 3.0))
        val maxCandidates = 650
        var count = 0
        var y = edgeMargin
        while (y <= boardHeight - edgeMargin - ph + EPS && count < maxCandidates) {
            var x = edgeMargin
            while (x <= boardWidth - edgeMargin - pw + EPS && count < maxCandidates) {
                add(x, y)
                x += step
                count++
            }
            y += step
        }
        return out.toList()
    }

    private fun cross(a: NestingPoint, b: NestingPoint, c: NestingPoint) =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun onSegment(a: NestingPoint, b: NestingPoint, p: NestingPoint): Boolean =
        abs(cross(a, b, p)) < 1e-6 &&
            p.x >= min(a.x, b.x) - 1e-6 && p.x <= max(a.x, b.x) + 1e-6 &&
            p.y >= min(a.y, b.y) - 1e-6 && p.y <= max(a.y, b.y) + 1e-6

    private fun segmentsIntersect(a: NestingPoint, b: NestingPoint, c: NestingPoint, d: NestingPoint): Boolean {
        val c1 = cross(a, b, c)
        val c2 = cross(a, b, d)
        val c3 = cross(c, d, a)
        val c4 = cross(c, d, b)
        val proper = ((c1 > 1e-6 && c2 < -1e-6) || (c1 < -1e-6 && c2 > 1e-6)) &&
            ((c3 > 1e-6 && c4 < -1e-6) || (c3 < -1e-6 && c4 > 1e-6))
        if (proper) return true

        // Collinear overlap with positive length is a real collision; a single
        // touching endpoint is intentionally allowed for zero-clearance nesting.
        if (abs(c1) < 1e-6 && abs(c2) < 1e-6 && abs(c3) < 1e-6 && abs(c4) < 1e-6) {
            val useX = abs(a.x - b.x) >= abs(a.y - b.y)
            val a0 = if (useX) a.x else a.y
            val a1 = if (useX) b.x else b.y
            val c0 = if (useX) c.x else c.y
            val c1v = if (useX) d.x else d.y
            val overlap = min(max(a0, a1), max(c0, c1v)) - max(min(a0, a1), min(c0, c1v))
            return overlap > 1e-6
        }
        return false
    }

    private fun pointInPolygon(p: NestingPoint, poly: List<NestingPoint>): Boolean {
        var inside = false
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            if (onSegment(a, b, p)) return false
            if ((a.y > p.y) != (b.y > p.y)) {
                val x = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
                if (p.x < x) inside = !inside
            }
        }
        return inside
    }

    private fun polygonsOverlap(a: List<NestingPoint>, b: List<NestingPoint>, clearance: Double): Boolean {
        val ab = bounds(a)
        val bb = bounds(b)
        val pad = clearance.coerceAtLeast(0.0)
        if (ab.maxX < bb.minX - pad - EPS || bb.maxX < ab.minX - pad - EPS ||
            ab.maxY < bb.minY - pad - EPS || bb.maxY < ab.minY - pad - EPS) return false

        for (i in a.indices) {
            val a1 = a[i]
            val a2 = a[(i + 1) % a.size]
            for (j in b.indices) {
                if (segmentsIntersect(a1, a2, b[j], b[(j + 1) % b.size])) return true
            }
        }
        if (pointInPolygon(a[0], b) || pointInPolygon(b[0], a)) return true
        return pad > EPS && minSegmentDistance(a, b) < pad - EPS
    }

    private fun minSegmentDistance(a: List<NestingPoint>, b: List<NestingPoint>): Double {
        var best = Double.POSITIVE_INFINITY
        for (i in a.indices) {
            val a1 = a[i]; val a2 = a[(i + 1) % a.size]
            for (j in b.indices) {
                val b1 = b[j]; val b2 = b[(j + 1) % b.size]
                val d = segmentDistance(a1, a2, b1, b2)
                if (d < best) {
                    best = d
                    if (best <= EPS) return 0.0
                }
            }
        }
        return best
    }

    private fun segmentDistance(a: NestingPoint, b: NestingPoint, c: NestingPoint, d: NestingPoint): Double {
        if (segmentsIntersect(a, b, c, d) || onSegment(a, b, c) || onSegment(a, b, d) ||
            onSegment(c, d, a) || onSegment(c, d, b)) return 0.0
        return min(
            min(pointSegmentDistance(a, c, d), pointSegmentDistance(b, c, d)),
            min(pointSegmentDistance(c, a, b), pointSegmentDistance(d, a, b))
        )
    }

    private fun pointSegmentDistance(p: NestingPoint, a: NestingPoint, b: NestingPoint): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val denom = dx * dx + dy * dy
        if (denom <= EPS) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / denom).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private fun inside(p: List<NestingPoint>, w: Double, h: Double, m: Double): Boolean {
        val b = bounds(p)
        return b.minX >= m - EPS && b.minY >= m - EPS &&
            b.maxX <= w - m + EPS && b.maxY <= h - m + EPS
    }

    private fun score(p: List<NestingPoint>, w: Double, h: Double): Double {
        val b = bounds(p)
        // Prefer a compact occupied envelope, then lower-left placement.
        // This reduces the long strips of unused material produced by pure corner scoring.
        return (b.maxX * b.maxY) * 1000.0 + b.maxY * 10.0 + b.maxX + abs(area(p)) * 1e-7
    }

    private fun elapsed(start: Long) = System.currentTimeMillis() - start
}
