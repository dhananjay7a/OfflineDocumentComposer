package com.example.offlinedocumentcomposer

import com.example.offlinedocumentcomposer.domain.resizer.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class ImageResizerTest {

    @Test
    fun `test standard presets exist and have valid properties`() {
        val presets = StandardImagePresets.PRESETS
        assertTrue(presets.isNotEmpty())

        val examPhoto = presets.first { it.id == "govt_exam_photo" }
        assertEquals(200f, examPhoto.width, 0.01f)
        assertEquals(230f, examPhoto.height, 0.01f)
        assertEquals(50, examPhoto.targetMaxKb)
        assertEquals(PresetCategory.EXAM_GOV, examPhoto.category)

        val examSig = presets.first { it.id == "govt_exam_sig" }
        assertEquals(140f, examSig.width, 0.01f)
        assertEquals(60f, examSig.height, 0.01f)
        assertEquals(20, examSig.targetMaxKb)

        val instaPost = presets.first { it.id == "insta_square" }
        assertEquals(1080f, instaPost.width, 0.01f)
        assertEquals(1080f, instaPost.height, 0.01f)

        val fhd = presets.first { it.id == "web_fhd" }
        assertEquals(1920f, fhd.width, 0.01f)
        assertEquals(1080f, fhd.height, 0.01f)
    }

    @Test
    fun `test pixel dimension resolution`() {
        val config = ResizeConfiguration(
            unit = ResizeUnit.PIXELS,
            width = 800f,
            height = 600f
        )
        val (w, h) = config.resolvePixelDimensions(1920, 1080)
        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test
    fun `test percentage dimension resolution`() {
        val config = ResizeConfiguration(
            unit = ResizeUnit.PERCENTAGE,
            scalePercent = 50f
        )
        val (w, h) = config.resolvePixelDimensions(1920, 1080)
        assertEquals(960, w)
        assertEquals(540, h)

        val configQuarter = ResizeConfiguration(
            unit = ResizeUnit.PERCENTAGE,
            scalePercent = 25f
        )
        val (w25, h25) = configQuarter.resolvePixelDimensions(1000, 800)
        assertEquals(250, w25)
        assertEquals(200, h25)
    }

    @Test
    fun `test physical millimeter dimension resolution at 300 DPI`() {
        // Indian Passport: 35 mm x 45 mm at 300 DPI
        // 35 / 25.4 * 300 = 413.38 -> 413 px
        // 45 / 25.4 * 300 = 531.49 -> 531 px
        val config = ResizeConfiguration(
            unit = ResizeUnit.MILLIMETERS,
            width = 35f,
            height = 45f,
            dpi = 300
        )
        val (w, h) = config.resolvePixelDimensions(1000, 1000)
        assertEquals((35f / 25.4f * 300f).roundToInt(), w)
        assertEquals((45f / 25.4f * 300f).roundToInt(), h)
        assertEquals(413, w)
        assertEquals(531, h)
    }

    @Test
    fun `test physical inch dimension resolution`() {
        // 2 x 2 inch (US visa standard) at 300 DPI = 600 x 600 px
        val config = ResizeConfiguration(
            unit = ResizeUnit.INCHES,
            width = 2f,
            height = 2f,
            dpi = 300
        )
        val (w, h) = config.resolvePixelDimensions(1000, 1000)
        assertEquals(600, w)
        assertEquals(600, h)
    }

    @Test
    fun `test image source meta file size formatting`() {
        val mockUri = org.mockito.Mockito.mock(android.net.Uri::class.java)
        val metaBytes = ImageSourceMeta(
            uri = mockUri,
            originalWidth = 500,
            originalHeight = 500,
            fileSizeBytes = 512L
        )
        assertEquals("512 B", metaBytes.formattedFileSize)

        val metaKb = ImageSourceMeta(
            uri = mockUri,
            originalWidth = 1000,
            originalHeight = 1000,
            fileSizeBytes = 45 * 1024L
        )
        assertEquals("45.0 KB", metaKb.formattedFileSize)

        val metaMb = ImageSourceMeta(
            uri = mockUri,
            originalWidth = 4000,
            originalHeight = 3000,
            fileSizeBytes = (2.5 * 1024 * 1024).toLong()
        )
        assertEquals("2.50 MB", metaMb.formattedFileSize)
    }

    @Test
    fun `test output format properties`() {
        assertEquals("jpg", OutputFormat.JPEG.extension)
        assertEquals("image/jpeg", OutputFormat.JPEG.mimeType)

        assertEquals("png", OutputFormat.PNG.extension)
        assertEquals("image/png", OutputFormat.PNG.mimeType)

        assertEquals("webp", OutputFormat.WEBP.extension)
        assertEquals("image/webp", OutputFormat.WEBP.mimeType)
    }
}
