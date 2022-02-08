package org.wikipedia.page

import android.text.SpannableStringBuilder
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.dataclient.mwapi.MwQueryPage.Revision
import org.wikipedia.dataclient.restbase.DiffResponse
import org.wikipedia.dataclient.restbase.EditCount
import org.wikipedia.util.Resource
import org.wikipedia.util.Resource.Success
import org.wikipedia.util.log.L
import java.nio.charset.StandardCharsets

class EditHistoryListViewModel : ViewModel() {

    val editHistoryListData = MutableLiveData<Resource<List<Revision>>>()
    val editCountsData = MutableLiveData<Resource<List<EditCount>>>()

    private val typesOfEdits = listOf(EditCount.EDIT_TYPE_EDITS, EditCount.EDIT_TYPE_BOT, EditCount.EDIT_TYPE_ANONYMOUS)

    private val handler = CoroutineExceptionHandler { _, throwable ->
        L.e(throwable)
    }

    fun fetchData(pageTitle: PageTitle) {
        viewModelScope.launch(handler) {
            withContext(Dispatchers.IO) {
                val response = ServiceFactory.get(WikiSite.forLanguageCode(pageTitle.wikiSite.languageCode)).getEditHistoryDetails(pageTitle.prefixedText)
                val revisions = response.query!!.pages?.get(0)?.revisions
                editHistoryListData.postValue(Success(revisions!!))
            }
        }
    }

    suspend fun fetchEditDetails(languageCode: String,
                                 olderRevisionId: Long,
                                 revisionId: Long): EditDetails {
        val response: DiffResponse = ServiceFactory.getCoreRest(WikiSite.forLanguageCode(languageCode)).getEditDiff(olderRevisionId, revisionId)
        var diffSize = 0
        val spannableString = SpannableStringBuilder()
        val changeText = SpannableStringBuilder()
       kotlin.runCatching {
           for (diff in response.diff) {
               val prefixLength = spannableString.length
               spannableString.append(diff.text.ifEmpty { "\n" })
               when (diff.type) {
                   DiffResponse.DIFF_TYPE_LINE_ADDED -> {
                       diffSize += diff.text.length + 1
                       changeText.append(spannableString.subSequence(prefixLength, prefixLength + diff.text.length))
                   }
                   DiffResponse.DIFF_TYPE_LINE_REMOVED -> {
                       diffSize -= diff.text.length + 1
                       changeText.append(spannableString.subSequence(prefixLength, prefixLength + diff.text.length))
                   }
                   DiffResponse.DIFF_TYPE_PARAGRAPH_MOVED_FROM -> {
                       diffSize -= diff.text.length + 1
                       changeText.append(spannableString.subSequence(prefixLength, prefixLength + diff.text.length))
                   }
                   DiffResponse.DIFF_TYPE_PARAGRAPH_MOVED_TO -> {
                       diffSize += diff.text.length + 1
                       changeText.append(spannableString.subSequence(prefixLength, prefixLength + diff.text.length))
                   }
               }

               if (diff.highlightRanges.isNotEmpty()) {
                   for (editRange in diff.highlightRanges) {
                       val indices = utf8Indices(diff.text)
                       val editRangeStart = indices[editRange.start]
                       val editRangeEnd = if (editRange.start + editRange.length < indices.size) indices[editRange.start + editRange.length] else indices[indices.size - 1]
                       if (editRange.type == DiffResponse.HIGHLIGHT_TYPE_ADD) {
                           diffSize += editRange.length
                       } else {
                           diffSize -= editRange.length
                       }
                       changeText.append(spannableString.subSequence(prefixLength + editRangeStart, prefixLength + editRangeEnd))
                   }
               }
               spannableString.append("\n")
           }
       }
        return EditDetails(diffSize, changeText)
    }

    private fun utf8Indices(s: String): IntArray {
        val indices = IntArray(s.toByteArray(StandardCharsets.UTF_8).size)
        var ptr = 0
        var count = 0
        for (i in s.indices) {
            val c = s.codePointAt(i)
            when {
                c <= 0x7F -> count = 1
                c <= 0x7FF -> count = 2
                c <= 0xFFFF -> count = 3
                c <= 0x1FFFFF -> count = 4
            }
            for (j in 0 until count) {
                indices[ptr++] = i
            }
        }
        return indices
    }

    fun fetchEditCounts(pageTitle: PageTitle) {
        viewModelScope.launch(handler) {
            withContext(Dispatchers.IO) {
                val list = mutableListOf<EditCount>()
                typesOfEdits.forEach {
                    val response = ServiceFactory.getCoreRest(pageTitle.wikiSite).getEditCount(pageTitle.prefixedText, it)
                    list.add(response)
                }
                editCountsData.postValue(Success(list))
            }
        }
    }

    fun fetchCreatedDate(pageTitle: PageTitle) {
        // TODO: reduce API calls by getting the latest edit history from the API?
    }
    
    class EditDetails(val diffSize: Int, val text: CharSequence)
}
