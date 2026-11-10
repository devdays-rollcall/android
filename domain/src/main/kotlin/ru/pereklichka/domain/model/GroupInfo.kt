package ru.pereklichka.domain.model

/**
 * Активная группа на этом телефоне. Одновременно активна одна группа.
 *
 * Создаёт её `GroupRepository` при создании группы или вступлении по QR. [token] это 32 случайных байта
 * в base64url без выравнивания: им подписаны запросы к серверу. [tag] выводится из токена
 * (`03-sync-protocol.md`, раздел про транспорт): из него строится serviceId Nearby, он же сверяется в Hello.
 * [me] новый для каждой группы.
 */
data class GroupInfo(
    val tag: String,
    val token: String,
    val name: String,
    val leaderId: MemberId,
    val me: MemberId,
    val createdAtWallMs: Long,
)

/**
 * Содержимое QR-кода для вступления: всё, что нужно телефону участника, чтобы найти группу без интернета.
 *
 * Строку QR собирает и разбирает код Никиты в `app/qr`, а `GroupActions.joinGroup` превращает её в [GroupInfo].
 */
data class QrPayload(
    val token: String,
    val groupName: String,
    val leaderId: MemberId,
)
