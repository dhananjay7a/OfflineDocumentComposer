package com.example.offlinedocumentcomposer.domain.pdf

import android.content.Context
import com.example.offlinedocumentcomposer.data.model.ScanPage
import com.example.offlinedocumentcomposer.data.repository.ScanRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.min

data class ScanExportOptions(val letter: Boolean = false, val landscape: Boolean = false,
    val marginMm: Float = 10f, val quality: PdfQuality = PdfQuality.LIGHT)

class ScanPdfExporter(private val context: Context) {
    suspend fun export(pages: List<ScanPage>, options: ScanExportOptions, output: File,
        progress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        require(pages.isNotEmpty()) { "Add at least one page" }
        require(options.marginMm in 0f..40f) { "Margin must be between 0 and 40 mm" }
        PDFBoxResourceLoader.init(context)
        val pending = File(output.parentFile,output.name+".partial")
        try {
            PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { document ->
                val portrait = if (options.letter) PDRectangle.LETTER else PDRectangle.A4
                val box = if (options.landscape) PDRectangle(portrait.height,portrait.width) else portrait
                val margin = options.marginMm*72f/25.4f
                val repository = ScanRepository(context)
                for ((i,page) in pages.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    progress("Exporting page ${i+1}/${pages.size}")
                    val bitmap = repository.render(page, (maxOf(box.width,box.height)/72*options.quality.dpi).toInt())
                    try {
                        val pdfPage = PDPage(box); document.addPage(pdfPage)
                        val image = JPEGFactory.createFromImage(document,bitmap,options.quality.jpeg)
                        val scale = min((box.width-2*margin)/bitmap.width,(box.height-2*margin)/bitmap.height)
                        val w = bitmap.width*scale; val h = bitmap.height*scale
                        PDPageContentStream(document,pdfPage).use { it.drawImage(image,(box.width-w)/2,(box.height-h)/2,w,h) }
                    } finally { bitmap.recycle() }
                }
                currentCoroutineContext().ensureActive()
                document.save(pending)
            }
            currentCoroutineContext().ensureActive()
            check(pending.renameTo(output)) { "Could not finalize PDF" }
            output
        } finally { pending.delete() }
    }
}
