package com.example.offlinedocumentcomposer

import android.graphics.Bitmap
import com.example.offlinedocumentcomposer.domain.passport.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class PassportPhotoTest {

    @Test
    fun `test passport standards have correct dimensions and aspect ratios`() {
        val indian = PassportPhotoStandard.INDIAN_PASSPORT
        assertEquals(35f, indian.widthMm, 0.01f)
        assertEquals(45f, indian.heightMm, 0.01f)
        assertEquals(35f / 45f, indian.aspectRatio, 0.01f)

        val pan = PassportPhotoStandard.PAN_CARD
        assertEquals(25f, pan.widthMm, 0.01f)
        assertEquals(35f, pan.heightMm, 0.01f)

        val stamp = PassportPhotoStandard.STAMP_SIZE
        assertEquals(20f, stamp.widthMm, 0.01f)
        assertEquals(25f, stamp.heightMm, 0.01f)

        val usVisa = PassportPhotoStandard.US_VISA
        assertEquals(51f, usVisa.widthMm, 0.01f)
        assertEquals(51f, usVisa.heightMm, 0.01f)
        assertEquals(1.0f, usVisa.aspectRatio, 0.01f)
    }

    @Test
    fun `test sheet config orientations`() {
        val configPortrait = PassportSheetConfig(
            sheetSize = PassportSheetSize.PHOTO_4X6,
            orientation = SheetOrientation.PORTRAIT
        )
        assertEquals(101.6f, configPortrait.effectiveSheetWidthMm, 0.1f)
        assertEquals(152.4f, configPortrait.effectiveSheetHeightMm, 0.1f)

        val configLandscape = PassportSheetConfig(
            sheetSize = PassportSheetSize.PHOTO_4X6,
            orientation = SheetOrientation.LANDSCAPE
        )
        assertEquals(152.4f, configLandscape.effectiveSheetWidthMm, 0.1f)
        assertEquals(101.6f, configLandscape.effectiveSheetHeightMm, 0.1f)
    }

    @Test
    fun `test passport layout calculates valid grid on 4x6 sheet`() {
        val mockBitmap = mock(Bitmap::class.java)
        val item = PassportPhotoItem(
            id = "photo_1",
            originalBitmap = mockBitmap,
            croppedBitmap = mockBitmap,
            copies = 8,
            standard = PassportPhotoStandard.INDIAN_PASSPORT
        )

        val config = PassportSheetConfig(
            sheetSize = PassportSheetSize.PHOTO_4X6,
            orientation = SheetOrientation.PORTRAIT,
            marginMm = 5f,
            gapMm = 3f
        )

        val layout = PassportSheetRenderer.calculateLayout(listOf(item), config)

        assertTrue("Should fit at least 2 columns", layout.columns >= 2)
        assertTrue("Should fit at least 3 rows", layout.rows >= 3)
        assertEquals(8, layout.slots.size)

        // All slots should be within sheet bounds
        for (slot in layout.slots) {
            assertTrue("Slot left within sheet", slot.xMm >= config.marginMm)
            assertTrue("Slot right within sheet", slot.xMm + slot.widthMm <= config.effectiveSheetWidthMm - config.marginMm + 0.1f)
            assertTrue("Slot top within sheet", slot.yMm >= config.marginMm)
            assertTrue("Slot bottom within sheet", slot.yMm + slot.heightMm <= config.effectiveSheetHeightMm - config.marginMm + 0.1f)
        }
    }

    @Test
    fun `test multiple photos tile with individual copy counts`() {
        val mockBitmap1 = mock(Bitmap::class.java)
        val mockBitmap2 = mock(Bitmap::class.java)

        val personA = PassportPhotoItem(
            id = "person_a",
            originalBitmap = mockBitmap1,
            croppedBitmap = mockBitmap1,
            copies = 4,
            standard = PassportPhotoStandard.INDIAN_PASSPORT
        )
        val personB = PassportPhotoItem(
            id = "person_b",
            originalBitmap = mockBitmap2,
            croppedBitmap = mockBitmap2,
            copies = 2,
            standard = PassportPhotoStandard.INDIAN_PASSPORT
        )

        val config = PassportSheetConfig(
            sheetSize = PassportSheetSize.PHOTO_4X6,
            orientation = SheetOrientation.PORTRAIT
        )

        val layout = PassportSheetRenderer.calculateLayout(listOf(personA, personB), config)

        assertEquals("Total copies should be 4 + 2 = 6", 6, layout.slots.size)
        assertEquals(4, layout.slots.count { it.photoItem.id == "person_a" })
        assertEquals(2, layout.slots.count { it.photoItem.id == "person_b" })
    }

    @Test
    fun `test A4 sheet capacity`() {
        val mockBitmap = mock(Bitmap::class.java)
        val item = PassportPhotoItem(
            id = "photo_a4",
            originalBitmap = mockBitmap,
            croppedBitmap = mockBitmap,
            copies = 30,
            standard = PassportPhotoStandard.INDIAN_PASSPORT
        )

        val config = PassportSheetConfig(
            sheetSize = PassportSheetSize.A4,
            orientation = SheetOrientation.PORTRAIT,
            marginMm = 10f,
            gapMm = 3f
        )

        val layout = PassportSheetRenderer.calculateLayout(listOf(item), config)
        assertTrue("A4 sheet should hold at least 25 passport photos", layout.maxSlotsPerSheet >= 25)
        assertEquals(30, layout.slots.size)
    }
}
