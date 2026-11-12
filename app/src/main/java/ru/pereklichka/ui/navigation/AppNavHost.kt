package ru.pereklichka.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import ru.pereklichka.ui.diagnostics.DiagnosticsScreen
import ru.pereklichka.ui.screens.AlarmScreen
import ru.pereklichka.ui.screens.AwayScreen
import ru.pereklichka.ui.screens.CreateGroupScreen
import ru.pereklichka.ui.screens.FinishRouteScreen
import ru.pereklichka.ui.screens.GroupScreen
import ru.pereklichka.ui.screens.JoinScreen
import ru.pereklichka.ui.screens.LobbyScreen
import ru.pereklichka.ui.screens.MemberScreen
import ru.pereklichka.ui.screens.RollcallScreen
import ru.pereklichka.ui.screens.SosScreen
import ru.pereklichka.ui.screens.StartScreen

/**
 * Граф навигации: все экраны приложения и переходы между ними. Его показывает `MainActivity`.
 *
 * Один [GroupViewModel] на всю навигацию, поэтому экраны группы смотрят на один и тот же `GroupView`. Первый экран
 * зависит от того, была ли активная группа при запуске. Дальше навигация следит за [GroupViewModel.hasGroup]:
 * когда группа появилась, экран создания или вступления сменяется экраном группы, а когда её стёрла «Новая группа»,
 * приложение уходит на старт. При каждом возврате приложения на экран вызывается
 * [GroupViewModel.ensureRouteServiceRunning], так служба оживает после убийства процесса или выдачи разрешений.
 */
@Composable
fun AppNavHost(
    openTarget: NotificationTarget? = null,
    onOpenHandled: () -> Unit = {},
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val hadGroupAtStart by viewModel.hadGroupAtStart.collectAsStateWithLifecycle()
    val hasGroup by viewModel.hasGroup.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val serviceStarted by viewModel.serviceStarted.collectAsStateWithLifecycle()
    val settingUp by viewModel.settingUp.collectAsStateWithLifecycle()
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

    // Нажатие на уведомление: открыть экран поверх текущего. Повторное нажатие не плодит копии экрана.
    LaunchedEffect(openTarget) {
        val target = openTarget ?: return@LaunchedEffect
        val route: Any = when (target) {
            NotificationTarget.Alarms -> AlarmRoute
            NotificationTarget.Rollcall -> RollcallRoute
            is NotificationTarget.Member -> MemberRoute(target.id.value)
        }
        navController.open(route)
        onOpenHandled()
    }

    // Переход по состоянию, а не из корутины создания: после поворота экрана группа может появиться, когда
    // экран создания уже пересоздан вместе с навигацией.
    LaunchedEffect(hasGroup) {
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
            StartScreen(
                onCreateGroup = { go { navController.open(CreateGroupRoute) } },
                onJoinGroup = { go { navController.open(JoinRoute) } },
                // Без проверки RESUMED: стартовый экран открывает разрешения сам, ещё до конца перехода на него,
                // а второй копии экрана не будет и так из-за launchSingleTop.
                onPermissions = { navController.open(PermissionsRoute) },
                onSettings = { go { navController.open(DiagnosticsRoute) } },
            )
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
                onJoin = viewModel::joinGroup,
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
                    onSettings = { go { navController.open(DiagnosticsRoute) } },
                    onPermissions = { go { navController.open(PermissionsRoute) } },
                    onLobby = { go { navController.open(LobbyRoute) } },
                    onStopService = viewModel::stopService,
                    onNewGroup = viewModel::leaveGroup,
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
            WithView(view) {
                SosScreen(
                    it,
                    onSos = viewModel::sos,
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
                    onFinish = viewModel::finishRoute,
                    onStopService = viewModel::stopService,
                    onNewGroup = viewModel::leaveGroup,
                    onPermissions = { go { navController.open(PermissionsRoute) } },
                    onBack = { go { navController.popBackStack() } },
                )
            }
        }
        composable<PermissionsRoute> {
            val go = onlyWhenResumed()
            PermissionsScreen(onDone = { go { viewModel.ensureRouteServiceRunning(); navController.popBackStack() } })
        }
        composable<DiagnosticsRoute> {
            val go = onlyWhenResumed()
            DiagnosticsScreen(onBack = { go { navController.popBackStack() } })
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

/** Переход без возврата: после создания группы, вступления и «Новой группы» «Назад» не ведёт на прошлый экран. */
private fun NavHostController.replaceStack(route: Any) {
    navigate(route) { popUpTo(graph.id) { inclusive = true } }
}

/**
 * В стеке есть экраны группы, а со стёртой группой на них оставаться нельзя. `getBackStackEntry` бросает
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
