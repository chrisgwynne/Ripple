package com.ripple.town

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ripple.town.core.ui.ProceduralSpriteProvider
import com.ripple.town.core.ui.RippleColors
import com.ripple.town.feature.history.HistoryScreen
import com.ripple.town.feature.news.NewsScreen
import com.ripple.town.feature.onboarding.OnboardingScreen
import com.ripple.town.feature.people.PeopleScreen
import com.ripple.town.feature.settings.SettingsSheet
import com.ripple.town.feature.town.TownScreen
import com.ripple.town.feature.town.TownViewModel

object Routes {
    const val TOWN = "town"
    const val PEOPLE = "people"
    const val NEWS = "news"
    const val HISTORY = "history"
}

private data class NavItem(val route: String, val label: String, val glyph: String)

private val NAV_ITEMS = listOf(
    NavItem(Routes.TOWN, "Town", "⌂"),
    NavItem(Routes.PEOPLE, "People", "☺"),
    NavItem(Routes.NEWS, "News", "✎"),
    NavItem(Routes.HISTORY, "History", "⧗")
)

@Composable
fun RippleApp(mainViewModel: MainViewModel = hiltViewModel()) {
    val appState by mainViewModel.appState.collectAsState()

    when (appState) {
        AppState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = RippleColors.WarmGreen)
        }
        AppState.NeedsOnboarding -> OnboardingScreen(onFinished = mainViewModel::onOnboardingFinished)
        AppState.Ready -> MainScaffold(mainViewModel)
    }
}

/** The four-destination navigation shell. Screens are slots so tests can stub them. */
@Composable
fun RippleNavScaffold(
    navController: NavHostController,
    town: @Composable () -> Unit,
    people: @Composable () -> Unit,
    news: @Composable () -> Unit,
    history: @Composable () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val backStack by navController.currentBackStackEntryAsState()
            val currentRoute = backStack?.destination?.route
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NAV_ITEMS.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick = {
                            if (currentRoute != item.route) {
                                navController.navigate(item.route) {
                                    popUpTo(Routes.TOWN) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Text(item.glyph, style = MaterialTheme.typography.titleLarge) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TOWN,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.TOWN) { town() }
            composable(Routes.PEOPLE) { people() }
            composable(Routes.NEWS) { news() }
            composable(Routes.HISTORY) { history() }
        }
    }
}

@Composable
private fun MainScaffold(mainViewModel: MainViewModel) {
    val navController: NavHostController = rememberNavController()
    val sprites = remember { ProceduralSpriteProvider() }
    // Shared so People/History can open sheets over the town.
    val townViewModel: TownViewModel = hiltViewModel()
    var showSettings by remember { mutableStateOf(false) }
    val showIntro by mainViewModel.showIntro.collectAsState()

    RippleNavScaffold(
        navController = navController,
        town = {
            TownScreen(
                sprites = sprites,
                onOpenSettings = { showSettings = true },
                viewModel = townViewModel
            )
        },
        people = {
            PeopleScreen(
                sprites = sprites,
                viewModel = townViewModel,
                onOpenResident = { id ->
                    townViewModel.openResident(id)
                    navController.navigate(Routes.TOWN) { launchSingleTop = true }
                }
            )
        },
        news = { NewsScreen() },
        history = {
            HistoryScreen(
                onOpenEvent = { id ->
                    townViewModel.openEvent(id)
                    navController.navigate(Routes.TOWN) { launchSingleTop = true }
                }
            )
        }
    )

    if (showSettings) {
        SettingsSheet(onDismiss = { showSettings = false })
    }

    if (showIntro) {
        IntroDialog(mainViewModel, townViewModel)
    }
}

/** First-launch introduction + the one free demo nudge. */
@Composable
private fun IntroDialog(mainViewModel: MainViewModel, townViewModel: TownViewModel) {
    val world by townViewModel.world.collectAsState()
    val followed = world?.resident(world?.followedResidentId)
    AlertDialog(
        onDismissRequest = { mainViewModel.introDoNothing() },
        title = { Text("This is ${followed?.name ?: "someone worth watching"}") },
        text = {
            Column {
                if (followed != null) {
                    Text(
                        "Age ${followed.age}. Lives at ${followed.homeName ?: "the edge of town"}. " +
                            "Works as ${followed.occupation.lowercase()}" +
                            (followed.employerName?.let { " at $it" } ?: "") + ".",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "\nYou don't control anyone here. You watch, and very occasionally, you nudge." +
                        "\n\nRight now ${followed?.firstName ?: "they"} is about to go about their day. " +
                        "You could hold them up for five minutes — or leave the world alone. " +
                        "Either way, what follows is not up to you.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { mainViewModel.introDelayChoice() }) { Text("Delay them (free)") }
        },
        dismissButton = {
            TextButton(onClick = { mainViewModel.introDoNothing() }) { Text("Do nothing") }
        }
    )
}
