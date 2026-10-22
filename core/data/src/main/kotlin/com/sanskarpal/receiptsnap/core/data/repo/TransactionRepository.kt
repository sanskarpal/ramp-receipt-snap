package com.sanskarpal.receiptsnap.core.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sanskarpal.receiptsnap.core.data.db.AppDatabase
import com.sanskarpal.receiptsnap.core.data.db.TransactionEntity
import com.sanskarpal.receiptsnap.core.data.db.TransactionRow
import com.sanskarpal.receiptsnap.core.data.network.ReceiptSnapApi
import com.sanskarpal.receiptsnap.core.matching.MerchantNormalizer
import com.sanskarpal.receiptsnap.core.model.ReceiptState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val db: AppDatabase,
    private val api: ReceiptSnapApi,
    private val prefs: DataStore<Preferences>,
) {
    fun observeRows(): Flow<List<TransactionRow>> = db.transactions().observeRows()

    /** Incremental pull: everything updated since the stored cursor, all pages, upserted into Room. */
    suspend fun refresh() {
        var cursor = prefs.data.first()[CURSOR]
        var pageToken: String? = null
        do {
            val page = api.transactions(updatedSince = cursor, pageToken = pageToken)
            val local = db.transactions().byIds(page.transactions.map { it.id }).associateBy { it.id }
            val rows = page.transactions.map { dto ->
                val mine = local[dto.id]?.receiptId
                // the server wins, except while a locally attached receipt is still on its way up
                val keepLocal = dto.receiptId == null && mine != null && !isSynced(mine)
                TransactionEntity(
                    id = dto.id,
                    merchantDescriptor = dto.merchantDescriptor,
                    merchantNormalized = MerchantNormalizer.normalize(dto.merchantDescriptor),
                    amountCents = dto.amountCents,
                    currency = dto.currency,
                    authorizedAt = Instant.parse(dto.authorizedAt),
                    cardLast4 = dto.cardLast4,
                    receiptId = if (keepLocal) mine else dto.receiptId,
                    updatedAt = Instant.parse(dto.updatedAt),
                )
            }
            db.transactions().upsert(rows)
            page.transactions.maxOfOrNull { it.updatedAt }?.let { newest ->
                if (cursor == null || newest > cursor!!) cursor = newest
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        cursor?.let { c -> prefs.edit { it[CURSOR] = c } }
    }

    private suspend fun isSynced(receiptId: String) = db.receipts().get(receiptId)?.state == ReceiptState.SYNCED

    companion object {
        private val CURSOR = stringPreferencesKey("transactions_cursor")
    }
}
