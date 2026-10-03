package com.example.offlinedocumentcomposer.domain.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

data class PageEditItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceFile: File,
    val sourcePageIndex: Int,
    val rotation: Int = 0, // 0, 90, 180, 270
    val isImage: Boolean = false
)

data class PdfAnnotationOverlay(
    val id: String = UUID.randomUUID().toString(),
    val pageIndex: Int,
    val normX: Float, // 0..1 relative to page width
    val normY: Float, // 0..1 relative to page height
    val normW: Float, // 0..1 relative to page width
    val normH: Float, // 0..1 relative to page height
    val signatureBitmap: Bitmap? = null,
    val text: String? = null,
    val textColor: Int = Color.BLACK,
    val textSizePt: Float = 14f,
    val isBold: Boolean = false
)

class PdfEditorService(private val context: Context) {

    private val scratchDir: File = context.cacheDir

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    private fun memorySetting(): MemoryUsageSetting {
        return MemoryUsageSetting.setupTempFileOnly().setTempDir(scratchDir)
    }

    /**
     * Merges multiple PDF files in the specified order into [outputFile].
     */
    suspend fun mergePdfs(
        files: List<File>,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require(files.size >= 2) { "Please select at least 2 PDF files to merge" }
        outputFile.parentFile?.mkdirs()

        val merger = PDFMergerUtility().apply {
            destinationFileName = outputFile.absolutePath
        }

        for ((index, file) in files.withIndex()) {
            merger.addSource(file)
            onProgress((index + 1).toFloat() / files.size)
        }

        merger.mergeDocuments(memorySetting())
        return@withContext outputFile
    }

    /**
     * Splits [sourceFile] by extracting only [pageIndices] (0-indexed) into [outputFile].
     */
    suspend fun splitPdfByPages(
        sourceFile: File,
        pageIndices: List<Int>,
        outputFile: File
    ): File = withContext(Dispatchers.IO) {
        require(pageIndices.isNotEmpty()) { "Select at least one page to extract" }
        outputFile.parentFile?.mkdirs()

        PDDocument.load(sourceFile, memorySetting()).use { srcDoc ->
            PDDocument(memorySetting()).use { destDoc ->
                for (pageIdx in pageIndices) {
                    if (pageIdx in 0 until srcDoc.numberOfPages) {
                        destDoc.importPage(srcDoc.getPage(pageIdx))
                    }
                }
                destDoc.save(outputFile)
            }
        }
        return@withContext outputFile
    }

    /**
     * Extracts every single page of [sourceFile] into separate 1-page PDF files in [outputDir].
     */
    suspend fun splitPdfAllPages(
        sourceFile: File,
        outputDir: File,
        baseName: String
    ): List<File> = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val results = mutableListOf<File>()

        PDDocument.load(sourceFile, memorySetting()).use { srcDoc ->
            val splitter = Splitter()
            val splitDocs = splitter.split(srcDoc)
            for ((index, doc) in splitDocs.withIndex()) {
                val outFile = File(outputDir, "${baseName}_page_${index + 1}.pdf")
                doc.save(outFile)
                doc.close()
                results.add(outFile)
            }
        }
        return@withContext results
    }

    /**
     * Reorganizes, rotates, and combines pages according to [pageItems] into [outputFile].
     */
    suspend fun organizePdf(
        pageItems: List<PageEditItem>,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require(pageItems.isNotEmpty()) { "At least one page is required" }
        outputFile.parentFile?.mkdirs()

        // Cache open PDDocuments by file path to avoid repeated file open overhead
        val docCache = mutableMapOf<String, PDDocument>()

        try {
            PDDocument(memorySetting()).use { outDoc ->
                for ((index, item) in pageItems.withIndex()) {
                    if (item.isImage) {
                        // Insert image as a full page
                        val imageBitmap = android.graphics.BitmapFactory.decodeFile(item.sourceFile.absolutePath)
                        if (imageBitmap != null) {
                            val pageW = imageBitmap.width.toFloat()
                            val pageH = imageBitmap.height.toFloat()
                            val page = PDPage(PDRectangle(pageW, pageH))
                            if (item.rotation != 0) {
                                page.rotation = (page.rotation + item.rotation) % 360
                            }
                            outDoc.addPage(page)

                            val pdImage = JPEGFactory.createFromImage(outDoc, imageBitmap, 0.88f)
                            PDPageContentStream(outDoc, page).use { cs ->
                                cs.drawImage(pdImage, 0f, 0f, pageW, pageH)
                            }
                            imageBitmap.recycle()
                        }
                    } else {
                        val srcDoc = docCache.getOrPut(item.sourceFile.absolutePath) {
                            PDDocument.load(item.sourceFile, memorySetting())
                        }
                        if (item.sourcePageIndex in 0 until srcDoc.numberOfPages) {
                            val importedPage = outDoc.importPage(srcDoc.getPage(item.sourcePageIndex))
                            if (item.rotation != 0) {
                                importedPage.rotation = (importedPage.rotation + item.rotation) % 360
                            }
                        }
                    }
                    onProgress((index + 1).toFloat() / pageItems.size)
                }
                outDoc.save(outputFile)
            }
        } finally {
            docCache.values.forEach { doc ->
                try { doc.close() } catch (_: Exception) {}
            }
        }
        return@withContext outputFile
    }

    /**
     * Applies signatures and text overlays onto the specified pages of [sourceFile],
     * preserving 100% of the original vector content and quality.
     */
    suspend fun applyOverlaysToPdf(
        sourceFile: File,
        overlays: List<PdfAnnotationOverlay>,
        outputFile: File
    ): File = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()

        PDDocument.load(sourceFile, memorySetting()).use { doc ->
            val pageCount = doc.numberOfPages
            val groupedByPage = overlays.groupBy { it.pageIndex }

            for ((pageIdx, pageOverlays) in groupedByPage) {
                if (pageIdx !in 0 until pageCount) continue
                val page = doc.getPage(pageIdx)
                val cropBox = page.cropBox ?: page.mediaBox
                val pageWidth = cropBox.width
                val pageHeight = cropBox.height

                // Append overlay stream so existing contents remain untouched underneath
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (overlay in pageOverlays) {
                        // PDF coordinate system has (0,0) at bottom-left
                        val x = cropBox.lowerLeftX + overlay.normX * pageWidth
                        val w = overlay.normW * pageWidth
                        val h = overlay.normH * pageHeight
                        val y = cropBox.lowerLeftY + pageHeight - (overlay.normY * pageHeight + h)

                        if (overlay.signatureBitmap != null) {
                            // Signature image overlay (Lossless PNG to preserve transparency)
                            val pdImage = LosslessFactory.createFromImage(doc, overlay.signatureBitmap)
                            cs.drawImage(pdImage, x, y, w, h)
                        } else if (!overlay.text.isNullOrBlank()) {
                            // Text box overlay
                            val font = if (overlay.isBold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
                            val fontSize = overlay.textSizePt.coerceIn(8f, 72f)
                            val r = Color.red(overlay.textColor) / 255f
                            val g = Color.green(overlay.textColor) / 255f
                            val b = Color.blue(overlay.textColor) / 255f

                            cs.setNonStrokingColor(r, g, b)
                            cs.beginText()
                            cs.setFont(font, fontSize)
                            // Position text slightly above bottom of box
                            cs.newLineAtOffset(x, y + (h - fontSize) / 2f + 2f)
                            cs.showText(overlay.text)
                            cs.endText()
                        }
                    }
                }
            }
            doc.save(outputFile)
        }
        return@withContext outputFile
    }
}
