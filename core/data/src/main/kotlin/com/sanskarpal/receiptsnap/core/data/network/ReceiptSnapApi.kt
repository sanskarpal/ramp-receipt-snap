package com.sanskarpal.receiptsnap.core.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** Hand-written to match backend/openapi.yaml exactly (see README for why not generated). */
interface ReceiptSnapApi {
    @GET("transactions")
    suspend fun transactions(
        @Query("updated_since") updatedSince: String?,
        @Query("page_token") pageToken: String?,
        @Query("limit") limit: Int = 200,
    ): TransactionsPage

    @Multipart
    @POST("receipts")
    suspend fun uploadReceipt(
        @Header("Idempotency-Key") key: String,
        @Part("metadata") metadata: RequestBody,
        @Part image: MultipartBody.Part,
    ): ReceiptUploadResult

    @POST("receipts/{id}/match")
    suspend fun confirmMatch(
        @Header("Idempotency-Key") key: String,
        @Path("id") receiptId: String,
        @Body body: MatchRequest,
    ): MatchOutcome

    @GET("approvals")
    suspend fun approvals(): ApprovalsResponse

    @POST("approvals/{id}/decision")
    suspend fun decide(
        @Header("Idempotency-Key") key: String,
        @Path("id") approvalId: String,
        @Body body: DecisionRequest,
    ): ApprovalDto
}

@Serializable
data class TransactionDto(
    val id: String,
    @SerialName("merchant_descriptor") val merchantDescriptor: String,
    @SerialName("amount_cents") val amountCents: Long,
    val currency: String,
    @SerialName("authorized_at") val authorizedAt: String,
    @SerialName("card_last4") val cardLast4: String,
    @SerialName("receipt_id") val receiptId: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class TransactionsPage(
    val transactions: List<TransactionDto>,
    @SerialName("next_page_token") val nextPageToken: String? = null,
)

@Serializable
data class ReceiptUploadMetadata(
    val id: String,
    val merchant: String?,
    @SerialName("total_cents") val totalCents: Long?,
    val date: String?,
    @SerialName("proposed_transaction_id") val proposedTransactionId: String?,
)

@Serializable
data class MatchOutcome(
    @SerialName("transaction_id") val transactionId: String? = null,
    val status: String, // ACCEPTED | REJECTED | NONE
    val reason: String? = null,
)

@Serializable
data class ReceiptUploadResult(
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("remote_id") val remoteId: String,
    val match: MatchOutcome,
)

@Serializable
data class MatchRequest(@SerialName("transaction_id") val transactionId: String?)

@Serializable
data class ApprovalDto(
    val id: String,
    @SerialName("requester_name") val requesterName: String,
    @SerialName("amount_cents") val amountCents: Long,
    val merchant: String,
    val memo: String,
    val status: String,
    val version: Int,
    @SerialName("decided_by") val decidedBy: String? = null,
)

@Serializable
data class ApprovalsResponse(val approvals: List<ApprovalDto>)

@Serializable
data class DecisionRequest(val decision: String, @SerialName("client_version") val clientVersion: Int)

@Serializable
data class ConflictBody(val error: String? = null, @SerialName("decided_by") val decidedBy: String? = null, val status: String? = null)
