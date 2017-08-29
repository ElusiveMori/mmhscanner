package net.lmoriarty.scanner

import sx.blah.discord.api.internal.json.objects.EmbedObject
import sx.blah.discord.handle.obj.IChannel
import sx.blah.discord.handle.obj.IMessage
import java.util.concurrent.*

/**
 * Represents controls of a single 'info message' - the message
 * being kept in the channel with updates.
 */
class InfoMessageHolder(val channel: IChannel) {
    private var requiresUpdate: Boolean = false
    private var infoMessage: IMessage? = null
    private var containedString: String? = null
    private var containedEmbed: EmbedObject? = null
    private val updateExecutor: ScheduledExecutorService
    private val cancellationExecutor: ExecutorService

    init {
        updateExecutor = Executors.newSingleThreadScheduledExecutor()
        cancellationExecutor = Executors.newSingleThreadExecutor()
    }

    private fun editInfoMessage() {
        synchronized(this) {
            val message = infoMessage

            if (message != null && !message.isDeleted) {
                makeRequest { message.edit(containedString, containedEmbed) }
            } else {
                postInfoMessage()
            }
        }
    }

    private fun postInfoMessage() {
        synchronized(this) {
            infoMessage?.delete() // delete if exists
            infoMessage = null
            infoMessage = makeRequest { channel.sendMessage(containedString, containedEmbed) }
        }
    }

    /**
     * We wait two seconds before updating the message, just to throttle it down a bit
     */
    private fun scheduleRenew() {
        cancellationExecutor.submit {
            val future = updateExecutor.schedule({
                postInfoMessage()
                requiresUpdate = false
            }, 2, TimeUnit.SECONDS)

            try {
                future.get(7, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
            }
        }
    }

    /**
     * Used to signal the info message should be 'renewed',
     * i.e. the old one deleted - and a new one posted instead.
     */
    fun renew() {
        synchronized(this) {
            if (!requiresUpdate) {
                requiresUpdate = true
                scheduleRenew()
            }
        }
    }

    /**
     * Used to signal the info message should be updated.
     */
    fun update(string: String?, embed: EmbedObject?) {
        synchronized(this) {
            containedString = string
            containedEmbed = embed

            editInfoMessage()
        }
    }

    /**
     * Tells if the specified message is the one tracked by
     * this InfoMessageHolder
     */
    fun isMessageTracked(message: IMessage): Boolean {
        synchronized(this) {
            return message == infoMessage
        }
    }

    fun shutdown() {
        synchronized(this) {
            cancellationExecutor.shutdown()
            updateExecutor.shutdown()
        }
    }
}