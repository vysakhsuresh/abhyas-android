package com.layerbit.abhyas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.layerbit.abhyas.AbhyasApplication
import com.layerbit.abhyas.data.repo.AbhyasRepository
import com.layerbit.abhyas.ui.about.AboutScreen
import com.layerbit.abhyas.ui.capture.CaptureScreen
import com.layerbit.abhyas.ui.deck.DeckScreen
import com.layerbit.abhyas.ui.decks.DecksScreen
import com.layerbit.abhyas.ui.insights.InsightsScreen
import com.layerbit.abhyas.ui.search.SearchScreen
import com.layerbit.abhyas.ui.settings.SettingsScreen
import com.layerbit.abhyas.ui.study.StudyScreen
import com.layerbit.abhyas.ui.theme.AbhyasColors

object Routes {
    const val DECKS = "decks"
    const val ABOUT = "about"
    const val SETTINGS = "settings"
    const val INSIGHTS = "insights"
    const val SEARCH = "search"
    const val DECK = "deck/{deckId}"
    const val STUDY = "study/{deckId}"
    const val CAPTURE = "capture/{deckId}"

    fun deck(id: Long) = "deck/$id"
    fun study(id: Long) = "study/$id"
    fun capture(id: Long) = "capture/$id"
}

@Composable
fun AbhyasApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.DECKS,
        modifier = Modifier.fillMaxSize().background(AbhyasColors.Background)
    ) {
        composable(Routes.DECKS) {
            DecksScreen(
                onOpenDeck = { navController.navigate(Routes.deck(it)) },
                onAbout = { navController.navigate(Routes.ABOUT) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onInsights = { navController.navigate(Routes.INSIGHTS) },
                onSearch = { navController.navigate(Routes.SEARCH) }
            )
        }

        composable(Routes.DECK, arguments = listOf(navArgument("deckId") { type = NavType.LongType })) { entry ->
            val deckId = entry.arguments?.getLong("deckId") ?: return@composable
            DeckScreen(
                deckId = deckId,
                onStudy = { navController.navigate(Routes.study(deckId)) },
                onAddCards = { navController.navigate(Routes.capture(deckId)) },
                onBack = { entry.popFrom(navController) },
                onDeleted = { entry.popFrom(navController) }
            )
        }

        composable(Routes.STUDY, arguments = listOf(navArgument("deckId") { type = NavType.LongType })) { entry ->
            val deckId = entry.arguments?.getLong("deckId") ?: return@composable
            StudyScreen(deckId = deckId, onDone = { entry.popFrom(navController) })
        }

        composable(Routes.CAPTURE, arguments = listOf(navArgument("deckId") { type = NavType.LongType })) { entry ->
            val deckId = entry.arguments?.getLong("deckId") ?: return@composable
            CaptureScreen(deckId = deckId, onDone = { entry.popFrom(navController) })
        }

        composable(Routes.ABOUT) { entry ->
            AboutScreen(onBack = { entry.popFrom(navController) })
        }

        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(
                onBack = { entry.popFrom(navController) },
                onAbout = { navController.navigate(Routes.ABOUT) }
            )
        }

        composable(Routes.INSIGHTS) { entry ->
            InsightsScreen(onBack = { entry.popFrom(navController) })
        }

        composable(Routes.SEARCH) { entry ->
            SearchScreen(onBack = { entry.popFrom(navController) })
        }
    }
}

/**
 * Go back from *this* screen, exactly once.
 *
 * A double tap on a Close or Done button fires the callback twice: popBackStack updates the back
 * queue synchronously, but NavHost keeps the outgoing composable composed and hit-testable for the
 * length of its exit transition, so the second tap lands on a screen that has already left.
 *
 * The previous guard tested `previousBackStackEntry != null`, which only prevents emptying the stack
 * - not what the double tap actually does. From the study screen, the first pop lands on the deck,
 * whose own previous entry is the decks list and still non-null, so the second pop fired too and the
 * user was thrown past the deck they had just been studying.
 *
 * Checking the entry that owns the callback is what discriminates, and it has to be equality with
 * RESUMED rather than `isAtLeast(STARTED)`: a popped entry is held at STARTED while its exit
 * transition runs, which is precisely the window the second tap arrives in.
 */
private fun NavBackStackEntry.popFrom(navController: NavHostController) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) navController.popBackStack()
}

/**
 * Build a ViewModel that needs the repository, and optionally the deck it is scoped to.
 *
 * Keeps every screen's `viewModel(...)` call to one line without pulling in a DI framework for
 * what is, at this size, four dependencies.
 */
@Composable
inline fun <reified VM : ViewModel> repositoryViewModel(
    key: String? = null,
    crossinline create: (AbhyasRepository) -> VM
): VM {
    val app = LocalContext.current.applicationContext as AbhyasApplication
    return viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { create(app.repository) }
        }
    )
}
