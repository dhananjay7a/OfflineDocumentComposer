package com.example.offlinedocumentcomposer

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinedocumentcomposer.domain.detector.*
import com.example.offlinedocumentcomposer.domain.image.PerspectiveCorrector
import com.example.offlinedocumentcomposer.opencv.OpenCvUtils
import com.example.offlinedocumentcomposer.data.model.PageModel
import com.example.offlinedocumentcomposer.domain.pdf.PdfRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.os.ParcelFileDescriptor

/** Native OpenCV tests must run on an Android device or emulator, not mocked JVM bitmaps. */
@RunWith(AndroidJUnit4::class)
class ScannerPipelineTest {
    @Test fun narrowInterfaceBarsAreNotDocuments() {
        val bitmap = Bitmap.createBitmap(800,640,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawRect(40f,180f,750f,220f,Paint().apply { color = Color.DKGRAY })
            drawRect(40f,500f,750f,525f,Paint().apply { color = Color.BLACK })
        }
        try { assertFalse(DocumentDetector().detect(bitmap).success) }
        finally { bitmap.recycle() }
    }
    @Test fun optionalDevicePhotoDiagnostic() {
        val source = InstrumentationRegistry.getArguments().getString("scanSource") ?: return
        require(File(source).name == source)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = BitmapFactory.decodeFile(File(context.filesDir,"scan_session/$source").path)
        requireNotNull(bitmap)
        try {
            val result = DocumentDetector().detect(bitmap)
            android.util.Log.i("ScanDiagnostic", "success=${result.success} confidence=${result.confidence} normalized=${result.corners.map { Pair(it.x/bitmap.width,it.y/bitmap.height) }}")
            assertTrue("Card boundary should be found",result.success)
        } finally { bitmap.recycle() }
    }
    @Test fun blankImageRequiresManualAdjustment() {
        assertTrue(OpenCvUtils.initOpenCV())
        val bitmap = Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try { val result = DocumentDetector().detect(bitmap); assertFalse(result.success); assertEquals(0f,result.confidence,0f) }
        finally { bitmap.recycle() }
    }
    @Test fun rotatedCardsAndPerspectiveDocumentsMatchKnownBoundaries() {
        val fixtures = listOf(
            listOf(PointF(150f,180f),PointF(650f,180f),PointF(650f,490f),PointF(150f,490f)),
            listOf(PointF(290f,60f),PointF(690f,300f),PointF(510f,580f),PointF(110f,340f)),
            listOf(PointF(170f,90f),PointF(620f,135f),PointF(690f,570f),PointF(115f,545f)),
            listOf(PointF(30f,30f),PointF(770f,35f),PointF(770f,600f),PointF(30f,600f))
        )
        for (points in fixtures) {
            val bitmap = Bitmap.createBitmap(800,640,Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(45,60,70))
            val path = Path().apply { moveTo(points[0].x,points[0].y); points.drop(1).forEach { lineTo(it.x,it.y) }; close() }
            canvas.drawPath(path,Paint().apply { color = Color.rgb(240,240,230) })
            canvas.save(); canvas.clipPath(path)
            val text = Paint().apply { color = Color.DKGRAY; textSize = 22f }
            for (y in 240..440 step 42) canvas.drawText("Document boundary fixture",220f,y.toFloat(),text)
            canvas.restore()
            try {
                val detected = DocumentDetector().detect(bitmap)
                assertTrue("Expected a boundary for $points; ${detected.message}",detected.success)
                assertTrue("Boundary IoU: ${iou(points,detected.corners)}",iou(points,detected.corners)>=.90f)
                val cropped = PerspectiveCorrector().correct(bitmap,detected.corners)
                assertTrue(cropped.width>100 && cropped.height>100); cropped.recycle()
            } finally { bitmap.recycle() }
        }
    }
    @Test fun pdfMediaBoxIsA4RatherThanRasterPixelDimensions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir,"physical-a4-test.pdf")
        try {
            assertTrue(PdfRenderer(context).renderToPdf(PageModel(),file).isSuccess)
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { pdf -> pdf.openPage(0).use { page ->
                    assertEquals(595,page.width); assertEquals(842,page.height)
                } }
            }
        } finally { file.delete() }
    }
    private fun iou(a: List<PointF>,b: List<PointF>): Float {
        fun region(p: List<PointF>): Region {
            val path = Path().apply { moveTo(p[0].x,p[0].y); p.drop(1).forEach { lineTo(it.x,it.y) }; close() }
            return Region().apply { setPath(path,Region(0,0,800,640)) }
        }
        fun area(r: Region): Long { val iterator = RegionIterator(r); val rect = Rect(); var sum=0L; while(iterator.next(rect)) sum+=rect.width().toLong()*rect.height(); return sum }
        val intersection = region(a).apply { op(region(b),Region.Op.INTERSECT) }
        val union = region(a).apply { op(region(b),Region.Op.UNION) }
        return area(intersection).toFloat()/area(union).coerceAtLeast(1)
    }
}
