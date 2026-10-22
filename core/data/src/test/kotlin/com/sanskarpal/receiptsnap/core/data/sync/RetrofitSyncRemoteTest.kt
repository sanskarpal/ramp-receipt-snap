package com.sanskarpal.receiptsnap.core.data.sync

import com.google.common.truth.Truth.assertThat
import com.sanskarpal.receiptsnap.core.data.network.ReceiptSnapApi
import com.sanskarpal.receiptsnap.core.data.network.ReceiptUploadMetadata
import com.sanskarpal.receiptsnap.core.model.Decision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class RetrofitSyncRemoteTest {
    private val server = MockWebServer()
    private lateinit var remote: RetrofitSyncRemote
    private val image = File.createTempFile("receipt", ".jpg").apply { writeBytes(ByteArray(64) { 1 }) }
    private val upload = UploadRequest(ReceiptUploadMetadata("r1", "Joe's", 3915, "2026-10-18", "txn_1"), image.path)

    @Before
    fun setUp() {
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ReceiptSnapApi::class.java)
        remote = RetrofitSyncRemote(api)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `sends the idempotency key and parses the result`() = runTest {
        server.enqueue(MockResponse().setBody("""{"receipt_id":"r1","remote_id":"x","match":{"transaction_id":"txn_1","status":"ACCEPTED"}}"""))
        val result = remote.uploadReceipt("key-123", upload)
        assertThat(result.match.status).isEqualTo("ACCEPTED")
        val req = server.takeRequest()
        assertThat(req.getHeader("Idempotency-Key")).isEqualTo("key-123")
        assertThat(req.body.readUtf8()).contains("\"proposed_transaction_id\":\"txn_1\"")
    }

    @Test
    fun `5xx and dropped connections are retryable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(runCatching { remote.uploadReceipt("k", upload) }.exceptionOrNull()).isInstanceOf(SyncError.Retryable::class.java)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY).setBody("""{"receipt_id":"r1","remote_id":"x","match":{"status":"NONE"}}"""))
        assertThat(runCatching { remote.uploadReceipt("k", upload) }.exceptionOrNull()).isInstanceOf(SyncError.Retryable::class.java)
    }

    @Test
    fun `409 becomes a conflict with who decided`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Already decided","decided_by":"Priya (Finance)","status":"APPROVED"}"""))
        val e = runCatching { remote.decide("k", "a1", Decision.APPROVE, 1) }.exceptionOrNull() as SyncError.Conflict
        assertThat(e.decidedBy).isEqualTo("Priya (Finance)")
    }

    @Test
    fun `other 4xx are permanent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))
        val e = runCatching { remote.confirmMatch("k", "r1", "t1") }.exceptionOrNull()
        assertThat(e).isInstanceOf(SyncError.Permanent::class.java)
        assertThat((e as SyncError.Permanent).code).isEqualTo(400)
    }
}
