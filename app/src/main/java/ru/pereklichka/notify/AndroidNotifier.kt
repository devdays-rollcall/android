package ru.pereklichka.notify

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Notifier] на системных уведомлениях. Тихий канал службы создаёт `ServiceNotification`, остальные каналы
 * создаются здесь.
 */
@Singleton
class AndroidNotifier @Inject constructor(@ApplicationContext private val context: Context) : Notifier {

    override fun show(event: NotifyEvent) {
        // TODO(Валентин): каналы тревог (USAGE_ALARM), статусов и переклички; event.key как tag уведомления.
        //  Нажатие открывает нужный экран; без POST_NOTIFICATIONS звук тревоги играет само приложение.
        //  docs/team/valentin-ui.md
        throw NotImplementedError("TODO(Валентин): каналы тревог (USAGE_ALARM), статусов и переклички; event.key как tag уведомления.")
    }

    override fun dismiss(key: String) {
        // TODO(Валентин): убрать уведомление с tag = key. docs/team/valentin-ui.md
        throw NotImplementedError("TODO(Валентин): убрать уведомление с tag = key.")
    }
}
