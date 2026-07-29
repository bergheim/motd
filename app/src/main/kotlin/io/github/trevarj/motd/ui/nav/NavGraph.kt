package io.github.trevarj.motd.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.trevarj.motd.ui.about.AboutScreen
import io.github.trevarj.motd.ui.channelinfo.ChannelInfoScreen
import io.github.trevarj.motd.ui.channellist.ChannelListScreen
import io.github.trevarj.motd.ui.chat.ChatScreen
import io.github.trevarj.motd.ui.chatlist.ChatListScreen
import io.github.trevarj.motd.ui.imageviewer.ImageViewerScreen
import io.github.trevarj.motd.ui.onboarding.OnboardingScreen
import io.github.trevarj.motd.ui.search.SearchScreen
import io.github.trevarj.motd.ui.settings.AppearanceSettingsScreen
import io.github.trevarj.motd.ui.settings.BackupRestoreScreen
import io.github.trevarj.motd.ui.settings.ChatSettingsScreen
import io.github.trevarj.motd.ui.settings.DeliverySettingsScreen
import io.github.trevarj.motd.ui.settings.DirectConnectionsScreen
import io.github.trevarj.motd.ui.settings.ManageNicksScreen
import io.github.trevarj.motd.ui.settings.NetworkSettingsScreen
import io.github.trevarj.motd.ui.settings.NetworkToolsScreen
import io.github.trevarj.motd.ui.settings.NetworksSettingsScreen
import io.github.trevarj.motd.ui.settings.NickListKind
import io.github.trevarj.motd.ui.settings.SettingsScreen
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkScreen
import io.github.trevarj.motd.ui.settings.addnetwork.ProtocolPickerScreen
import io.github.trevarj.motd.ui.settings.bouncer.BouncerNetworksScreen
import io.github.trevarj.motd.ui.settings.xmpp.XmppAccountScreen
import io.github.trevarj.motd.ui.theme.MotdMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App navigation graph. Routes come from [Routes.kt] (frozen). Each destination is wired to its
 * screen composable; WP7/WP8 fill in their own screen bodies behind these signatures.
 */
// Material shared-axis X feel: forward pushes the new screen in from the right and the old one out
// to the left; back reverses it. Chat uses a drawer-style transition: only the chat surface moves,
// while the adjacent destination stays stationary beneath it. This avoids transforming two full
// Compose trees while the first Room and Paging emissions arrive.
@Composable
fun MotdNavGraph(
    navController: NavHostController = rememberNavController(),
    // Notification-tap deep-link: open the buffer and jump to the message. Null when absent.
    notificationTarget: NotificationTarget? = null,
    onNotificationTargetHandled: () -> Unit = {},
) {
    // Registry-driven account-entry dispatch (docs/backend-neutral-xmpp-rollout.md): resolves
    // protocol -> route for both the add-account entry point and existing-row "network settings"
    // navigation, so this graph names no protocol beyond the route types it registers below.
    val accountRouting: AccountRoutingViewModel = hiltViewModel()
    val accountRoutingScope = rememberCoroutineScope()

    // Route a notification tap to ChatRoute so the existing jump path (local resolve → CHATHISTORY
    // AROUND fallback) scrolls to and highlights the message. Runs for both cold start (target
    // seeded before first composition) and warm start (target updated by onNewIntent). Clearing the
    // target after navigating lets a subsequent identical tap re-trigger (null → value transition).
    LaunchedEffect(notificationTarget) {
        val target = notificationTarget ?: return@LaunchedEffect
        navController.navigate(
            ChatRoute(
                target.bufferId,
                target.jumpToMsgid,
                target.jumpToTime,
                target.jumpToEventId,
            ),
        ) {
            launchSingleTop = true
        }
        onNotificationTargetHandled()
    }
    NavHost(
        navController = navController,
        startDestination = ChatListRoute,
        enterTransition = {
            if (isChatTarget() && isChatInitial()) {
                EnterTransition.None
            } else if (isChatTarget()) {
                slideIntoContainer(SlideDirection.Start, MotdMotion.navigationDrawerSpatial)
            } else {
                slideIntoContainer(SlideDirection.Start, tween(MotdMotion.NavigationDurationMs))
            }
        },
        exitTransition = {
            if (isChatTarget() && isChatInitial()) {
                ExitTransition.None
            } else if (isChatTarget()) {
                // Keep the current screen in place until the incoming chat finishes, mirroring
                // ModalNavigationDrawer's single moving surface over stationary content.
                ExitTransition.KeepUntilTransitionsFinished
            } else {
                slideOutOfContainer(SlideDirection.Start, tween(MotdMotion.NavigationDurationMs))
            }
        },
        popEnterTransition = {
            if (isChatInitial()) {
                // The destination is already visible beneath the outgoing chat surface.
                EnterTransition.None
            } else {
                slideIntoContainer(SlideDirection.End, tween(MotdMotion.NavigationDurationMs))
            }
        },
        popExitTransition = {
            if (isChatInitial()) {
                slideOutOfContainer(SlideDirection.End, MotdMotion.chatBackSpatial)
            } else {
                slideOutOfContainer(SlideDirection.End, tween(MotdMotion.NavigationDurationMs))
            }
        },
    ) {
        composable<ChatListRoute> {
            var openedDefault by rememberSaveable { mutableStateOf(false) }
            ChatWorkspace(
                listPane = { twoPane ->
                    ChatListPane(
                        navController = navController,
                        accountRouting = accountRouting,
                        accountRoutingScope = accountRoutingScope,
                        onDefaultBufferAvailable = { bufferId ->
                            if (twoPane && !openedDefault) {
                                openedDefault = true
                                navController.openChat(ChatRoute(bufferId), replaceCurrentChat = false)
                            }
                        },
                    )
                },
            )
        }
        composable<ChatRoute> { entry ->
            val route = entry.toRoute<ChatRoute>()
            ChatWorkspace(
                listPane = {
                    ChatListPane(
                        navController = navController,
                        accountRouting = accountRouting,
                        accountRoutingScope = accountRoutingScope,
                        selectedBufferId = route.bufferId,
                        replaceCurrentChat = true,
                    )
                },
                detailPane = { showBack ->
                    ChatScreen(
                        bufferId = route.bufferId,
                        onBack = { navController.popBackStack() },
                        showBack = showBack,
                        onOpenChannelInfo = { navController.navigate(ChannelInfoRoute(it)) },
                        onOpenSearch = { navController.navigate(SearchRoute(it)) },
                        onOpenImage = { navController.navigate(ImageViewerRoute(it)) },
                        // /msg and /query replace the detail on wide layouts and push on phones.
                        onOpenBuffer = {
                            navController.openChat(ChatRoute(it), replaceCurrentChat = !showBack)
                        },
                        onOpenAudioOrigin = { origin ->
                            navController.openChat(
                                ChatRoute(origin.bufferId, origin.msgid, origin.serverTime, origin.eventId),
                                replaceCurrentChat = !showBack,
                            )
                        },
                        onOpenChannelList = { navController.navigate(ChannelListRoute(it)) },
                    )
                },
            )
        }
        composable<OnboardingRoute> {
            // Finish lands on a fresh ChatList and clears onboarding (plus any duplicate
            // onboarding entries) from the backstack; a bare popBackStack could fall back to
            // the Welcome step instead of the chat list.
            OnboardingScreen(onDone = {
                navController.navigate(ChatListRoute) {
                    popUpTo<ChatListRoute> { inclusive = true }
                    launchSingleTop = true
                }
            })
        }
        composable<SettingsRoute> {
            // Top-level Settings: category rows opening the focused sub-screens below.
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAppearance = { navController.navigate(AppearanceSettingsRoute) },
                onOpenChat = { navController.navigate(ChatSettingsRoute) },
                onOpenDelivery = { navController.navigate(DeliverySettingsRoute) },
                onOpenNetworks = { navController.navigate(NetworksSettingsRoute) },
                onOpenBackupRestore = { navController.navigate(BackupRestoreRoute) },
                onOpenAbout = { navController.navigate(AboutRoute) },
            )
        }
        composable<AppearanceSettingsRoute> {
            AppearanceSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenNickColors = { navController.navigate(NickColorsRoute) },
            )
        }
        composable<ChatSettingsRoute> {
            ChatSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenFriends = { navController.navigate(FriendsRoute) },
                onOpenFools = { navController.navigate(FoolsRoute) },
                onOpenDirectConnections = { navController.navigate(DirectConnectionsRoute) },
            )
        }
        composable<DirectConnectionsRoute> {
            DirectConnectionsScreen(onBack = { navController.popBackStack() })
        }
        composable<DeliverySettingsRoute> {
            DeliverySettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<NetworksSettingsRoute> {
            NetworksSettingsScreen(
                onBack = { navController.popBackStack() },
                // Registry-driven, mirroring ChatListRoute's drawer entry points above.
                onOpenNetwork = { id ->
                    accountRoutingScope.launch { navController.navigate(accountRouting.editRouteFor(id)) }
                },
                onOpenAddNetwork = { navController.navigate(accountRouting.createDestination()) },
            )
        }
        composable<BackupRestoreRoute> {
            BackupRestoreScreen(onBack = { navController.popBackStack() })
        }
        composable<FriendsRoute> {
            ManageNicksScreen(NickListKind.FRIENDS, onBack = { navController.popBackStack() })
        }
        composable<FoolsRoute> {
            ManageNicksScreen(NickListKind.FOOLS, onBack = { navController.popBackStack() })
        }
        composable<NickColorsRoute> {
            ManageNicksScreen(NickListKind.COLORS, onBack = { navController.popBackStack() })
        }
        composable<NetworkSettingsRoute> { entry ->
            val route = entry.toRoute<NetworkSettingsRoute>()
            NetworkSettingsScreen(
                networkId = route.networkId,
                onBack = { navController.popBackStack() },
                // Round 5: soju root -> bouncer manager; "Server messages" -> the SERVER buffer.
                onOpenBouncerNetworks = { navController.navigate(BouncerNetworksRoute(it)) },
                onOpenBuffer = { navController.navigate(ChatRoute(it)) },
                onOpenNetworkTools = { navController.navigate(NetworkToolsRoute(it)) },
            )
        }
        composable<NetworkToolsRoute> { entry ->
            val route = entry.toRoute<NetworkToolsRoute>()
            NetworkToolsScreen(
                networkId = route.networkId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<SearchRoute> { entry ->
            val route = entry.toRoute<SearchRoute>()
            SearchScreen(
                bufferId = route.bufferId,
                onBack = { navController.popBackStack() },
                onOpenHit = { bufferId, msgid, time, eventId ->
                    navController.navigate(ChatRoute(bufferId, msgid, time, eventId))
                },
            )
        }
        composable<ChannelInfoRoute> { entry ->
            val route = entry.toRoute<ChannelInfoRoute>()
            ChannelInfoScreen(
                bufferId = route.bufferId,
                onBack = { navController.popBackStack() },
                // Member "Message" action opens the DM's QUERY buffer.
                onOpenBuffer = { navController.navigate(ChatRoute(it)) },
            )
        }
        composable<ImageViewerRoute>(
            // Full-screen image reads better appearing/dismissing in place than sliding sideways.
            enterTransition = { fadeIn(tween(MotdMotion.NavigationDurationMs)) },
            exitTransition = { fadeOut(tween(MotdMotion.NavigationDurationMs)) },
            popEnterTransition = { fadeIn(tween(MotdMotion.NavigationDurationMs)) },
            popExitTransition = { fadeOut(tween(MotdMotion.NavigationDurationMs)) },
        ) { entry ->
            val route = entry.toRoute<ImageViewerRoute>()
            ImageViewerScreen(url = route.url, onBack = { navController.popBackStack() })
        }
        composable<AboutRoute> {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        // Round 5 (plans/16 §5.1): app-shell / network-management destinations.
        composable<AddNetworkRoute> {
            AddNetworkScreen(
                onBack = { navController.popBackStack() },
                onOpenBouncerNetworks = { rootId ->
                    navController.navigate(BouncerNetworksRoute(rootId)) {
                        // The add-flow is replaced by the manager once the soju root exists.
                        popUpTo<AddNetworkRoute> { inclusive = true }
                    }
                },
            )
        }
        // Backend-neutral account entry points (docs/backend-neutral-xmpp-rollout.md): the picker
        // is reached only when accountRouting.createDestination() resolves to it (2+ backends
        // registered); each choice's route is one of this graph's other destinations.
        composable<AccountPickerRoute> {
            ProtocolPickerScreen(
                choices = accountRouting.createChoices,
                onBack = { navController.popBackStack() },
                onChoose = { route ->
                    navController.navigate(route) {
                        popUpTo<AccountPickerRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<XmppAccountRoute> { entry ->
            val route = entry.toRoute<XmppAccountRoute>()
            XmppAccountScreen(
                networkId = route.networkId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<BouncerNetworksRoute> { entry ->
            val route = entry.toRoute<BouncerNetworksRoute>()
            BouncerNetworksScreen(
                rootNetworkId = route.rootNetworkId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<ChannelListRoute> { entry ->
            val route = entry.toRoute<ChannelListRoute>()
            ChannelListScreen(
                networkId = route.networkId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun ChatListPane(
    navController: NavHostController,
    accountRouting: AccountRoutingViewModel,
    accountRoutingScope: CoroutineScope,
    selectedBufferId: Long? = null,
    replaceCurrentChat: Boolean = false,
    onDefaultBufferAvailable: (Long) -> Unit = {},
) {
    ChatListScreen(
        onOpenBuffer = {
            navController.openChat(ChatRoute(it), replaceCurrentChat)
        },
        onOpenAudioOrigin = { origin ->
            navController.openChat(
                ChatRoute(
                    bufferId = origin.bufferId,
                    jumpToMsgid = origin.msgid,
                    jumpToTime = origin.serverTime,
                    jumpToEventId = origin.eventId,
                ),
                replaceCurrentChat,
            )
        },
        onOpenSettings = { navController.navigate(SettingsRoute) },
        onOpenSearch = { navController.navigate(SearchRoute()) },
        onOpenOnboarding = { navController.navigate(OnboardingRoute) },
        // Registry-driven (docs/backend-neutral-xmpp-rollout.md), mirroring NetworksSettingsRoute
        // below instead of hardcoding IRC's routes.
        onOpenNetworkSettings = { id ->
            accountRoutingScope.launch { navController.navigate(accountRouting.editRouteFor(id)) }
        },
        onOpenAddNetwork = { navController.navigate(accountRouting.createDestination()) },
        onOpenChannelList = { navController.navigate(ChannelListRoute(it)) },
        selectedBufferId = selectedBufferId,
        onDefaultBufferAvailable = onDefaultBufferAvailable,
    )
}

private fun NavHostController.openChat(route: ChatRoute, replaceCurrentChat: Boolean) {
    navigate(route) {
        if (replaceCurrentChat) popUpTo<ChatRoute> { inclusive = true }
        launchSingleTop = true
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isChatTarget(): Boolean =
    isChatRoutePattern(targetState.destination.route)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isChatInitial(): Boolean =
    isChatRoutePattern(initialState.destination.route)

internal fun isChatRoutePattern(route: String?): Boolean {
    val chatRouteName = ChatRoute::class.qualifiedName ?: return false
    return route == chatRouteName ||
        route?.startsWith("$chatRouteName/") == true ||
        route?.startsWith("$chatRouteName?") == true
}
