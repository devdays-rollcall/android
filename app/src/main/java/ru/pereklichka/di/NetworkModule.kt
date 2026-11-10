package ru.pereklichka.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.pereklichka.upload.EventsApi
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** HTTP-клиент сервера событий. Функции открыты, чтобы `UploadWorkerTest` собрал тот же клиент против MockWebServer. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Пустые поля не отправляются: спецификация сервера запрещает подменять отсутствующие измерения. */
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     * Таймауты короткие: на «почти сети» (Wi-Fi без интернета, белые списки) запрос всё равно не пройдёт,
     * и лучше быстрее отдать WorkManager повтор.
     */
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun eventsApi(client: OkHttpClient): EventsApi = Retrofit.Builder()
        // Адрес сервера приходит в каждом вызове через @Url, базовый адрес Retrofit требует формально.
        .baseUrl("http://localhost/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(EventsApi::class.java)
}
