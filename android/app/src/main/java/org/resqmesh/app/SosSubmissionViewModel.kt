package org.resqmesh.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.resqmesh.app.data.LocalCipher
import java.util.UUID

data class SosSubmission(
    val pending: Boolean = false,
    val reportId: String? = null,
    val error: String? = null,
    val clearDraft: Boolean = false,
    val clearMedia: Boolean = false,
)

/** A durable operation token survives process death; recovery never sends a new SOS automatically. */
class SosSubmissionViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(SosSubmission())
    val state = mutableState.asStateFlow()
    private val journal = application.getSharedPreferences("sos-submission", 0)
    private val cipher = LocalCipher()
    init {
        val saved = runCatching { journal.getString("operation", null)?.let { JSONObject(cipher.decrypt(it)) } }.getOrNull()
        if (saved != null) {
            val id = saved.optString("id")
            val clearDraft = saved.optBoolean("clearDraft"); val clearMedia = saved.optBoolean("clearMedia")
            mutableState.value = SosSubmission(pending = true, clearDraft = clearDraft, clearMedia = clearMedia)
            (application as MeshApplication).mesh.recoverSubmission(id) { exists ->
                mutableState.value = SosSubmission(reportId = id.takeIf { exists },
                    error = if (exists) null else "The app was interrupted before this SOS was saved. Review your draft and send when ready.",
                    clearDraft = exists && clearDraft, clearMedia = exists && clearMedia)
            }
        }
    }
    fun send(mesh: MeshController, draft: SosDraft, clearDraft: Boolean) {
        if (state.value.pending || state.value.reportId != null) return
        val id = UUID.randomUUID().toString()
        val operation = JSONObject().put("id", id).put("clearDraft", clearDraft).put("clearMedia", draft.attachments.isNotEmpty())
        val recorded = runCatching { journal.edit().putString("operation", cipher.encrypt(operation.toString())).commit() }.getOrDefault(false)
        if (!recorded) { mutableState.value = SosSubmission(error = "Could not safely save this send operation. Your draft is still here."); return }
        mutableState.value = SosSubmission(pending = true, clearDraft = clearDraft, clearMedia = draft.attachments.isNotEmpty())
        mesh.createWithSubmissionId(draft, id) { reportId, error ->
            mutableState.value = SosSubmission(reportId = reportId,
                error = if (reportId == null) error ?: "Could not save this SOS. Your draft is still here." else null,
                clearDraft = clearDraft, clearMedia = draft.attachments.isNotEmpty())
        }
    }
    fun consumeResult() {
        journal.edit().remove("operation").commit()
        mutableState.value = SosSubmission()
    }
}
