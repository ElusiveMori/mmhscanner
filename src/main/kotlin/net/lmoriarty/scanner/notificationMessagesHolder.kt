package net.lmoriarty.scanner

import sx.blah.discord.handle.obj.IChannel
import sx.blah.discord.handle.obj.IMessage

/**
 * Represents controls of multiple notifications posted in a channel,
 * and their lifecycle
 */
class NotificationMessagesHolder(val channel: IChannel) {
    private val notificationMessages = HashMap<String, IMessage>()

    /**
     * Sends a notification (once) for this game info
     */
    fun sendNotification(info: GameInfo) {
        synchronized(this) {
            if (info.botName !in notificationMessages) {
                notificationMessages[info.botName] = makeRequest {
                    channel.sendMessage("@here A game has been hosted! `${info.name}`")
                }
            }
        }
    }

    /**
     * Removes the notification associated with this game info
     */
    fun removeNotification(info: GameInfo) {
        synchronized(this) {
            val message = notificationMessages[info.botName]

            if (message != null) {
                message.delete()
            }
        }
    }

    /**
     * Tells if the specified message is tracked by
     * this NotificationMessagesHolder
     */
    fun isMessageTracked(message: IMessage): Boolean {
        synchronized(this) {
            return notificationMessages.containsValue(message)
        }
    }

    fun shutdown() {
        synchronized(this) {
            notificationMessages.clear()
        }
    }
}