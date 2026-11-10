package ru.pereklichka.domain.rules

import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.model.GroupSnapshot
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.presence.PresenceTracker
import ru.pereklichka.domain.time.Age
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.domain.time.Stamped
import ru.pereklichka.domain.time.ageNow

/**
 * Правила статусов: из снимка данных считает [GroupView] для экранов и `StatusMonitor`.
 *
 * Создаёт и вызывает его `GroupViewProvider` раз в 2 секунды и при изменении данных; при переключении режима демо
 * провайдер создаёт калькулятор с другими [Thresholds]. Это чистая функция: никакого состояния между вызовами
 * и никаких полей «статус» в базе. Тревоги, перекличка и состояние маршрута зависят только от множества событий,
 * поэтому одинаковые события в любом порядке и с повторами дают одинаковый результат на всех телефонах.
 * Настенное время в расчётах не участвует.
 */
class GroupCalculator(private val thresholds: Thresholds) {

    /**
     * @param heardAgo кого мой телефон слышал напрямую и сколько миллисекунд назад, из `PresenceTracker.heardAgo()`.
     * @param myFix моя последняя фиксация для расстояний и направлений; null, если её ещё нет.
     */
    fun compute(snapshot: GroupSnapshot, heardAgo: Map<MemberId, Long>, myFix: Fix?, clock: Clock): GroupView =
        Calculation(snapshot, heardAgo, myFix, clock).groupView()

    /** Один расчёт: промежуточные значения считаются один раз и нужны нескольким частям [GroupView]. */
    private inner class Calculation(
        private val snapshot: GroupSnapshot,
        private val heardAgo: Map<MemberId, Long>,
        private val myFix: Fix?,
        private val clock: Clock,
    ) {
        private val group = snapshot.group
        private val me = group.me

        // События неизвестного типа хранятся ради префикса, но в правилах не участвуют.
        private val events: List<Stamped<ControlEvent>> = snapshot.events
            .filter { it.value.type != EventType.UNKNOWN }
            .distinctBy { it.value.id }
        private val eventsById = events.associateBy { it.value.id }
        private val names: Map<MemberId, String> = events
            .filter { it.value.type == EventType.JOINED }
            .associate { it.value.author to (it.value.name ?: it.value.author.value) }
        private val alarms: List<AlarmView> = activeAlarms(events.map { it.value }, group.leaderId).map(::alarmView)

        fun groupView(): GroupView {
            val members = names.keys
                .map(::memberView)
                .sortedWith(compareBy({ !it.isMe }, { it.name }, { it.id.value }))
            return GroupView(
                group = group,
                me = me,
                iAmLeader = me == group.leaderId,
                route = routeState(),
                members = members,
                alarms = alarms,
                rollcall = rollcallView(members),
                prompts = nearbyPrompts(),
            )
        }

        private fun routeState(): RouteState {
            val leaderTypes = events.filter { it.value.author == group.leaderId }.map { it.value.type }.toSet()
            return when {
                EventType.ROUTE_FINISHED in leaderTypes -> RouteState.FINISHED
                EventType.ROUTE_STARTED in leaderTypes -> RouteState.ACTIVE
                else -> RouteState.LOBBY
            }
        }

        private fun memberView(id: MemberId): MemberView {
            val isMe = id == me
            val contact = if (isMe) null else lastContact(id)
            val awayRemainingMs = awayRemainingMs(id)
            val presence = when {
                isMe -> Presence.OK
                awayRemainingMs != null -> Presence.AWAY
                contact == null || contact.age.ms > thresholds.lostAfterMs -> Presence.LOST
                else -> Presence.OK
            }
            val state = snapshot.states[id]
            val position = state?.value?.position
            val positionAge = position?.let {
                val stateAge = state.ageNow(clock)
                Age(stateAge.ms + (state.value.fixAgeMs ?: 0), stateAge.approximate)
            }
            val origin = myFix?.position
            return MemberView(
                id = id,
                name = nameOf(id),
                isLeader = id == group.leaderId,
                isMe = isMe,
                presence = presence,
                lastContact = contact?.age,
                heardDirectlyNow = !isMe && isHeardDirectlyNow(id),
                lastHeardBy = contact?.heardBy,
                position = position,
                positionAge = positionAge,
                accuracyM = state?.value?.accuracyM,
                distanceM = if (origin != null && position != null) Geo.distanceM(origin, position) else null,
                bearingDeg = if (origin != null && position != null) Geo.bearingDeg(origin, position) else null,
                batteryPercent = state?.value?.batteryPercent,
                awayRemainingMs = awayRemainingMs,
                hasActiveAlarm = alarms.any { it.target == id },
                hasSos = alarms.any { it.target == id && it.isSos },
            )
        }

        /**
         * Минимум из всех наблюдений: моё прямое, возраст последнего состояния и события участника и, для каждого
         * участника O, возраст состояния O плюс то, сколько назад O слышал его (`03-sync-protocol.md`).
         */
        private fun lastContact(id: MemberId): Contact? {
            val observations = buildList {
                heardAgo[id]?.let { add(Contact(Age(it, approximate = false), heardBy = me)) }
                snapshot.states[id]?.let { add(Contact(it.ageNow(clock), heardBy = null)) }
                events.filter { it.value.author == id }.maxByOrNull { it.value.seq }
                    ?.let { add(Contact(it.ageNow(clock), heardBy = null)) }
                for ((observer, state) in snapshot.states) {
                    if (observer == id) continue
                    val heardAgoMs = state.value.heardAgoMs[id] ?: continue
                    val stateAge = state.ageNow(clock)
                    add(Contact(Age(stateAge.ms + heardAgoMs, stateAge.approximate), heardBy = observer))
                }
            }
            return observations.minByOrNull { it.age.ms }
        }

        /** Сколько осталось отлучки по последнему событию AWAY; null, если отлучки нет, она кончилась или отменена. */
        private fun awayRemainingMs(id: MemberId): Long? {
            val away = events
                .filter { it.value.author == id && it.value.type == EventType.AWAY }
                .maxByOrNull { it.value.seq } ?: return null
            val remainingMs = (away.value.durationMs ?: 0) - away.ageNow(clock).ms
            return remainingMs.takeIf { it > 0 }
        }

        private fun rollcallView(members: List<MemberView>): RollcallView? {
            val rollcall = events
                .filter { it.value.author == group.leaderId && it.value.type == EventType.ROLLCALL }
                .maxByOrNull { it.value.seq } ?: return null
            val remainingMs = thresholds.rollcallWindowMs - rollcall.ageNow(clock).ms
            if (remainingMs <= 0) return null

            val answeredIds = events
                .filter { it.value.type == EventType.ROLLCALL_ANSWER && rollcall.value.id in it.value.refs }
                .map { it.value.author }
                .toSet()
            // Порог «слышен сейчас» здесь не годится: за три-четыре перехода оценка контакта доходит до 30 с,
            // поэтому «рядом» значит «не LOST».
            val (answered, silent) = members.filter { !it.isLeader }.partition { it.id in answeredIds }
            val (notHeard, silentNearby) = silent.partition { it.presence == Presence.LOST }
            return RollcallView(
                id = rollcall.value.id,
                remainingMs = remainingMs,
                answered = answered.map { it.id },
                silentNearby = silentNearby.map { it.id },
                notHeard = notHeard.map { it.id },
            )
        }

        private fun nearbyPrompts(): List<NearbyPrompt> = alarms
            .map { it.target }
            .distinct()
            .filter { it != me && isHeardDirectlyNow(it) }
            .map { NearbyPrompt(it, nameOf(it)) }

        private fun alarmView(alarm: ControlEvent): AlarmView {
            val target = checkNotNull(alarm.alarmTarget)
            return AlarmView(
                id = alarm.id,
                target = target,
                targetName = nameOf(target),
                raisedBy = alarm.author,
                raisedByName = nameOf(alarm.author),
                isSos = alarm.type == EventType.SOS,
                age = eventsById.getValue(alarm.id).ageNow(clock),
            )
        }

        private fun isHeardDirectlyNow(id: MemberId): Boolean {
            val heardAgoMs = heardAgo[id] ?: return false
            return heardAgoMs <= PresenceTracker.HEARD_NOW_MS
        }

        // Тревога может прийти раньше JOINED её цели, тогда вместо имени показываем id.
        private fun nameOf(id: MemberId): String = names[id] ?: id.value
    }

    /** Наблюдение о последнем контакте и тот, кто слышал участника напрямую. */
    private class Contact(val age: Age, val heardBy: MemberId?)
}
