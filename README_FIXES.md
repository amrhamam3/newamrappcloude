# Amr3D PreviewPro Nesting — v5 Fixes

This revision corrects the Nesting workflow and the mixed/custom nesting engine.

## Workflow fixes
- Custom cabinets/units are fully independent from the DXF currently loaded in Viewer.
- Selecting **خزائن / وحدات مخصصة** immediately clears the inherited Viewer DXF session.
- DXF is only required for **DXF / تصميم** and **DXF + خزائن / وحدات مخصصة**.
- The **الوحدات / الخزائن** step is hidden automatically for DXF-only jobs.
- The **اتجاه وطريقة الرص** step is hidden automatically for custom-only jobs because every custom unit already stores its own أفقي/رأسي orientation.
- The workflow skips hidden steps automatically.
- Returning from custom-only to a DXF job requires an explicit DXF selection, preventing stale-file reuse.

## Nesting engine fixes
- Fixed the custom-unit orientation being applied twice.
- Fixed the mixed-engine preview geometry being rotated twice.
- Reworked mixed candidate generation with board-edge, piece-edge, and bounded grid fallback candidates.
- Mixed nesting no longer silently stops at the first unplaceable item.
- Progress is based on processed items and never claims 100% unless all requested pieces were actually placed.
- Removed the UI progress-post flood by throttling main-thread progress updates.
- The Fragment no longer overwrites a partial result with a fake 100% completion state.

## Verification
- Mixed engine sources compile successfully with standalone Kotlin syntax/type checking using temporary model stubs.
- Full Android Gradle build could not be executed in this offline environment because the Gradle 8.4 distribution is not cached and the wrapper attempted to reach services.gradle.org.

## v6 DXF / Viewer workflow + performance fixes
- Viewer handoff is explicit via `NestingSession.fromViewer`.
- Nesting shows `✓ تم تحديد ملف من العارض — اضغط لإعادة التعيين` when opened from Viewer.
- Cabinet/custom-unit choices are hidden/disabled for a Viewer DXF job until the user explicitly resets the source.
- Reset requires confirmation and clears the Viewer source before allowing DXF/manual/custom workflows again.
- The single margin value is enforced both at board edges and between nested parts.
- Mixed/custom nesting now generates exact margin-offset contact candidates and performs true segment-distance clearance checks.
- DXF search was tightened for speed: fewer anchors/candidates/rotation seeds, while keeping targeted geometry-derived candidates and the smart saving pass.
- DXF placement scoring now favors compact occupied-envelope area to reduce empty strips and material waste.
- Progress is staged: `جاري الرص` → `جاري الحصول على أفضل توفير` → `جاري تجهيز المعاينة`, with stage-specific percentages.

## Final XML-first Nesting revision

- Nesting screen visual structure moved from programmatic Kotlin view creation to XML: `fragment_nesting.xml` and `item_nesting_unit.xml`.
- UI follows the approved Amr3D Nesting Pro black/orange workflow with numbered sections and gated progression.
- Future sections stay hidden until the current section is completed; conditional sections are hidden automatically.
- Viewer-originated DXF locks the source to `تم تحديد ملف من العارض` and hides custom cabinet/unit entry. Pressing the source button opens a confirmation dialog before reset.
- Direct Nesting file selection now uses the app's existing internal `FileBrowserFragment`, not Android/Google document picker.
- Closing Nesting clears the handoff session so an old Viewer DXF cannot leak into a later custom job.
- Custom units use an XML item row with length, width, quantity, and per-unit horizontal/vertical orientation.
- CNC/Laser settings use a single unified edge-margin field plus a separate part-to-part clearance field so the two concepts are not mixed.
- DXF nesting performs a minimum-width canonical orientation search before placement, then evaluates the full board with true polygon collision/clearance checks.
- Mixed nesting has a bounded true-shape compaction pass and a faster candidate search to reduce UI stalls and material waste.
- Progress is split into real stages: `جاري الرص`, `جاري الحصول على أفضل توفير`, and `تجهيز المعاينة`.
- Mixed-job utilization accounting was corrected so total source area is not multiplied twice.
