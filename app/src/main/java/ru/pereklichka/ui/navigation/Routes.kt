package ru.pereklichka.ui.navigation

import kotlinx.serialization.Serializable

// Маршруты навигации. Экраны и их поля перечислены в docs/team/valentin-ui.md, раздел «Экраны».

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
object DiagnosticsRoute
