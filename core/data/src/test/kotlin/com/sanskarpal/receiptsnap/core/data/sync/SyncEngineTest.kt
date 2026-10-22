package com.sanskarpal.receiptsnap.core.data.sync

import com.google.common.truth.Truth.assertThat
import com.sanskarpal.receiptsnap.core.data.network.ApprovalDto
import com.sanskarpal.receiptsnap.core.model.Decision
import com.sanskarpal.receiptsnap.core.model.OutboxState
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random

class SyncEngineTest {

    @Test
    fun `drains uploads in order and deletes images only after the server has them`() = runTest {
        val server = FakeServer(Random(1))
        val store = FakeStore(server, Random(1))
        repeat(3) { store.addReceipt("r$it") }

        assertThat(SyncEngine(store, server).drain().outcome).isEqualTo(SyncEngine.Outcome.DRAINED)
        assertThat(server.receipts.keys).containsExactly("r0", "r1", "r2").inOrder()
        assertThat(store.images).isEmpty()
        assertThat(store.deletedBeforeServerHadIt).isEmpty()
    }

    @Test
    fun `a dropped response is retried with the same key and creates no duplicate`() = runTest {
        val server = FakeServer(Random(2), dropRate = 1.0)
        val store = FakeStore(server, Random(2))
        store.addReceipt("r0")
        val engine = SyncEngine(store, server)

        assertThat(engine.drain().outcome).isEqualTo(SyncEngine.Outcome.RETRY_LATER)
        assertThat(store.images).contains("r0") // response lost, so the image must stay
        server.dropRate = 0.0
        assertThat(engine.drain().outcome).isEqualTo(SyncEngine.Outcome.DRAINED)
        assertThat(server.receipts).containsExactly("r0", 1)
    }

    @Test
    fun `a retryable failure stops the drain so later operations keep their order`() = runTest {
        val server = FakeServer(Random(3), offline = true)
        val store = FakeStore(server, Random(3))
        repeat(3) { store.addReceipt("r$it") }
        SyncEngine(store, server).drain()
        assertThat(server.requests).isEqualTo(1)
        assertThat(store.ops.map { it.state }).containsExactly(OutboxState.PENDING, OutboxState.PENDING, OutboxState.PENDING)
        assertThat(store.ops[0].attempts).isEqualTo(1)
    }

    @Test
    fun `an approval someone else already decided rolls back the local decision`() = runTest {
        val server = FakeServer(Random(4))
        server.approvals["a1"] = ApprovalDto("a1", "Ava", 100, "United", "", "APPROVED", 2, "Priya (Finance)")
        val store = FakeStore(server, Random(4))
        store.enqueue("op-a1", OutboxPayload.ApprovalDecision("a1", Decision.DENY, 1))

        SyncEngine(store, server).drain()
        assertThat(store.approvalConflicts).containsExactly("a1", "Priya (Finance)")
        assertThat(store.ops.single().state).isEqualTo(OutboxState.DONE)
    }

    @Test
    fun `a missing local image is a permanent failure that does not block the queue`() = runTest {
        val server = FakeServer(Random(5))
        val store = FakeStore(server, Random(5))
        store.enqueue("op-ghost", OutboxPayload.UploadReceipt("ghost"))
        store.addReceipt("r1")

        assertThat(SyncEngine(store, server).drain().outcome).isEqualTo(SyncEngine.Outcome.DRAINED)
        assertThat(store.ops.map { it.state }).containsExactly(OutboxState.FAILED_PERMANENT, OutboxState.DONE).inOrder()
        assertThat(server.receipts.keys).containsExactly("r1")
    }

    @Test
    fun `in-process retries keep a flaky queue moving and give up after a streak with no progress`() = runTest {
        val flaky = FakeServer(Random(6), dropRate = 0.3, failureRate = 0.2)
        val store = FakeStore(flaky, Random(6))
        repeat(50) { store.addReceipt("r$it") }
        val pauses = mutableListOf<Long>()
        // one WorkManager run usually finishes the whole queue despite 30% drops
        assertThat(SyncEngine(store, flaky).drainWithRetries(maxFailedStreak = 10) { pauses += it }).isEqualTo(SyncEngine.Outcome.DRAINED)
        assertThat(flaky.receipts).hasSize(50)
        assertThat(pauses.max()).isAtMost(8_500L)

        val down = FakeServer(Random(7), offline = true)
        val stuck = FakeStore(down, Random(7)).apply { addReceipt("x") }
        var waits = 0
        assertThat(SyncEngine(stuck, down).drainWithRetries { waits++ }).isEqualTo(SyncEngine.Outcome.RETRY_LATER)
        assertThat(waits).isEqualTo(3) // 4 attempts, then WorkManager's backoff takes over
    }

    /**
     * The design doc's chaos test (§12), at the sync-logic level: N receipts, 30% of
     * responses dropped after the server commits, 20% 500s, 10% timeouts, an offline
     * stretch, and the process killed at random points mid-sync. The queue must
     * drain with every receipt on the server exactly once and no image deleted early.
     */
    @Test
    fun `chaos - no receipt lost and none duplicated`() = runTest {
        for (seed in 1..20) {
            val random = Random(seed)
            val server = FakeServer(random, failureRate = 0.2, dropRate = 0.3, timeoutRate = 0.1)
            val store = FakeStore(server, random, killRate = 0.1)
            val n = 200
            repeat(n) { store.addReceipt("r$it") }
            val engine = SyncEngine(store, server)

            var rounds = 0
            while (store.ops.any { it.state != OutboxState.DONE && it.state != OutboxState.FAILED_PERMANENT }) {
                rounds++
                server.offline = rounds in 5..15 // airplane mode for a while
                try {
                    engine.drain()
                } catch (_: ProcessDeath) {
                    store.recoverInFlight() // relaunch
                }
                check(rounds < 100_000) { "queue never drained (seed $seed)" }
            }

            assertThat(server.receipts.size).isEqualTo(n) // 0 lost
            assertThat(server.receipts.values.toSet()).containsExactly(1) // 0 duplicates
            assertThat(store.deletedBeforeServerHadIt).isEmpty()
            assertThat(store.images).isEmpty()
            assertThat(store.ops.count { it.state == OutboxState.FAILED_PERMANENT }).isEqualTo(0)
        }
    }
}
