package com.amr3d.preview.pro

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

private data class CustomUnit(
    var length: Double = 600.0,
    var width: Double = 400.0,
    var quantity: Int = 1,
    var horizontal: Boolean = true,
    var row: View? = null
)

/**
 * Nesting UI is XML-first. Kotlin owns state, validation, navigation and engine orchestration;
 * all visual structure lives in fragment_nesting.xml / item_nesting_unit.xml.
 */
class NestingFragment : Fragment() {
    private lateinit var rootView: View
    private lateinit var preview: NestingPreviewView
    private lateinit var progressPanel: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressStage: TextView
    private lateinit var progressPercent: TextView
    private lateinit var cancelButton: Button
    private lateinit var sourceText: TextView
    private lateinit var inputType: Spinner
    private lateinit var chooseFile: Button
    private lateinit var boardPreset: Spinner
    private lateinit var boardWidth: EditText
    private lateinit var boardHeight: EditText
    private lateinit var unitsContainer: LinearLayout
    private lateinit var machineType: Spinner
    private lateinit var toolDiameter: EditText
    private lateinit var partGap: EditText
    private lateinit var edgeMargin: EditText
    private lateinit var nestingStrategy: Spinner
    private lateinit var dxfCopies: EditText
    private lateinit var grainDirection: Spinner
    private lateinit var rotationStep: EditText
    private lateinit var reviewText: TextView
    private lateinit var resultText: TextView
    private lateinit var startButton: Button
    private lateinit var boardColorButton: Button
    private lateinit var exportDxfButton: Button
    private lateinit var exportPdfButton: Button

    /** قايمة ألوان مقترحة لإطار اللوح — بتتنادى بالترتيب كل ما المستخدم يضغط
     * زرار "لون الإطار"، أسهل من عمل Color Picker كامل ومحافظة على نفس شكل
     * الشاشة الحالي بدون أي عنصر تحكم إضافي معقّد. */
    private val boardColorPalette = listOf(
        Color.rgb(56, 189, 248),  // سماوي (الافتراضي الجديد)
        Color.rgb(255, 255, 255), // أبيض
        Color.rgb(255, 138, 30),  // برتقالي (نفس لون القطع، تباين أقل لكن مطلوب أحيانًا)
        Color.rgb(74, 222, 128),  // أخضر
        Color.rgb(248, 113, 113)  // أحمر فاتح
    )
    private var boardColorIndex = 0

    private val units = mutableListOf<CustomUnit>()
    private var currentShape: NestingPolygon? = null
    private var lastResult: NestingResult? = null
    private var engineJob: Job? = null
    private val cancelled = AtomicBoolean(false)
    private var activeStep = 0
    private var unlockedStep = 0

    private val headers = mutableListOf<TextView>()
    private val bodies = mutableListOf<View>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_nesting, container, false)
        rootView = root
        bindViews(root)
        setupStaticAdapters()
        setupNavigation()
        setupSourceWorkflow()
        addUnitRow()
        loadSessionIfAvailable()
        openStep(0)
        return root
    }

    private fun bindViews(root: View) {
        preview = root.findViewById(R.id.nestingPreview)
        progressPanel = root.findViewById(R.id.progressPanel)
        progressBar = root.findViewById(R.id.progressBar)
        progressStage = root.findViewById(R.id.progressStage)
        progressPercent = root.findViewById(R.id.progressPercent)
        cancelButton = root.findViewById(R.id.cancelNesting)
        sourceText = root.findViewById(R.id.sourceText)
        inputType = root.findViewById(R.id.inputTypeSpinner)
        chooseFile = root.findViewById(R.id.chooseFileButton)
        boardPreset = root.findViewById(R.id.boardPreset)
        boardWidth = root.findViewById(R.id.boardWidth)
        boardHeight = root.findViewById(R.id.boardHeight)
        unitsContainer = root.findViewById(R.id.unitsContainer)
        machineType = root.findViewById(R.id.machineType)
        toolDiameter = root.findViewById(R.id.toolDiameter)
        partGap = root.findViewById(R.id.partGap)
        edgeMargin = root.findViewById(R.id.edgeMargin)
        nestingStrategy = root.findViewById(R.id.nestingStrategy)
        dxfCopies = root.findViewById(R.id.dxfCopies)
        grainDirection = root.findViewById(R.id.grainDirection)
        rotationStep = root.findViewById(R.id.rotationStep)
        reviewText = root.findViewById(R.id.reviewText)
        resultText = root.findViewById(R.id.resultText)
        startButton = root.findViewById(R.id.startNesting)
        boardColorButton = root.findViewById(R.id.btnBoardColor)
        exportDxfButton = root.findViewById(R.id.btnExportDxf)
        exportPdfButton = root.findViewById(R.id.btnExportPdf)

        val ids = listOf(
            R.id.step01Header, R.id.step02Header, R.id.step03Header, R.id.step04Header,
            R.id.step05Header, R.id.step06Header, R.id.step07Header
        )
        val bodyIds = listOf(
            R.id.step01Body, R.id.step02Body, R.id.step03Body, R.id.step04Body,
            R.id.step05Body, R.id.step06Body, R.id.step07Body
        )
        headers += ids.map { root.findViewById(it) }
        bodies += bodyIds.map { root.findViewById(it) }
    }

    private fun setupStaticAdapters() {
        boardPreset.adapter = spinnerAdapter(listOf("1220 × 2440", "1220 × 3050", "1830 × 3660", "مخصص"))
        machineType.adapter = spinnerAdapter(listOf("CNC", "Laser"))
        nestingStrategy.adapter = spinnerAdapter(listOf("أفضل توفير تلقائياً", "أولوية سرعة التنفيذ", "اتجاه ثابت"))
        grainDirection.adapter = spinnerAdapter(listOf("حر", "أفقي", "رأسي"))
        inputType.adapter = spinnerAdapter(listOf("DXF / تصميم", "قياسات مخصصة", "DXF + قياسات مخصصة"))

        boardPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> setBoard("1220", "2440")
                    1 -> setBoard("1220", "3050")
                    2 -> setBoard("1830", "3660")
                }
                val custom = position == 3
                boardWidth.isEnabled = custom
                boardHeight.isEnabled = custom
            }
        }
        machineType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                toolDiameter.visibility = if (position == 0) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupSourceWorkflow() {
        inputType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (NestingSession.fromViewer) return
                val customOnly = position == 1
                chooseFile.visibility = if (customOnly) View.GONE else View.VISIBLE
                sourceText.visibility = if (customOnly) View.GONE else View.VISIBLE
                if (customOnly) {
                    currentShape = null
                    NestingSession.clear()
                }
                updateConditionalSteps()
            }
        }
        chooseFile.setOnClickListener {
            if (NestingSession.fromViewer) {
                confirmResetViewerSource()
            } else {
                (activity as? MainActivity)?.openNestingFileBrowser(object : FileBrowserFragment.OnFileSelectedListener {
                    override fun onFileSelected(file: File) {
                        (activity as? MainActivity)?.returnFromNestingFileBrowser()
                        loadSelectedDxf(Uri.fromFile(file), file.name)
                    }
                })
            }
        }
        cancelButton.setOnClickListener { cancelEngine() }
        boardColorButton.setOnClickListener { cycleBoardBorderColor() }
        exportDxfButton.setOnClickListener { exportNestingDxf() }
        exportPdfButton.setOnClickListener { exportNestingPdf() }
        startButton.setOnClickListener { startEngine() }
        rootView.findViewById<ImageButton>(R.id.btnNestingBack).setOnClickListener {
            (activity as? MainActivity)?.closeNesting()
        }
        rootView.findViewById<ImageButton>(R.id.btnFullscreen).setOnClickListener { openFullscreenPreview() }
        rootView.findViewById<Button>(R.id.addUnitButton).setOnClickListener { addUnitRow() }
    }

    private fun setupNavigation() {
        rootView.findViewById<Button>(R.id.step01Next).setOnClickListener {
            val needsDxf = !isCustomOnly()
            if (needsDxf && currentShape == null) toast("اختَر ملف DXF أولاً") else advanceFrom(0)
        }
        rootView.findViewById<Button>(R.id.step02Next).setOnClickListener {
            if (boardWidth.value() > 0 && boardHeight.value() > 0) advanceFrom(1) else toast("أدخل مقاس اللوح بشكل صحيح")
        }
        rootView.findViewById<Button>(R.id.step03Next).setOnClickListener {
            syncAllUnits()
            if (units.isNotEmpty() && units.all { it.length > 0 && it.width > 0 && it.quantity > 0 }) advanceFrom(2)
            else toast("راجع مقاسات الوحدات والكميات")
        }
        rootView.findViewById<Button>(R.id.step04Next).setOnClickListener {
            if (partGap.value() >= 0 && edgeMargin.value() >= 0) advanceFrom(3) else toast("راجع المسافة والهامش")
        }
        rootView.findViewById<Button>(R.id.step05Next).setOnClickListener { advanceFrom(4) }
        rootView.findViewById<Button>(R.id.step06Next).setOnClickListener {
            updateReview(); advanceFrom(5)
        }
        headers.forEachIndexed { index, header ->
            header.setOnClickListener { if (index <= unlockedStep && isStepVisible(index)) openStep(index) }
        }
    }

    private fun advanceFrom(current: Int) {
        val next = nextVisibleStep(current + 1)
        if (next != null) {
            unlockedStep = maxOf(unlockedStep, next)
            openStep(next)
        }
    }

    private fun openStep(index: Int) {
        if (!isStepVisible(index)) {
            val next = nextVisibleStep(index)
            if (next != null) openStep(next)
            return
        }
        activeStep = index
        headers.forEachIndexed { i, h ->
            h.visibility = if (isStepVisible(i) && i <= unlockedStep) View.VISIBLE else View.GONE
            h.alpha = if (i <= unlockedStep) 1f else 0.52f
        }
        bodies.forEachIndexed { i, b ->
            b.visibility = if (isStepVisible(i) && i == index) View.VISIBLE else View.GONE
        }
        if (index == 5) updateReview()
        headers.getOrNull(index)?.post { headers[index].parent?.let { (it as? View)?.requestFocus() } }
    }

    private fun isStepVisible(index: Int): Boolean = when (index) {
        2 -> !isDxfOnly()
        4 -> !isCustomOnly()
        else -> true
    }

    private fun nextVisibleStep(start: Int): Int? = (start until headers.size).firstOrNull { isStepVisible(it) }

    private fun updateConditionalSteps() {
        if (!::inputType.isInitialized) return
        val dxfOnly = isDxfOnly()
        val customOnly = isCustomOnly()
        if (dxfOnly && activeStep == 2) activeStep = 3
        if (customOnly && activeStep == 4) activeStep = 5
        headers.forEachIndexed { i, h -> h.visibility = if (isStepVisible(i) && i <= unlockedStep) View.VISIBLE else View.GONE }
        bodies.forEachIndexed { i, b -> b.visibility = if (isStepVisible(i) && i == activeStep) View.VISIBLE else View.GONE }
    }

    private fun isDxfOnly() = NestingSession.fromViewer || inputType.selectedItemPosition == 0
    private fun isCustomOnly() = !NestingSession.fromViewer && inputType.selectedItemPosition == 1

    private fun addUnitRow() {
        val row = layoutInflater.inflate(R.layout.item_nesting_unit, unitsContainer, false)
        val unit = CustomUnit(row = row)
        val index = units.size + 1
        row.findViewById<TextView>(R.id.unitTitle).text = "وحدة ${index.toString().padStart(2, '0')}"
        row.findViewById<Spinner>(R.id.unitOrientation).adapter = spinnerAdapter(listOf("أفقي", "رأسي"))
        row.findViewById<Button>(R.id.removeUnit).setOnClickListener {
            units.remove(unit); unitsContainer.removeView(row); refreshUnitTitles()
        }
        units += unit
        unitsContainer.addView(row)
    }

    private fun refreshUnitTitles() {
        units.forEachIndexed { i, u -> u.row?.findViewById<TextView>(R.id.unitTitle)?.text = "وحدة ${(i + 1).toString().padStart(2, '0')}" }
    }

    private fun syncAllUnits() {
        units.forEach { u ->
            val r = u.row ?: return@forEach
            u.length = r.findViewById<EditText>(R.id.unitLength).value()
            u.width = r.findViewById<EditText>(R.id.unitWidth).value()
            u.quantity = r.findViewById<EditText>(R.id.unitQuantity).text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
            u.horizontal = r.findViewById<Spinner>(R.id.unitOrientation).selectedItemPosition == 0
        }
    }

    private fun updateReview() {
        syncAllUnits()
        val type = when {
            NestingSession.fromViewer -> "ملف من العارض"
            inputType.selectedItemPosition == 1 -> "قياسات مخصصة"
            else -> "DXF + قياسات مخصصة"
        }
        val machine = if (machineType.selectedItemPosition == 0) "CNC" else "Laser"
        val sb = StringBuilder()
        sb.append("✓ نوع الإدخال: $type\n")
        if (!isCustomOnly()) sb.append("✓ الملف: ${NestingSession.sourceName.ifBlank { "DXF جاهز" }}\n")
        sb.append("✓ اللوح: ${boardWidth.value().fmt()} × ${boardHeight.value().fmt()} mm\n")
        sb.append("✓ الماكينة: $machine\n")
        sb.append("✓ المسافة بين القطع: ${partGap.value().fmt()} mm\n")
        sb.append("✓ هامش الحواف الموحد: ${edgeMargin.value().fmt()} mm\n")
        if (machine == "CNC") sb.append("✓ قطر الأداة: ${toolDiameter.value().fmt()} mm\n")
        if (!isCustomOnly()) {
            sb.append("✓ نسخ DXF: ${dxfCopies.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1}\n")
            sb.append("✓ الاستراتيجية: ${nestingStrategy.selectedItem}\n")
            sb.append("✓ خطوة الدوران: ${rotationStep.value().fmt()}°\n")
        }
        if (!isDxfOnly()) {
            sb.append("✓ الوحدات: ${units.size}\n")
            units.forEachIndexed { i, u -> sb.append("  • ${i + 1}: ${u.length.fmt()} × ${u.width.fmt()} — ${u.quantity} — ${if (u.horizontal) "أفقي" else "رأسي"}\n") }
        }
        reviewText.text = sb.toString()
    }

    private fun confirmResetViewerSource() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("إعادة تعيين ملف العارض؟")
            .setMessage("سيتم إلغاء الملف المحدد من العارض. هل تريد المتابعة واختيار مصدر جديد؟")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("موافق") { _, _ ->
                NestingSession.clear(); currentShape = null
                inputType.adapter = spinnerAdapter(listOf("DXF / تصميم", "قياسات مخصصة", "DXF + قياسات مخصصة"))
                inputType.isEnabled = true; inputType.setSelection(0)
                chooseFile.text = "اختيار ملف DXF"
                sourceText.text = "لم يتم التحديد"
                unlockedStep = 0; activeStep = 0
                updateConditionalSteps(); openStep(0)
            }.show()
    }

    private fun loadSessionIfAvailable() {
        val model = NestingSession.model ?: return
        val fromViewer = NestingSession.fromViewer
        if (fromViewer) {
            inputType.adapter = spinnerAdapter(listOf("ملف من العارض"))
            inputType.isEnabled = false
            chooseFile.text = "✓ تم تحديد ملف من العارض — اضغط لإعادة التعيين"
            sourceText.text = "تم تحديد ملف من العارض: ${NestingSession.sourceName.ifBlank { "DXF" }}"
            lifecycleScope.launch(Dispatchers.Default) {
                val shape = NestingShapeBuilder.fromModel(model)
                withContext(Dispatchers.Main) { currentShape = shape; unlockedStep = 1; updateConditionalSteps(); openStep(0) }
            }
        } else {
            sourceText.text = "تم تحديد ملف DXF: ${NestingSession.sourceName.ifBlank { "DXF" }}"
        }
    }

    private fun loadSelectedDxf(uri: Uri, displayName: String) {
        sourceText.text = "جاري تحليل DXF…"
        lifecycleScope.launch {
            try {
                val model = withContext(Dispatchers.IO) { DXFParser.parse(requireContext(), uri) }
                val shape = withContext(Dispatchers.Default) { NestingShapeBuilder.fromModel(model) }
                NestingSession.model = model
                NestingSession.sourceUri = uri
                NestingSession.sourceName = displayName
                NestingSession.fromViewer = false
                currentShape = shape
                sourceText.text = if (shape == null) "تعذر استخراج Contour مغلق من DXF" else "تم تحديد ملف DXF: $displayName"
                if (shape != null) { unlockedStep = 1; openStep(0) }
            } catch (e: Exception) {
                sourceText.text = "خطأ في DXF: ${e.message ?: "غير معروف"}"
            }
        }
    }

    private fun buildMixedPieces(): List<MixedNestingEngine.InputPiece> {
        syncAllUnits()
        val result = mutableListOf<MixedNestingEngine.InputPiece>()
        val type = if (NestingSession.fromViewer) 0 else inputType.selectedItemPosition
        if (type != 1) {
            val shape = currentShape ?: return emptyList()
            val copies = dxfCopies.text.toString().toIntOrNull()?.coerceIn(1, 10000) ?: 1
            repeat(copies) { result += MixedNestingEngine.InputPiece(shape, RotationMode.FREE, "DXF") }
        }
        if (type != 0) {
            units.forEach { u ->
                repeat(u.quantity) {
                    val rect = NestingPolygon(listOf(
                        NestingPoint(0.0, 0.0), NestingPoint(u.length, 0.0),
                        NestingPoint(u.length, u.width), NestingPoint(0.0, u.width)
                    ))
                    result += MixedNestingEngine.InputPiece(rect, if (u.horizontal) RotationMode.HORIZONTAL else RotationMode.VERTICAL, "Cabinet")
                }
            }
        }
        return result
    }

    private fun startEngine() {
        if (engineJob?.isActive == true) return
        syncAllUnits()
        val bw = boardWidth.value().coerceAtLeast(1.0)
        val bh = boardHeight.value().coerceAtLeast(1.0)
        val edge = edgeMargin.value().coerceAtLeast(0.0)
        val gap = partGap.value().coerceAtLeast(0.0)
        val type = if (NestingSession.fromViewer) 0 else inputType.selectedItemPosition
        val mixed = type == 2 || type == 1
        val strategy = if (!isCustomOnly()) nestingStrategy.selectedItemPosition else 1
        val effectiveRotationStep = when (strategy) {
            1 -> maxOf(30.0, rotationStep.value().coerceIn(1.0, 90.0))
            2 -> 90.0
            else -> rotationStep.value().coerceIn(1.0, 90.0)
        }

        cancelled.set(false)
        progressPanel.visibility = View.VISIBLE
        progressBar.progress = 0
        progressStage.text = "جاري الرص"
        progressPercent.text = "0%"
        startButton.isEnabled = false
        resultText.text = "جاري تنفيذ الرص…"

        engineJob = lifecycleScope.launch {
            val publish: (NestingProgress) -> Unit = { p ->
                if (isAdded) {
                    progressBar.post {
                        progressStage.text = p.stageLabel
                        progressBar.progress = p.stagePercent.coerceIn(0, 100)
                        progressPercent.text = "${p.stagePercent.coerceIn(0, 100)}%"
                    }
                }
            }
            val result = withContext(Dispatchers.Default) {
                if (mixed) {
                    MixedNestingEngine.nest(
                        buildMixedPieces(), bw, bh, edge, gap,
                        effectiveRotationStep,
                        onProgress = publish,
                        isCancelled = { cancelled.get() }, boardColor = 0xFF0D0F14.toInt()
                    )
                } else {
                    val shape = currentShape ?: return@withContext NestingResult(emptyList(), 1, 0, 0.0, 0.0, 0.0, 0)
                    NestingEngine.nest(
                        shape,
                        NestingConfig(
                            boardWidth = bw, boardHeight = bh,
                            copies = dxfCopies.text.toString().toIntOrNull()?.coerceIn(1, 10000) ?: 1,
                            rotationStepDeg = rotationStep.value().coerceIn(1.0, 90.0),
                            rotationMode = RotationMode.FREE,
                            grainAxis = when (grainDirection.selectedItemPosition) { 1 -> GrainAxis.HORIZONTAL; 2 -> GrainAxis.VERTICAL; else -> GrainAxis.FREE },
                            clearanceMm = gap, boardColor = 0xFF0D0F14.toInt(),
                            edgeTopMm = edge, edgeBottomMm = edge, edgeLeftMm = edge, edgeRightMm = edge
                        ), onProgress = publish, isCancelled = { cancelled.get() }
                    )
                }
            }
            if (!isAdded) return@launch
            lastResult = result
            withContext(Dispatchers.Main) {
                progressStage.text = "تجهيز المعاينة"
                progressBar.progress = 0
                progressPercent.text = "0%"
                preview.result = result
                progressBar.progress = 100
                progressPercent.text = "100%"
            }
            delay(180)
            progressPanel.visibility = View.GONE
            startButton.isEnabled = true
            val complete = result.totalPlaced == result.totalRequested && result.totalRequested > 0
            // طول مسار القص بالمتر ووقت القص التقديري (دقايق:ثواني) — بيتحسبوا
            // من محيط كل القطع المرصوصة الفعلي (بما فيه أي فتحات داخلية)، مش
            // تقدير تقريبي على شكل اللوح نفسه.
            val cutLenM = "%.2f".format(result.totalCuttingLengthMm / 1000.0)
            val cutSecs = result.estimatedCuttingTimeSeconds().roundToInt()
            val cutTimeLabel = "${cutSecs / 60}:${"%02d".format(cutSecs % 60)}"
            resultText.text = if (complete) {
                "تم الرص بالكامل: ${result.totalPlaced}/${result.totalRequested} قطعة | ألواح: ${result.boards.size} | استغلال: ${"%.1f".format(result.utilization)}% | طول القص: ${cutLenM}م | زمن قص تقديري: $cutTimeLabel | زمن الحساب: ${result.elapsedMs}ms"
            } else {
                "تم رص ${result.totalPlaced}/${result.totalRequested} قطعة. لم يتم اعتبار العملية ناجحة قبل إكمال كل القطع."
            }
            // التصدير مسموح بس لو فيه على الأقل لوح واحد وقطعة واحدة اتحطت فعلاً،
            // حتى لو العملية "غير مكتملة" (بعض القطع اتحطت والباقي لأ) — أحسن من
            // منع التصدير كليًا وإجبار المستخدم يعيد كل حاجة من الأول.
            val canExport = result.boards.isNotEmpty() && result.totalPlaced > 0
            exportDxfButton.isEnabled = canExport
            exportPdfButton.isEnabled = canExport
        }
    }

    /** بتلف على قايمة ألوان مقترحة لإطار اللوح في المعاينة، وبتحدّث المعاينة
     * فورًا لو فيه نتيجة رص ظاهرة حاليًا. */
    private fun cycleBoardBorderColor() {
        boardColorIndex = (boardColorIndex + 1) % boardColorPalette.size
        preview.boardBorderColor = boardColorPalette[boardColorIndex]
    }

    /** بتصدّر نتيجة الرص الحالية كملف DXF حقيقي (بالمليمتر) وتفتح قايمة
     * المشاركة عشان المستخدم يبعته مباشرة لبرنامج الماكينة أو يحفظه. */
    private fun exportNestingDxf() {
        val result = lastResult ?: return
        try {
            val file = NestingExport.saveDxfFile(requireContext(), result)
            shareExportedFile(file, "application/dxf")
            Toast.makeText(context, getString(R.string.nesting_export_dxf_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.nesting_export_failed), Toast.LENGTH_LONG).show()
        }
    }

    /** بتصدّر نتيجة الرص الحالية كملف PDF بصفحة لكل لوح، بنفس شكل ولون
     * المعاينة الحالي — مناسب للمشاركة مع العميل عشان يفتحه على موبايله عادي. */
    private fun exportNestingPdf() {
        val result = lastResult ?: return
        try {
            val file = NestingExport.savePdfFile(requireContext(), result, preview.boardBorderColor)
            shareExportedFile(file, "application/pdf")
            Toast.makeText(context, getString(R.string.nesting_export_pdf_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.nesting_export_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun shareExportedFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.nesting_export_share_title)))
    }

    private fun cancelEngine() {
        cancelled.set(true); engineJob?.cancel(); engineJob = null
        progressPanel.visibility = View.GONE
        startButton.isEnabled = true
    }

    private fun openFullscreenPreview() {
        val r = lastResult ?: preview.result ?: return
        val d = Dialog(requireContext())
        val v = NestingPreviewView(requireContext()).apply { result = r; showAllBoards = true }
        d.setContentView(v); d.show(); d.window?.setLayout(-1, -1)
    }

    private fun spinnerAdapter(items: List<String>) = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, items)
    private fun setBoard(w: String, h: String) { boardWidth.setText(w); boardHeight.setText(h) }
    private fun EditText.value() = text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
    private fun Double.fmt() = if (abs(this - roundToInt()) < 0.001) roundToInt().toString() else "%.2f".format(this)
    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() { cancelled.set(true); engineJob?.cancel(); super.onDestroyView() }
    companion object { fun newInstance() = NestingFragment() }
}
