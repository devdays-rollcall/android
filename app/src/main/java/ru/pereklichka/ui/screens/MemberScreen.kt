package ru.pereklichka.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.GroupView

/**
 * Карточка участника: точность позиции, кто слышал последним (`lastHeardBy`),
 * «Объявить тревогу», «Вижу, в порядке» при активной тревоге о нём.
 *
 * Задача: docs/team/valentin-ui.md
 */
@Composable
fun MemberScreen(view: GroupView, memberId: MemberId, onRaiseAlarm: (MemberId) -> Unit, onSeenOk: (MemberId) -> Unit) {
    // TODO(Валентин): подробная карточка; «Объявить тревогу» о себе не показывать (для себя есть SOS).
    //  docs/team/valentin-ui.md
    val member = view.members.firstOrNull { it.id == memberId }
    StubScreen(member?.name ?: "Участник не найден") {
        if (member == null) return@StubScreen
        Text("Статус: ${member.presence}, слышали ${member.lastContact?.ms?.div(1000)} с назад")
        if (!member.isMe) StubButton("Объявить тревогу") { onRaiseAlarm(member.id) }
        if (member.hasActiveAlarm && !member.isMe) StubButton("Вижу, в порядке") { onSeenOk(member.id) }
    }
}
