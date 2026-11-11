# Перекличка

Android-приложение, которое держит связь туристической группы без сотовой сети. Телефоны обмениваются данными через Google Nearby Connections, считают статусы и тревоги, а при появлении интернета отправляют GPS-точки на сервер. Протокол обмена и задачи участников лежат в [`docs/`](docs/README.md).

Кнопка SOS включает предупреждение группы с пометкой SOS и отправляет его в ntfy. Без сети запрос остаётся в очереди до подключения. Это не вызов экстренных служб; доставка уведомления на каждый телефон не гарантируется.

## Сборка и тесты

Нужны Android Studio (её JDK подходит) и Android SDK с платформой 37. Путь к SDK Android Studio записывает в `local.properties` сама.

```text
./gradlew assembleDebug test      # APK и все тесты, около минуты после первой сборки
./gradlew :domain:test            # только логика без Android, несколько секунд
./gradlew :app:testDebugUnitTest  # тесты app, Room и WorkManager идут через Robolectric без эмулятора
```

Для проверки HTTP-клиента с работающим monitor можно отдельно запустить:

```sh
MONITOR_LIVE_URL=https://rollcall.vareonix.cc ./gradlew :app:testDebugUnitTest --tests 'ru.pereklichka.upload.MonitorLiveApiTest' --rerun-tasks
```

Тест создаёт на указанном сервере новую группу с двумя точками и ключом просмотра. Данные остаются на сервере после проверки; тестовый ключ нигде не сохраняется. Без `MONITOR_LIVE_URL` тест пропускается.

Свою часть ищи по метке: `git grep -n "TODO(Ольга)"`. Тесты-спецификации помечены `@Ignore("включи, когда сделаешь ...")`, и когда часть готова, пометку снимают и добиваются зелёного.

## Модули

`domain` написан на чистом Kotlin и про Android не знает, поэтому всё в нём проверяется на ноутбуке за секунды.

| Пакет | Что внутри |
|---|---|
| `domain/model` | Записи группы: события, точки трека, состояния участников, группа, QR |
| `domain/time` | Часы и отметка свежести: возраст записи без общих часов |
| `domain/sync` | Сообщения Hello и Delta, кодек, контракты `MeshStore` и `PeerTransport`, движок `SyncEngine` |
| `domain/presence` | `PresenceTracker`: кого телефон слышит напрямую |
| `domain/rules` | `GroupCalculator` и `GroupView`: статусы, тревоги, перекличка, расстояния |
| `domain/track` | Прореживание трека и очистка фиксаций |
| `domain/testing` | `InMemoryMeshStore`, `InMemoryNetwork` с `InMemoryTransport`, `FakeClock` для тестов обоих модулей |

`app` содержит всё, что требует Android.

| Пакет | Что внутри |
|---|---|
| `di` | Модули Hilt и переключатель фейковых данных `DataModeModule` |
| `data`, `data/db` | Room, `RoomMeshStore`, `GroupRepository`, `AppSettings`, токен группы |
| `actions` | `GroupActions`: действия человека превращаются в события |
| `status` | `GroupViewProvider` (единственный источник `GroupView`) и `StatusMonitor` (уведомления) |
| `service` | `RouteService`, GPS, своё состояние, wake lock и исключение из оптимизации |
| `transport` | Nearby, политика соединений, проверка радио |
| `notify` | Контракт уведомлений и `AndroidNotifier` |
| `upload` | DTO по `openapi.yaml`, `EventsApi`, `UploadScheduler`, `UploadWorker` |
| `qr` | Формат и разбор QR, картинка, сканер |
| `permissions` | Списки разрешений по версиям Android и экран запроса |
| `ui` | Тема, навигация, экраны, `GroupViewModel`, `FakeGroupViews`, диагностика |

## Что осталось участникам

| Файл | Кто | Задача |
|---|---|---|
| `app/.../service/RouteService.kt`, ветка `intent == null` | Василиса | Безопасный перезапуск после убийства процесса |
| `app/.../service/BootReceiver.kt` | Василиса | Запуск после перезагрузки, затем включить приёмник в манифесте |
| `app/.../permissions/PermissionsScreen.kt` | Василиса | Запрос разрешений с объяснением |
| `app/.../upload/UploadWorker.kt` | Даниил | Heartbeat пустой пачкой, пока идёт маршрут, и предел в 2 минуты на запуск |
