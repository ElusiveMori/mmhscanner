package net.lmoriarty.scanner

import sx.blah.discord.handle.obj.IChannel
import sx.blah.discord.handle.obj.IMessage

/**
 * Represents controls of multiple notifications posted in a channel,
 * and their lifecycle
 */
class NotificationMessagesHolder(val channel: IChannel) {
    private val notificationMessages = HashMap<Long, IMessage>()

    /**
     * Sends a notification (once) for this game info
     */
    fun sendNotification(info: GameInfo, mention: String): Boolean {
        synchronized(this) {
            if (info.id !in notificationMessages) {
                notificationMessages[info.id] = makeRequest {
                    channel.sendMessage("${mention} A game has been hosted! `${info.name}`")
                }

                return true
            }

            return false
        }
    }

    /**
     * Removes the notification associated with this game info
     */
    fun removeNotification(info: GameInfo) {
        synchronized(this) {
            val message = notificationMessages[info.id]

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