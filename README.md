# Перекличка

Android-приложение, которое держит связь туристической группы без сотовой сети. Телефоны обмениваются данными через Google Nearby Connections, считают статусы и тревоги, а при появлении интернета отправляют GPS-точки на сервер. Протокол обмена и задачи участников лежат в [`docs/`](docs/README.md).

## Сборка и тесты

Нужны Android Studio (её JDK подходит) и Android SDK с платформой 37. Путь к SDK Android Studio записывает в `local.properties` сама.

```text
./gradlew assembleDebug test      # APK и все тесты, около минуты после первой сборки
./gradlew :domain:test            # только логика без Android, несколько секунд
./gradlew :app:testDebugUnitTest  # тесты app, Room и WorkManager идут через Robolectric без эмулятора
```

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
| `domain/.../sync/SyncEngine.kt` | Ольга | Обработчики сеанса: Hello, Delta, рассылка, таймер Hello; цель `MeshSimulationTest` |
| `app/.../transport/NearbyPeerTransport.kt` | Ольга | Реклама, поиск и соединения Nearby по `ConnectionPolicy`, отправка |
| `app/.../ui/diagnostics/DiagnosticsViewModel.kt` | Ольга | Соседи с временем последнего сообщения, список блокировки |
| `app/.../data/RoomMeshStore.kt` | Василиса | Хранилище на Room по эталону `InMemoryMeshStore`; цель `RoomMeshStoreContractTest` |
| `app/.../service/FusedLocationSource.kt` | Василиса | GPS раз в 5 с на `callbackFlow` |
| `app/.../service/RouteService.kt`, ветка `intent == null` | Василиса | Безопасный перезапуск после убийства процесса |
| `app/.../service/BootReceiver.kt` | Василиса | Запуск после перезагрузки, затем включить приёмник в манифесте |
| `app/.../permissions/PermissionsScreen.kt` | Василиса | Запрос разрешений с объяснением |
| `app/.../ui/screens/*.kt` | Валентин | Одиннадцать экранов; в каждой заглушке написано, какие поля `GroupView` нужны |
| `app/.../notify/AndroidNotifier.kt` | Валентин | Каналы, звук тревоги, одно уведомление на тревогу |
| `app/.../upload/UploadWorker.kt` | Даниил | Пачки, коды ответа, повторы, heartbeat; цель `UploadWorkerTest` |
