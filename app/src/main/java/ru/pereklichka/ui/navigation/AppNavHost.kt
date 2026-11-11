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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * зависит от того, была ли активная группа при запуске. При каждом возврате приложения на экран вызывается
 * [GroupViewModel.ensureRouteServiceRunning], так служба оживает после убийства процесса или выдачи разрешений.
 */
@Composable
fun AppNavHost(
    openTarget: NotificationTarget? = null,
    onOpenHandled: () -> Unit = {},
    viewModel: GroupViewModel = hiltViewModel(),
) {
    val hadGroupAtStart by viewModel.hadGroupAtStart.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
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
        navController.navigate(route) { launchSingleTop = true }
        onOpenHandled()
    }

    NavHost(navController, startDestination = startDestination) {
        composable<StartRoute> {
            StartScreen(
                onCreateGroup = { navController.navigate(CreateGroupRoute) },
                onJoinGroup = { navController.navigate(JoinRoute) },
                onPermissions = { navController.navigate(PermissionsRoute) },
                onDiagnostics = { navController.navigate(DiagnosticsRoute) },
            )
        }
        composable<CreateGroupRoute> {
            CreateGroupScreen(
                onCreate = { groupName, myName ->
                    viewModel.createGroup(groupName, myName) { navController.replaceStack(LobbyRoute) }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<JoinRoute> {
            JoinScreen(
                onJoin = { qr, myName -> viewModel.joinGroup(qr, myName) { navController.replaceStack(GroupRoute) } },
                onBack = { navController.popBackStack() },
            )
        }
        composable<LobbyRoute> {
            WithView(view) {
                LobbyScreen(
                    it,
                    serverUrl = serverUrl,
                    onStartRoute = { viewModel.startRoute(); navController.replaceStack(GroupRoute) },
                    onOpenGroup = { navController.replaceStack(GroupRoute) },
                )
            }
        }
        composable<GroupRoute> {
            WithView(view) {
                GroupScreen(
                    view = it,
                    onMember = { member -> navController.navigate(MemberRoute(member.value)) },
                    onRollcall = { navController.navigate(RollcallRoute) },
                    onAway = { navController.navigate(AwayRoute) },
                    onAlarms = { navController.navigate(AlarmRoute) },
                    onSos = { navController.navigate(SosRoute) },
                    onFinishRoute = { navController.navigate(FinishRouteRoute) },
                    onDiagnostics = { navController.navigate(DiagnosticsRoute) },
                    onStopService = viewModel::stopService,
                )
            }
        }
        composable<MemberRoute> { entry ->
            val memberId = MemberId(entry.toRoute<MemberRoute>().memberId)
            WithView(view) {
                MemberScreen(
                    view = it,
                    memberId = memberId,
                    onRaiseAlarm = viewModel::raiseAlarm,
                    onSeenOk = { target -> viewModel.resolve(target, ResolveKind.SEEN_OK) },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable<RollcallRoute> {
            WithView(view) {
                RollcallScreen(
                    it,
                    onStartRollcall = viewModel::startRollcall,
                    onAnswer = viewModel::answerRollcall,
                    onAway = { navController.navigate(AwayRoute) },
                    onSos = { navController.navigate(SosRoute) },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable<AwayRoute> {
            WithView(view) {
                AwayScreen(
                    it,
                    onAway = viewModel::announceAway,
                    onCancelAway = viewModel::cancelAway,
                    onClose = { navController.popBackStack() },
                )
            }
        }
        composable<AlarmRoute> {
            WithView(view) { AlarmScreen(it, onResolve = viewModel::resolve, onClose = { navController.popBackStack() }) }
        }
        composable<SosRoute> {
            WithView(view) { SosScreen(it, onSos = viewModel::sos, onBack = { navController.popBackStack() }) }
        }
        composable<FinishRouteRoute> {
            WithView(view) {
                FinishRouteScreen(
                    it,
                    onFinish = viewModel::finishRoute,
                    onStopService = viewModel::stopService,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable<PermissionsRoute> {
            PermissionsScreen(onDone = { navController.popBackStack() })
        }
        composable<DiagnosticsRoute> {
            DiagnosticsScreen()
        }
    }
}

/** Переход без возврата назад: после создания группы или вступления кнопка «Назад» не ведёт на старт. */
private fun NavHostController.replaceStack(route: Any) {
    navigate(route) { popUpTo(graph.id) { inclusive = true } }
}

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
