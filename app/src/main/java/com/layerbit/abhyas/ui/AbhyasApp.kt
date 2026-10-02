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

        composable(Routes.DECK, arguments = listOf(navArgument("deckId") { type = NavType.LongType })) {
            val deckId = it.arguments?.getLong("deckId") ?: return@composable
            DeckScreen(
                deckId = deckId,
                onStudy = { navController.navigate(Routes.study(deckId)) },
                onAddCards = { navController.navigate(Routes.capture(deckId)) },
                onBack = navController::popBackStackSafely,
                onDeleted = navController::popBackStackSafely
            )
        }

        composable(Routes.STUDY, arguments = listOf(navArgument("deckId") { type = NavType.LongType })) {
            val deckId = it.arguments?.getLong("deckId") ?: return@composable
            StudyScreen(deckId = deckId, onDone = navController::popBackStackSafely)
        }

        composable(Routes.CAPTURE, arguments = listOf(navArgument("deckId") { type = NavType.LongType })) {
            val deckId = it.arguments?.getLong("deckId") ?: return@composable
            CaptureScreen(deckId = deckId, onDone = navController::popBackStackSafely)
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = navController::popBackStackSafely)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = navController::popBackStackSafely,
                onAbout = { navController.navigate(Routes.ABOUT) }
            )
        }

        composable(Routes.INSIGHTS) {
            InsightsScreen(onBack = navController::popBackStackSafely)
        }

        composable(Routes.SEARCH) {
            SearchScreen(onBack = navController::popBackStackSafely)
        }
    }
}

/**
 * Back that cannot pop the last entry.
 *
 * A double tap on a Done button fires the callback twice, and the second pop would empty the back
 * stack and leave a blank Activity behind. Guarding here rather than debouncing every call site.
 */
private fun NavHostController.popBackStackSafely() {
    if (previousBackStackEntry != null) popBackStack()
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
