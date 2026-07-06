package com.aliucord.coreplugins

import android.content.Context
import android.os.Bundle
import android.view.View
import com.aliucord.BotSession
import com.aliucord.Utils
import com.aliucord.entities.CorePlugin
import com.aliucord.patcher.instead
import com.aliucord.utils.ReflectUtils
import com.aliucord.utils.ViewUtils.addTo
import com.aliucord.widgets.BottomSheet
import com.discord.gateway.GatewaySocket
import com.discord.gateway.io.Outgoing
import com.discord.gateway.opcodes.Opcode
import com.discord.utilities.rest.RestAPI
import com.discord.views.CheckedSetting
import com.google.gson.Gson
import de.robv.android.xposed.XposedBridge
import kotlin.jvm.functions.Function0

/**
 * Makes bot-token sessions (see [TokenLogin]) speak the Bot Gateway/REST protocol instead of the
 * normal client one: "Bot <token>" auth header, and an IDENTIFY payload built from [BotSession.intents]
 * instead of the client's presence/capabilities/client_state.
 *
 * Regular user-token sessions are completely unaffected; every patch here checks [BotSession.isBot]
 * and otherwise defers to the original implementation via [XposedBridge.invokeOriginalMethod].
 */
internal class BotLogin : CorePlugin(Manifest("BotLogin")) {
    override val isHidden = true

    init {
        manifest.description = "Speaks the Bot Gateway/REST protocol for sessions logged in with a bot token"
        settingsTab = SettingsTab(IntentsSheet::class.java, SettingsTab.Type.BOTTOM_SHEET)
    }

    override fun start(context: Context) {
        patchAuthHeader()
        patchGatewayIdentify()
    }

    override fun stop(context: Context) = patcher.unpatchAll()

    private fun patchAuthHeader() {
        // Every REST request (Aliucord's own helpers and Discord's real RequiredHeadersInterceptor)
        // reads the Authorization header value from here.
        patcher.instead<RestAPI.AppHeadersProvider>("getAuthToken") { param ->
            val token = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args) as String?
            if (BotSession.isBot && token != null) "Bot $token" else token
        }
    }

    // Bot Gateway IDENTIFY shape (op 2): only token/properties/intents.
    // https://discord.com/developers/docs/events/gateway#identify
    private data class BotIdentifyPayload(
        val token: String,
        val properties: Map<String, Any?>,
        val intents: Int,
    )

    private fun patchGatewayIdentify() {
        patcher.instead<GatewaySocket>("doIdentify") { param ->
            if (!BotSession.isBot) {
                return@instead XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
            }

            val socket = param.thisObject as GatewaySocket

            @Suppress("UNCHECKED_CAST")
            val identifyDataProvider = ReflectUtils.getField(socket, "identifyDataProvider") as Function0<GatewaySocket.IdentifyData?>
            val identifyData = identifyDataProvider.invoke()
                ?: return@instead XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)

            @Suppress("UNCHECKED_CAST")
            val properties = ReflectUtils.getField(socket, "identifyProperties") as Map<String, Any?>
            val token = identifyData.token

            // Mirror the bookkeeping the original doIdentify() does so heartbeat/resume logic
            // downstream still sees consistent state.
            ReflectUtils.setField(socket, "seq", 0)
            ReflectUtils.setField(socket, "sessionId", null)
            ReflectUtils.setField(socket, "connectionState", 3) // GatewaySocket.IDENTIFYING
            ReflectUtils.setField(socket, "identifyStartTime", System.currentTimeMillis())
            ReflectUtils.setField(socket, "token", token)

            val outgoing = Outgoing(Opcode.IDENTIFY, BotIdentifyPayload(token, properties, BotSession.intents))
            val send = GatewaySocket::class.java.getDeclaredMethod(
                "send", Outgoing::class.java, Boolean::class.javaPrimitiveType, Gson::class.java
            ).apply { isAccessible = true }
            send.invoke(socket, outgoing, false, Gson())
        }
    }

    /** Lets the user pick which gateway intents a bot session identifies with. */
    class IntentsSheet : BottomSheet() {
        private data class IntentOption(val label: String, val bit: Int)

        private val options = listOf(
            IntentOption("Guilds", BotSession.INTENT_GUILDS),
            IntentOption("Guild messages", BotSession.INTENT_GUILD_MESSAGES),
            IntentOption("Message content", BotSession.INTENT_MESSAGE_CONTENT),
            IntentOption("Direct messages", BotSession.INTENT_DIRECT_MESSAGES),
            IntentOption("Guild members (privileged)", BotSession.INTENT_GUILD_MEMBERS),
        )

        override fun onViewCreated(view: View, bundle: Bundle?) {
            super.onViewCreated(view, bundle)

            for (option in options) {
                Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.CHECK, option.label, null).apply {
                    isChecked = BotSession.intents and option.bit != 0
                    setOnCheckedListener { checked ->
                        BotSession.intents = if (checked) BotSession.intents or option.bit else BotSession.intents and option.bit.inv()
                    }
                }.addTo(linearLayout)
            }
        }
    }
}
