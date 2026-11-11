package ru.pereklichka.notify

import android.content.Intent
import ru.pereklichka.domain.model.MemberId

/**
 * Какой экран открыть по нажатию на уведомление. [AndroidNotifier] кладёт его в Intent для `MainActivity`,
 * `MainActivity` достаёт и передаёт в `AppNavHost`, а тот переходит на экран.
 */
sealed interface NotificationTarget {
    data object Alarms : NotificationTarget
    data object Rollcall : NotificationTarget
    data class Member(val id: MemberId) : NotificationTarget

    companion object {
        private const val EXTRA_OPEN = "ru.pereklichka.OPEN"
        private const val ALARMS = "alarms"
        private const val ROLLCALL = "rollcall"
        private const val MEMBER_PREFIX = "member:"

        fun put(intent: Intent, target: NotificationTarget): Intent = intent.putExtra(
            EXTRA_OPEN,
            when (target) {
                Alarms -> ALARMS
                Rollcall -> ROLLCALL
                is Member -> MEMBER_PREFIX + target.id.value
            },
        )

        /** Цель из Intent или null, если Activity открыли не из уведомления. */
        fun from(intent: Intent?): NotificationTarget? {
            val value = intent?.getStringExtra(EXTRA_OPEN) ?: return null
            return when {
                value == ALARMS -> Alarms
                value == ROLLCALL -> Rollcall
                value.startsWith(MEMBER_PREFIX) -> Member(MemberId(value.removePrefix(MEMBER_PREFIX)))
                else -> null
            }
        }
    }
}
