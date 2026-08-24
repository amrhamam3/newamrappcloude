package com.amr3d.preview.pro

/**
 * Explicit handoff from the Viewer/Slicer to Nesting.
 * Nesting must never treat this as a permanent source for custom-unit jobs.
 */
object NestingSession {
    var model: DxfModel? = null
    var sourceName: String = ""
    var sourceUri: android.net.Uri? = null
    /** True only when the current DXF was handed off by Viewer. */
    var fromViewer: Boolean = false

    fun clear() {
        model = null
        sourceName = ""
        sourceUri = null
        fromViewer = false
    }
}
