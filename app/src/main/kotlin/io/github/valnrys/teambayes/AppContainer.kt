package io.github.valnrys.teambayes

import android.content.Context
import io.github.valnrys.teambayes.data.GameRepository
import io.github.valnrys.teambayes.data.PlayerRepository
import io.github.valnrys.teambayes.data.RatingsRepository
import io.github.valnrys.teambayes.data.RoomGameRepository
import io.github.valnrys.teambayes.data.RoomPlayerRepository
import io.github.valnrys.teambayes.data.db.TeamBayesDatabase

/**
 * Holds the things the whole app shares: the database and the repositories on top of it.
 *
 * It is built once, by [TeamBayesApplication], and reached from anywhere with a [Context]
 * through `(context.applicationContext as TeamBayesApplication).container`. We wire it by
 * hand instead of using a dependency-injection library: there are only four objects.
 *
 * @property database the database everything is stored in. The app uses the file on the
 *   phone; tests pass an in-memory one, so each starts empty and leaves nothing behind.
 */
class AppContainer(val database: TeamBayesDatabase) {

    /** The app's own container, on the phone's database file. Room opens it on first use, not here. */
    constructor(context: Context) : this(TeamBayesDatabase.create(context))

    val playerRepository: PlayerRepository = RoomPlayerRepository(database)
    val gameRepository: GameRepository = RoomGameRepository(database)
    val ratingsRepository = RatingsRepository(playerRepository, gameRepository)
}
