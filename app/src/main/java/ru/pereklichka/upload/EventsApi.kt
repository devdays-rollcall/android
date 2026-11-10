package ru.pereklichka.upload

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Клиент Retrofit для сервера событий (`docs/server/openapi.yaml`). Адрес сервера вводят на экране диагностики
 * во время работы, поэтому полный URL передаётся в каждом вызове. Используют [UploadWorker] и его тест
 * с MockWebServer.
 */
interface EventsApi {

    /**
     * `POST /api/v1/events`. [Response] нужен, чтобы различать коды ответа: 202, 400, 401, 413, 422, 503.
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
        /** Полный адрес `POST /api/v1/events` для адреса сервера из `AppSettings`. */
        fun eventsUrl(serverUrl: String): String = serverUrl.trimEnd('/') + "/api/v1/events"

        /** Значение заголовка Authorization для токена группы. */
        fun bearer(token: String): String = "Bearer $token"
    }
}
