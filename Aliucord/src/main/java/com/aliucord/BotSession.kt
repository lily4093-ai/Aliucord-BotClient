package com.aliucord

import com.aliucord.api.SettingsAPI
import com.aliucord.settings.delegate

/**
 * Tracks whether the currently logged in session was started with a bot token rather than a
 * regular user token, and which gateway intents that bot session should identify with.
 *
 * This is intentionally kept separate from Discord's own auth/session classes (which have no
 * concept of "bot sessions" on the client) so bot-mode state survives app restarts without
 * requiring any patch to those classes.
 */
object BotSession {
    private val settings = SettingsAPI("BotSession")

    var isBot by settings.delegate("isBot", false)
    var intents by settings.delegate("intents", DEFAULT_INTENTS)

    // https://discord.com/developers/docs/events/gateway#gateway-intents
    const val INTENT_GUILDS = 1 shl 0
    const val INTENT_GUILD_MEMBERS = 1 shl 1
    const val INTENT_GUILD_MESSAGES = 1 shl 9
    const val INTENT_DIRECT_MESSAGES = 1 shl 12
    const val INTENT_MESSAGE_CONTENT = 1 shl 15

    const val DEFAULT_INTENTS = INTENT_GUILDS or INTENT_GUILD_MESSAGES or INTENT_MESSAGE_CONTENT
}
