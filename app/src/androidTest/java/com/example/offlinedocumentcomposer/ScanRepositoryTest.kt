package com.example.offlinedocumentcomposer

import com.example.offlinedocumentcomposer.data.model.*
import com.example.offlinedocumentcomposer.data.repository.ScanRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScanRepositoryTest {
    @Test fun restoresPageOrderAndNonDestructiveEdits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir,"scan-repository-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val source = File(directory,"test.source").apply { writeBytes(byteArrayOf(1)) }
        val points = listOf(ScanPoint(0f,0f),ScanPoint(1f,0f),ScanPoint(1f,1f),ScanPoint(0f,1f))
        val page = ScanPage(source = source.absolutePath,corners = points,rotation = 90,filter = ScanFilter.BLACK_WHITE,brightness = 12f,contrast = 20f,needsReview = true)
        ScanRepository(context, directory).save(ScanSession(listOf(page,page.copy(id = "second"))))
        val restored = ScanRepository(context, directory).load()
        assertEquals(listOf(page.id,"second"),restored.pages.map { it.id })
        assertEquals(page,restored.pages.first())
        assertArrayEquals(byteArrayOf(1),source.readBytes())
    }
}
