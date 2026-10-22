package com.sanskarpal.receiptsnap.di

import com.sanskarpal.receiptsnap.BuildConfig
import com.sanskarpal.receiptsnap.core.data.repo.TextRecognizer
import com.sanskarpal.receiptsnap.mlkit.MlKitTextRecognizer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    abstract fun textRecognizer(impl: MlKitTextRecognizer): TextRecognizer

    companion object {
        @Provides
        @Named("apiBaseUrl")
        fun apiBaseUrl(): String = BuildConfig.API_BASE_URL
    }
}
