package com.camobs.di

import android.content.Context
import androidx.camera.core.ProcessCameraProvider
import androidx.lifecycle.ViewModelProvider
import com.camobs.model.StreamConfig
import com.camobs.streaming.StreamingEngine
import com.camobs.ui.StreamViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.InstallIn
import dagger.hilt.android.scopes.Singleton
import dagger.hilt.components.SingletonComponent
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDefaultStreamConfig(): StreamConfig = StreamConfig.default()

    @Provides
    @Singleton
    fun provideProcessCameraProvider(@ApplicationContext context: Context): ProcessCameraProvider {
        return ProcessCameraProvider.getInstance(context).get()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ViewModelModule {
    @dagger.hilt.android.lifecycle.HiltViewModel
    abstract fun bindStreamViewModel(): StreamViewModel
}