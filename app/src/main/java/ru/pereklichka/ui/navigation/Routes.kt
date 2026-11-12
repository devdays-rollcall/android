package ru.pereklichka.ui.navigation

import kotlinx.serialization.Serializable

// Маршруты навигации для AppNavHost, по одному на экран. Что на каждом экране, написано в KDoc экрана
// и в docs/team/valentin-ui.md.

@Serializable
object StartRoute

@Serializable
object CreateGroupRoute

@Serializable
object LobbyRoute

@Serializable
object JoinRoute

@Serializable
object GroupRoute

@Serializable
data class MemberRoute(val memberId: String)

@Serializable
object RollcallRoute

@Serializable
object AwayRoute

@Serializable
object AlarmRoute

@Serializable
object SosRoute

@Serializable
object FinishRouteRoute

@Serializable
object PermissionsRoute

@Serializable
object SettingsRoute

@Serializable
object DevRoute
