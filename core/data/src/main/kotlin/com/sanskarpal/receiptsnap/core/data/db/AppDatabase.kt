package com.sanskarpal.receiptsnap.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant
import java.time.LocalDate

@Database(
    entities = [
        TransactionEntity::class, ReceiptEntity::class, ReceiptExtractionEntity::class,
        MatchEntity::class, ApprovalRequestEntity::class, OutboxOperationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun receipts(): ReceiptDao
    abstract fun approvals(): ApprovalDao
    abstract fun outbox(): OutboxDao
}

class Converters {
    @TypeConverter fun instantToLong(v: Instant?): Long? = v?.toEpochMilli()
    @TypeConverter fun longToInstant(v: Long?): Instant? = v?.let(Instant::ofEpochMilli)
    @TypeConverter fun dateToString(v: LocalDate?): String? = v?.toString()
    @TypeConverter fun stringToDate(v: String?): LocalDate? = v?.let(LocalDate::parse)
}
