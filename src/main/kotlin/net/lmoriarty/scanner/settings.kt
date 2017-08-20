package net.lmoriarty.scanner

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

private const val tokenPlaceholder = "PUT_TOKEN_HERE"

/**
 * Stores settings as retrieved/written to the JSON file.
 */
object Settings {
    data class NotificationChannel(var types: MutableSet<GameType> = HashSet())
    data class ChatBotSettings(var token: String = tokenPlaceholder,
                               var channels: MutableMap<Long, NotificationChannel> = HashMap(),
                               var owner: Long = 0)

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val settingsFile = File("settings.json")

    private var settings = ChatBotSettings()
    var token: String
        get() = settings.token
        set(value) {settings.token = value}
    var channels: MutableMap<Long, NotificationChannel>
        get() = settings.channels
        set(value) {settings.channels = value}
    var owner: Long
        get() = settings.owner
        set(value) {settings.owner = value}

    init {
        if (settingsFile.createNewFile()) {
            writeSettings()
        } else {
            readSettings()
        }

        if (settings.token == tokenPlaceholder) throw InvalidDiscordTokenException("Replace placeholder!")
    }

    fun readSettings() {
        settingsFile.bufferedReader().use {
            val settings = gson.fromJson<ChatBotSettings>(it)
            token = settings.token
            channels = HashMap(settings.channels)
            owner = settings.owner
            log.info("Read settings:\n" + this)
        }
    }

    fun writeSettings() {
        settingsFile.bufferedWriter().use {
            gson.toJson(ChatBotSettings(token, channels, owner), it)
            log.info("Wrote settings:\n" + this)
        }
    }
}
