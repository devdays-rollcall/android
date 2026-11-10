package ru.pereklichka.domain.model

/**
 * Группа, в которой сейчас состоит этот телефон. Одновременно активна одна группа.
 *
 * Создаёт её `GroupRepository` при создании группы или вступлении по QR, читают почти все части приложения.
 * [token] это 32 случайных байта в base64url без выравнивания, с ним телефон ходит на сервер. [tag] выводится
 * из токена (`docs/03-sync-protocol.md`, «Транспорт поверх Nearby Connections»): из него строится serviceId
 * Nearby, и его же сверяет сводка (Hello). [me] это `member_id` этого телефона, новый для каждой группы.
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
 * Строку QR собирает и разбирает `QrFormat`, а `GroupRepository.join` превращает [QrPayload] в [GroupInfo].
 */
data class QrPayload(
    val token: String,
    val groupName: String,
    val leaderId: MemberId,
)
