package ru.pereklichka.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.rules.GroupView
import ru.pereklichka.notify.NotificationTarget
import ru.pereklichka.permissions.PermissionsScreen
import ru.pereklichka.ui.GroupViewModel
import ru.pereklichka.ui.PastRoutesViewModel
import ru.pereklichka.ui.screens.AlarmScreen
import ru.pereklichka.ui.screens.AwayScreen
import ru.pereklichka.ui.screens.CreateGroupScreen
import ru.pereklichka.ui.screens.FinishRouteScreen
import ru.pereklichka.ui.screens.GroupScreen
import ru.pereklichka.ui.screens.JoinScreen
import ru.pereklichka.ui.screens.LobbyScreen
import ru.pereklichka.ui.screens.MemberScreen
import ru.pereklichka.ui.screens.PastRouteScreen
import ru.pereklichka.ui.screens.RollcallScreen
import ru.pereklichka.ui.screens.SosScreen
import ru.pereklichka.ui.screens.StartScreen
import ru.pereklichka.ui.settings.DevScreen
import ru.pereklichka.ui.settings.SettingsScreen

/**
 * Граф навигации: все экраны приложения и переходы между ними. Его показывает `MainActivity`.
 *
 * Один [GroupViewModel] на всю навигацию, поэтому экраны группы смотрят на один и тот же `GroupView`. Первый экран
 * зависит от того, была ли активная группа при запуске. Дальше навигация следит за [GroupViewModel.hasGroup]:
 * когда группа появилась, экран создания или вступления сменяется экраном группы, а когда служба закрыла её после
 * выхода или отмены сбора, приложение уходит на старт. С экрана завершённой группы «Назад» и «Новый маршрут» тоже
 * ведут на старт, а группа остаётся активной и открывается оттуда же. Прошлые маршруты открываются со старта на
 * просмотр, их список и удаление даёт [PastRoutesViewModel]. При каждом возврате приложения на экран вызывается
 * [GroupViewModel.ensureRouteServiceRunning], так служба оживает после убийства процесса или выдачи разрешений.
 *
 * Экран группы сменяет экран создания по состоянию, а не из корутины создания: после поворота экрана группа может
 * появиться, когда экран создания уже пересоздан вместе с навигацией. Ключ группы перезапускает переход, даже если
 * промежуточное false при смене группы экран не застал. Нажатие на уведомление открывает его экран поверх текущего,
 * и повторное нажатие копию экрана не добавляет.
 *
 * QR активной группы ведёт на экран группы без нового вступления: иначе группа ушла бы в прошлые маршруты, а телефон
 * вступил бы в неё ещё раз с новым member_id и появился бы у остальных дважды. Отмена сбора без вступивших удаляет
 * группу, и навигация сама уходит на старт; если кто-то вступил, сбор становится завершённым и его показывает экран
 * группы. Разрешения со старта открываются без проверки RESUMED, потому что стартовый экран открывает их сам ещё до
 * конца перехода на него, а вторую копию экрана не даст `launchSingleTop`.
 */
@Composable
fun AppNavHost(
    openTarget: NotificationTarget? = null,
    onOpenHandled: () -> Unit = {},
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val hadGroupAtStart by viewModel.hadGroupAtStart.collectAsStateWithLifecycle()
    val hasGroup by viewModel.hasGroup.collectAsStateWithLifecycle()
    val activeGroupKey by viewModel.activeGroupKey.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val serviceStarted by viewModel.serviceStarted.collectAsStateWithLifecycle()
    val settingUp by viewModel.settingUp.collectAsStateWithLifecycle()
    val exchange by viewModel.exchange.collectAsStateWithLifecycle()
    val trackStatus by viewModel.trackStatus.collectAsStateWithLifecycle()
    val quitting by viewModel.quitting.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    LifecycleResumeEffect(Unit) {
        viewModel.ensureRouteServiceRunning()
        onPauseOrDispose { }
    }
    val startDestination: Any = when (hadGroupAtStart) {
        null -> return Loading()
        true -> GroupRoute
        false -> StartRoute
    }

    LaunchedEffect(openTarget) {
        val target = openTarget ?: return@LaunchedEffect
        val route: Any = when (target) {
            NotificationTarget.Alarms -> AlarmRoute
            NotificationTarget.Rollcall -> RollcallRoute
            NotificationTarget.Away -> AwayRoute
            is NotificationTarget.Member -> MemberRoute(target.id.value)
        }
        navController.open(route)
        onOpenHandled()
    }

    LaunchedEffect(hasGroup, activeGroupKey) {
        val destination = navController.currentBackStackEntry?.destination ?: return@LaunchedEffect
        when (hasGroup) {
            true -> when {
                destination.hasRoute<CreateGroupRoute>() -> navController.replaceStack(LobbyRoute)
                destination.hasRoute<JoinRoute>() -> navController.replaceStack(GroupRoute)
            }
            false -> if (navController.inGroup()) navController.replaceStack(StartRoute)
            null -> Unit
        }
    }

    NavHost(navController, startDestination = startDestination) {
        composable<StartRoute> {
            val go = onlyWhenResumed()
            val pastRoutes: PastRoutesViewModel = hiltViewModel()
            val routes by pastRoutes.routes.collectAsStateWithLifecycle()
            StartScreen(
                lastGroup = view.takeIf { hasGroup == true },
                trackStatus = trackStatus,
                pastRoutes = routes,
                exchange = exchange,
                onOpenGroup = { go { navController.replaceStack(GroupRoute) } },
                onOpenPastRoute = { key -> go { navController.open(PastRouteRoute(key)) } },
                onCreateGroup = { go { viewModel.resetSettingUp(); navController.open(CreateGroupRoute) } },
                onJoinGroup = { go { viewModel.resetSettingUp(); navController.open(JoinRoute) } },
                onPermissions = { navController.open(PermissionsRoute) },
                onSettings = { go { navController.open(SettingsRoute) } },
            )
        }
        composable<PastRouteRoute> { entry ->
            val go = onlyWhenResumed()
            val key = entry.toRoute<PastRouteRoute>().key
            val pastRoutes: PastRoutesViewModel = hiltViewModel()
            val details by remember(key) { pastRoutes.details(key) }.collectAsStateWithLifecycle(initialValue = null)
            val current = details
            if (current == null) {
                Loading()
            } else {
                PastRouteScreen(
                    current,
                    nowWallMs = remember { System.currentTimeMillis() },
                    onDelete = { go { pastRoutes.delete(key); navController.popBackStack() } },
                    onBack = { go { navController.popBackStack() } },
                )
            }
        }
        composable<CreateGroupRoute> {
            val go = onlyWhenResumed()
            CreateGroupScreen(
                creating = settingUp,
                onCreate = viewModel::createGroup,
                onBack = { go { navController.popBackStack() } },
            )
        }
        composable<JoinRoute> {
            val go = onlyWhenResumed()
            JoinScreen(
                joining = settingUp,
                onJoin = { qr, myName ->
                    if (view?.group?.token == qr.token) go { navController.replaceStack(GroupRoute) } else viewModel.joinGroup(qr, myName)
                },
                onBack = { go { navController.popBackStack() } },
            )
        }
        composable<LobbyRoute> {
            val go = onlyWhenResumed()
            WithView(view) {
                LobbyScreen(
                    it,
                    serverUrl = serverUrl,
                    serviceStarted = serviceStarted,
                    onStartRoute = { go { viewModel.startRoute(); navController.replaceStack(GroupRoute) } },
                    onOpenGroup = { go { navController.replaceStack(GroupRoute) } },
                    onPermissions = { go { navController.open(PermissionsRoute) } },
                    onCancelGathering = {
                        val joined = it.members.any { member -> !member.isMe }
                        viewModel.cancelGathering()
                        if (joined) go { navController.replaceStack(GroupRoute) }
                    },
                )
            }
        }
        composable<GroupRoute> {
            val go = onlyWhenResumed()
            WithView(view) {
                GroupScreen(
                    view = it,
                    serviceStarted = serviceStarted,
                    onMember = { member -> go { navController.open(MemberRoute(member.value)) } },
                    onRollcall = { go { navController.open(RollcallRoute) } },
                    onAway = { go { navController.open(AwayRoute) } },
                    onAlarms = { go { navController.open(AlarmRoute) } },
                    onSos = { go { navController.open(SosRoute) } },
                    onFinishRoute = { go { navController.open(FinishRouteRoute) } },
                    onSettings = { go { navController.open(SettingsRoute) } },
                    onPermissions = { go { navController.open(PermissionsRoute) } },
                    onLobby = { go { navController.open(LobbyRoute) } },
                    onStopService = viewModel::stopService,
                    onLeaveNow = viewModel::leaveGroup,
                    onToStart = { go { navController.replaceStack(StartRoute) } },
                    onQuit = viewModel::quitGroup,
                    onCancelGathering = viewModel::cancelGathering,
                    quitting = quitting,
                    exchange = exchange,
                )
            }
        }
        composable<MemberRoute> { entry ->
            val go = onlyWhenResumed()
            val memberId = MemberId(entry.toRoute<MemberRoute>().memberId)
            WithView(view) {
                MemberScreen(
                    view = it,
                    memberId = memberId,
                    onRaiseAlarm = viewModel::raiseAlarm,
                    onSeenOk = { target -> viewModel.resolve(target, ResolveKind.SEEN_OK) },
                    onBack = { go { navController.popBackStack() } },
                )
            }
        }
        composable<RollcallRoute> {
            val go = onlyWhenResumed()
            val reportedBySeq by viewModel.reportedBySeq.collectAsStateWithLifecycle()
            WithView(view) {
                RollcallScreen(
                    it,
                    reportedBySeq = reportedBySeq,
                    onStartRollcall = viewModel::startRollcall,
                    onAnswer = viewModel::answerRollcall,
                    onAway = { go { navController.open(AwayRoute) } },
                    onBack = { go { navController.popBackStack() } },
                )
            }
        }
        composable<AwayRoute> {
            val go = onlyWhenResumed()
            WithView(view) {
                AwayScreen(
                    it,
                    onAway = viewModel::announceAway,
                    onCancelAway = viewModel::cancelAway,
                    onClose = { go { navController.popBackStack() } },
                )
            }
        }
        composable<AlarmRoute> {
            val go = onlyWhenResumed()
            WithView(view) {
                AlarmScreen(
                    it,
                    onResolve = viewModel::resolve,
                    onMember = { member -> go { navController.open(MemberRoute(member.value)) } },
                    onClose = { go { navController.popBackStack() } },
                )
            }
        }
        composable<SosRoute> {
            val go = onlyWhenResumed()
            val reportedBySeq by viewModel.reportedBySeq.collectAsStateWithLifecycle()
            WithView(view) {
                SosScreen(
                    it,
                    reportedBySeq = reportedBySeq,
                    onSos = viewModel::sos,
                    onCancelSos = { viewModel.resolve(it.me, ResolveKind.SELF_OK) },
                    onResendToServer = viewModel::resendSosToServer,
                    onBack = { go { navController.popBackStack() } },
                )
            }
        }
        composable<FinishRouteRoute> {
            val go = onlyWhenResumed()
            WithView(view) {
                FinishRouteScreen(
                    it,
                    serviceStarted = serviceStarted,
                    exchange = exchange,
                    trackStatus = trackStatus,
                    onFinish = viewModel::finishRoute,
                    onStopService = viewModel::stopService,
                    onNewRoute = { go { navController.replaceStack(StartRoute) } },
                    onPermissions = { go { navController.open(PermissionsRoute) } },
                    onBack = { go { navController.popBackStack() } },
                )
            }
        }
        composable<PermissionsRoute> {
            val go = onlyWhenResumed()
            PermissionsScreen(onDone = { go { viewModel.ensureRouteServiceRunning(); navController.popBackStack() } })
        }
        composable<SettingsRoute> {
            val go = onlyWhenResumed()
            SettingsScreen(
                onBack = { go { navController.popBackStack() } },
                onPermissions = { go { navController.open(PermissionsRoute) } },
                onDev = { go { navController.open(DevRoute) } },
            )
        }
        composable<DevRoute> {
            val go = onlyWhenResumed()
            DevScreen(onBack = { go { navController.popBackStack() } })
        }
    }
}

/**
 * Нажатие срабатывает, только пока экран в RESUMED. Во время перехода экран уже не RESUMED, поэтому второе нажатие
 * «Назад» не снимет ещё и экран под ним, а второе нажатие кнопки перехода не откроет экран дважды.
 */
@Composable
private fun onlyWhenResumed(): (() -> Unit) -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return { action -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action() }
}

/** Открывает экран поверх текущего. Если этот экран уже сверху, второй копии не будет. */
private fun NavHostController.open(route: Any) {
    navigate(route) { launchSingleTop = true }
}

/**
 * Переход без возврата: после создания группы, вступления, закрытия группы и между стартом и завершённой группой
 * «Назад» не ведёт на прошлый экран.
 */
private fun NavHostController.replaceStack(route: Any) {
    navigate(route) { popUpTo(graph.id) { inclusive = true } }
}

/**
 * В стеке есть экраны группы, а с закрытой группой на них оставаться нельзя. `getBackStackEntry` бросает
 * исключение, если экрана в стеке нет.
 */
private fun NavHostController.inGroup(): Boolean =
    runCatching { getBackStackEntry<GroupRoute>() }.isSuccess || runCatching { getBackStackEntry<LobbyRoute>() }.isSuccess

/** Рисует экран группы, когда есть `GroupView`, а до первого расчёта показывает ожидание. */
@Composable
private fun WithView(view: GroupView?, content: @Composable (GroupView) -> Unit) {
    if (view == null) Loading() else content(view)
}

@Composable
private fun Loading() {
    Surface(Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) { Text("Загрузка группы…") }
    }
}
