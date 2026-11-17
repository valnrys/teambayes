package io.github.valnrys.teambayes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.valnrys.teambayes.firstrun.FirstRunRoute
import io.github.valnrys.teambayes.matchday.MatchDayRoute
import kotlinx.coroutines.flow.first

/** The app's top-level screens. */
enum class AppScreen {
    /** No players stored yet: add and rank them. */
    FIRST_RUN,

    /** The everyday screen: pick who's playing and make teams. */
    MATCH_DAY,
}

/**
 * The whole app: first run if no players are stored yet, match day otherwise.
 *
 * **Why no Navigation library.** There are two screens and one way between them (first
 * run → match day, once). One remembered value covers that. A navigation library earns
 * its place once there's a back stack to manage, such as a players list or settings.
 *
 * **How it decides.** The first time it is shown it reads the stored players once. It
 * doesn't keep watching them, so a player added on match day can't flip the screen, and
 * first run moves on only when it says it's [done][FirstRunRoute]. The choice is kept with
 * [rememberSaveable], so it survives rotation and Android closing the app in the
 * background. While the database answers (a few milliseconds) nothing is drawn.
 *
 * @param container the app's shared database and repositories.
 */
@Composable
fun TeamBayesApp(container: AppContainer) {
    var screen by rememberSaveable { mutableStateOf<AppScreen?>(null) }
    LaunchedEffect(container) {
        if (screen == null) {
            val anyPlayers = container.playerRepository.players.first().isNotEmpty()
            screen = if (anyPlayers) AppScreen.MATCH_DAY else AppScreen.FIRST_RUN
        }
    }
    when (screen) {
        AppScreen.FIRST_RUN -> FirstRunRoute(
            repository = container.playerRepository,
            onDone = { screen = AppScreen.MATCH_DAY },
        )
        AppScreen.MATCH_DAY -> MatchDayRoute(container)
        null -> Unit
    }
}
