package com.sanskarpal.receiptsnap.core.data.sync

import com.sanskarpal.receiptsnap.core.data.network.ApprovalDto
import com.sanskarpal.receiptsnap.core.data.network.ConflictBody
import com.sanskarpal.receiptsnap.core.data.network.DecisionRequest
import com.sanskarpal.receiptsnap.core.data.network.MatchOutcome
import com.sanskarpal.receiptsnap.core.data.network.MatchRequest
import com.sanskarpal.receiptsnap.core.data.network.ReceiptSnapApi
import com.sanskarpal.receiptsnap.core.data.network.ReceiptUploadResult
import com.sanskarpal.receiptsnap.core.model.Decision
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException

/** Maps HTTP outcomes onto the outbox's three cases: retry, conflict, give up. */
class RetrofitSyncRemote(private val api: ReceiptSnapApi, private val json: Json = Json { ignoreUnknownKeys = true }) : SyncRemote {

    override suspend fun uploadReceipt(idempotencyKey: String, request: UploadRequest): ReceiptUploadResult = call {
        val file = File(request.imagePath)
        val metadata = json.encodeToString(request.metadata).toRequestBody("application/json".toMediaType())
        val image = MultipartBody.Part.createFormData("image", file.name, file.asRequestBody("image/jpeg".toMediaType()))
        api.uploadReceipt(idempotencyKey, metadata, image)
    }

    override suspend fun confirmMatch(idempotencyKey: String, receiptId: String, transactionId: String?): MatchOutcome = call {
        api.confirmMatch(idempotencyKey, receiptId, MatchRequest(transactionId))
    }

    override suspend fun decide(idempotencyKey: String, approvalId: String, decision: Decision, clientVersion: Int): ApprovalDto = call {
        api.decide(idempotencyKey, approvalId, DecisionRequest(decision.name, clientVersion))
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val code = e.code()
        when {
            code == 409 -> {
                val body = runCatching { json.decodeFromString<ConflictBody>(e.response()?.errorBody()?.string().orEmpty()) }.getOrNull()
                throw SyncError.Conflict(body?.decidedBy, body?.status)
            }
            code >= 500 || code == 408 || code == 429 -> throw SyncError.Retryable("HTTP $code")
            else -> throw SyncError.Permanent(code, e.message())
        }
    } catch (e: IOException) {
        // timeouts, dropped connections, airplane mode, and responses cut off mid-body
        throw SyncError.Retryable(e.message ?: e.javaClass.simpleName)
    } catch (e: SerializationException) {
        // a truncated body that still parses as far as the stream went is a broken response, not bad data
        throw SyncError.Retryable("Malformed response: ${e.message}")
    }
}
