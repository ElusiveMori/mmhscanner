package net.lmoriarty.scanner

import sx.blah.discord.handle.obj.IMessage
import kotlin.concurrent.timer

/**
 * Holds info about a single MMH bot account, as much as is relevant to us.
 */
data class GameInfo(var name: String, var associatedMessage: IMessage?, var updated: Boolean, var pattern: Regex)

/**
 * Scans the MMH roster and notifies the discord bot when it should post notifications.
 */
class Watcher {
    // key is mmh bot account name
    private val registry: MutableMap<String, GameInfo> = HashMap()
    // TODO: add loading from config, modification with commands
    private val patterns = listOf(
            Regex(".*rotrp.*"),
            Regex(".*aoc.*"),
            Regex(".*roleplay.*")
    )


    init {


        timer(period = 5000, action = {scan()})
    }

    /**
     * Returns which regex matched the game, if any
     * or null otherwise
     */
    private fun shouldWatch(row: GameRow): Regex? {
        for (pattern in patterns) {
            if (pattern.matches(row.currentGame.toLowerCase())) {
                return pattern
            }
        }

        return null
    }

    private fun scan() {
        try {
            val rows = extractRows().map { Pair(it, shouldWatch(it)) }.filter { it.second != null }
            registry.forEach { it.value.updated = false }

            for (row in rows) {
                val data = row.first
                val regex = row.second as Regex
                var info = registry[row.first.botName]

                if (info != null) {
                    if (info.pattern == regex) {
                        if (info.name != data.currentGame) {
                            info.name = data.currentGame
                            log.info("Game is updated: " + data.currentGame)
                            // TODO: update message/post new one
                        }
                    } else {
                        info = null
                    }
                }

                if (info == null) {
                    log.info("Game created: " + data.currentGame)
                    // TODO: send message
                    info = GameInfo(data.currentGame, null, false, regex)
                    registry[data.botName] = info
                }

                info.updated = true
            }

            registry.filter {
                if (!it.value.updated) {
                    log.info("Game unhosted: " + it.value.name)
                    return@filter false
                }

                return@filter true
            }

        } catch (e: MakeMeHostConnectException) {
            log.warn(e)
        }

    }

}