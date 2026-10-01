package com.example.offlinedocumentcomposer.presentation.scanner

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinedocumentcomposer.data.model.*
import com.example.offlinedocumentcomposer.data.repository.ScanRepository
import com.example.offlinedocumentcomposer.domain.pdf.*
import com.example.offlinedocumentcomposer.presentation.tools.DocumentFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ScanState(val session: ScanSession = ScanSession(), val busy: Boolean = true,
    val progress: String = "Loading session", val error: String? = null, val output: File? = null)

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    val repository = ScanRepository(application)
    private val mutable = MutableStateFlow(ScanState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    init {
        job = viewModelScope.launch {
            try { mutable.value = ScanState(session = withContext(Dispatchers.IO) { repository.load() }, busy = false) }
            catch (e: Exception) { mutable.value = ScanState(busy = false, error = "Could not restore session: ${e.message}") }
        }
    }
    private fun work(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null, output = null)
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: "Operation failed") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private suspend fun commit(pages: List<ScanPage>) {
        val session = ScanSession(pages)
        withContext(NonCancellable + Dispatchers.IO) { repository.save(session) }
        mutable.value = mutable.value.copy(session = session)
    }
    fun add(uris: List<Uri>, replaceId: String? = null) = work {
        for ((i,uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(progress = "Importing image ${i+1}/${uris.size}")
            // Finish one page atomically. Cancellation stops before the next page.
            withContext(NonCancellable) {
                val page = withContext(Dispatchers.IO) { repository.import(uri) }
                val old = mutable.value.session.pages.firstOrNull { it.id == replaceId }
                try { commit(if (old == null) mutable.value.session.pages+page else mutable.value.session.pages.map { if (it.id == old.id) page else it }) }
                catch (e: Exception) { withContext(Dispatchers.IO) { repository.deleteFiles(page) }; throw e }
                withContext(Dispatchers.IO) {
                    if (old != null) repository.deleteFiles(old)
                    if (uri.scheme == "file") {
                        val file = uri.path?.let { File(it) }
                        if (file != null && file.parentFile?.canonicalFile == getApplication<Application>().cacheDir.canonicalFile && file.name.startsWith("scan_capture_")) file.delete()
                    }
                }
            }
        }
    }
    fun update(page: ScanPage) = work {
        val updated = page.copy(revision = page.revision+1)
        withContext(Dispatchers.IO) { repository.makeThumbnail(updated) }
        commit(mutable.value.session.pages.map { if (it.id == page.id) updated else it })
        withContext(Dispatchers.IO) { repository.clearOldThumbnails(updated) }
    }
    fun delete(id: String) = work {
        val old = mutable.value.session.pages.first { it.id == id }
        commit(mutable.value.session.pages.filterNot { it.id == id })
        withContext(Dispatchers.IO) { repository.deleteFiles(old) }
    }
    fun move(id: String, offset: Int) = work {
        val pages = mutable.value.session.pages.toMutableList()
        val from = pages.indexOfFirst { it.id == id }; val to = from+offset
        if (from >= 0 && to in pages.indices) { val page = pages.removeAt(from); pages.add(to,page); commit(pages) }
    }
    fun clear() = work {
        val pages = mutable.value.session.pages
        commit(emptyList())
        withContext(Dispatchers.IO) { pages.forEach(repository::deleteFiles) }
    }
    fun export(name: String, options: ScanExportOptions) = work {
        require(mutable.value.session.pages.none { it.needsReview }) { "Review uncertain crop boundaries before exporting." }
        val output = ScanPdfExporter(getApplication()).export(mutable.value.session.pages, options,
            DocumentFiles.output(getApplication(),name)) { mutable.value = mutable.value.copy(progress = it) }
        mutable.value = mutable.value.copy(output = output)
    }
    fun reportError(message: String) { mutable.value = mutable.value.copy(error = message) }
    fun cancel() { job?.cancel() }
}
