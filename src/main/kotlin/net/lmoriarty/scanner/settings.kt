package net.lmoriarty.scanner

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

private val tokenPlaceholder = "PUT_TOKEN_HERE"

object Settings {
    data class ChatBotSettings(var token: String = tokenPlaceholder, var channels: MutableList<Long> = ArrayList())

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val settingsFile = File("settings.json")

    private var settings = ChatBotSettings()
    var token: String
        get() = settings.token
        set(value) {settings.token = value}
    var channels: MutableList<Long>
        get() = settings.channels
        set(value) {settings.channels = value}

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
            channels = ArrayList(settings.channels)
            log.info("Read settings:\n" + this)
        }
    }

    fun writeSettings() {
        settingsFile.bufferedWriter().use {
            gson.toJson(ChatBotSettings(token, channels), it)
            log.info("Wrote settings:\n" + this)
        }
    }
}
