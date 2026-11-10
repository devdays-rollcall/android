package ru.pereklichka.upload

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Сервер событий. Адрес сервера вводят на экране диагностики во время работы, поэтому полный URL передаётся
 * в каждом вызове, а не задаётся в Retrofit заранее. Используют `UploadWorker` и его тест с MockWebServer.
 */
interface EventsApi {

    /**
     * `POST /api/v1/events`. [Response] нужен, чтобы различать коды ответа: 202, 413, 422, 401, 503.
     * @param url [eventsUrl] от адреса из `AppSettings`.
     * @param authorization [bearer] от токена группы. В логи его выводить нельзя.
     */
    @POST
    suspend fun postEvents(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body batch: EventBatchDto,
    ): Response<AcceptedDto>

    companion object {
        fun eventsUrl(serverUrl: String): String = serverUrl.trimEnd('/') + "/api/v1/events"

        fun bearer(token: String): String = "Bearer $token"
    }
}
