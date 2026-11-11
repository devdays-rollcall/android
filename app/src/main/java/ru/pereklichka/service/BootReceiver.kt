package ru.pereklichka.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.pereklichka.data.GroupRepository
import ru.pereklichka.di.ApplicationScope
import ru.pereklichka.di.FakeGroupData
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.status.GroupViewProvider
import javax.inject.Inject

/**
 * Поднимает [RouteService] после перезагрузки телефона, если у активной группы маршрут не завершён.
 * Приёмнику BOOT_COMPLETED система разрешает запустить foreground service из фона, а тип connectedDevice
 * не попадает под запрет Android 15 для этого приёмника. На Xiaomi и Honor BOOT_COMPLETED приходит,
 * только если приложению включили автозапуск.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var viewProvider: GroupViewProvider
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    @JvmField
    @Inject
    @FakeGroupData
    var useFakeGroupData: Boolean = false

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || useFakeGroupData) return
        // Чтение базы и первый расчёт вида укладываются в 10 с, которые goAsync даёт приёмнику.
        val pending = goAsync()
        scope.launch {
            try {
                if (groupRepository.activeGroup() != null && viewProvider.awaitRoute() != RouteState.FINISHED) {
                    RouteService.restart(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
