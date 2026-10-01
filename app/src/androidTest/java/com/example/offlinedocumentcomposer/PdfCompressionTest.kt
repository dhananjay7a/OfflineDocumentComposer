package com.example.offlinedocumentcomposer

import android.content.Context
import android.graphics.Bitmap
import com.example.offlinedocumentcomposer.domain.pdf.*
import com.example.offlinedocumentcomposer.data.model.PageModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfCompressionTest {
    private lateinit var context: Context
    @Before fun setup() { context = InstrumentationRegistry.getInstrumentation().targetContext; PDFBoxResourceLoader.init(context) }
    private fun input(): File {
        val file = File.createTempFile("fixture", ".pdf",context.cacheDir)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            val bitmap = Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888)
            val random = java.util.Random(12)
            val pixels = IntArray(1200*800) { 0xff000000.toInt() or (random.nextInt(100)+100).let { (it shl 16) or (it shl 8) or it } }
            bitmap.setPixels(pixels,0,1200,0,0,1200,800)
            val image = LosslessFactory.createFromImage(doc,bitmap); bitmap.recycle()
            PDPageContentStream(doc,page).use {
                it.drawImage(image,40f,100f,400f,270f)
                it.beginText(); it.setFont(PDType1Font.HELVETICA,12f); it.newLineAtOffset(40f,750f)
                it.showText("Selectable text survives compression"); it.endText()
            }
            page.annotations.add(PDAnnotationLink().apply { rectangle = PDRectangle(40f,700f,120f,20f); action = PDActionURI().apply { uri = "https://example.com" } })
            val form = PDAcroForm(doc); doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(com.tom_roush.pdfbox.cos.COSName.getPDFName("Helv"),PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 12 Tf 0 g"
            form.fields.add(PDTextField(form).apply { partialName = "Name"; value = "Ada" })
            doc.save(file)
        }
        return file
    }
    @Test fun compressionPreservesTextLinksFormsAndPageDimensions() = runBlocking {
        val input = input(); val output = File.createTempFile("test-compressed.pdf", ".pdf", context.cacheDir).apply { delete() }
        val result = PdfCompressionService(context).compress(input,output,PdfQuality.STRONG,null) {}
        assertTrue(result.outputBytes <= result.originalBytes)
        PDDocument.load(output).use { doc ->
            assertEquals(1,doc.numberOfPages)
            assertTrue(PDFTextStripper().getText(doc).contains("Selectable text survives compression"))
            assertEquals("Ada",doc.documentCatalog.acroForm.getField("Name").valueAsString)
            assertEquals("https://example.com",(doc.getPage(0).annotations.filterIsInstance<PDAnnotationLink>().first().action as PDActionURI).uri)
            assertEquals(PDRectangle.A4.width,doc.getPage(0).mediaBox.width,.01f)
        }
    }
    @Test fun impossibleTargetIsReportedWithoutLargerOutput() = runBlocking {
        val input = input(); val output = File.createTempFile("test-tiny.pdf", ".pdf", context.cacheDir).apply { delete() }
        val result = PdfCompressionService(context).compress(input,output,PdfQuality.LIGHT,1) {}
        assertEquals(false,result.targetReached)
        assertTrue(output.length() <= input.length())
    }
    @Test fun malformedAndSignedInputsLeaveNoOutput() = runBlocking {
        val input = File(context.cacheDir,"bad.pdf").apply { writeText("not a PDF") }
        val output = File.createTempFile("test-bad_output.pdf", ".pdf", context.cacheDir).apply { delete() }
        assertTrue(runCatching { PdfCompressionService(context).compress(input,output,PdfQuality.BALANCED,null) {} }.isFailure)
        assertFalse(output.exists())
        PDDocument().use { doc -> doc.addPage(PDPage()); doc.addSignature(PDSignature()); doc.save(input) }
        assertTrue(runCatching { PdfCompressionService(context).compress(input,output,PdfQuality.BALANCED,null) {} }.isFailure)
        assertFalse(output.exists())
    }
    @Test fun composerPdfUsesPhysicalA4Dimensions() = runBlocking {
        val output = File.createTempFile("test-a4.pdf", ".pdf", context.cacheDir).apply { delete() }
        val result = PdfRenderer(context).renderToPdf(PageModel(),output)
        assertTrue(result.isSuccess)
        PDDocument.load(output).use { doc ->
            assertEquals(595f,doc.getPage(0).mediaBox.width,1f)
            assertEquals(842f,doc.getPage(0).mediaBox.height,1f)
        }
    }
}
