package com.sanskarpal.receiptsnap.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.sanskarpal.receiptsnap.core.data.db.AppDatabase
import com.sanskarpal.receiptsnap.core.data.network.ReceiptSnapApi
import com.sanskarpal.receiptsnap.core.data.sync.RetrofitSyncRemote
import com.sanskarpal.receiptsnap.core.data.sync.RoomSyncStore
import com.sanskarpal.receiptsnap.core.data.sync.SyncEngine
import com.sanskarpal.receiptsnap.core.extraction.ReceiptParser
import com.sanskarpal.receiptsnap.core.matching.MatchingEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/** The app provides @Named("apiBaseUrl") and a TextRecognizer; everything else is wired here. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true }

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun api(client: OkHttpClient, json: Json, @Named("apiBaseUrl") baseUrl: String): ReceiptSnapApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ReceiptSnapApi::class.java)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "receiptsnap.db").build()

    @Provides @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("sync") }

    @Provides @Singleton
    fun syncEngine(store: RoomSyncStore, api: ReceiptSnapApi, json: Json): SyncEngine = SyncEngine(store, RetrofitSyncRemote(api, json))

    @Provides @Singleton
    fun parser(): ReceiptParser = ReceiptParser()

    @Provides @Singleton
    fun matchingEngine(): MatchingEngine = MatchingEngine(zone = ZoneId.systemDefault())

    @Provides @Singleton @Named("appScope")
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
