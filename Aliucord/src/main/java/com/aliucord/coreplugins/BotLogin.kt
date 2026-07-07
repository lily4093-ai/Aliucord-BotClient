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
import com.discord.models.user.MeUser
import com.discord.utilities.rest.RestAPI
import com.discord.views.CheckedSetting
import com.google.gson.Gson
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlin.jvm.functions.Function0

private fun XC_MethodHook.MethodHookParam.callOriginal(): Any? =
    XposedBridge.invokeOriginalMethod(method, thisObject, args)

/**
 * Makes bot-token sessions (see [TokenLogin]) speak the Bot Gateway/REST protocol instead of the
 * normal client one: "Bot <token>" auth header, and an IDENTIFY payload built from [BotSession.intents]
 * instead of the client's presence/capabilities/client_state.
 *
 * Regular user-token sessions are completely unaffected; every patch here checks [BotSession.isBot]
 * and otherwise defers to the original implementation via [XposedBridge.invokeOriginalMethod].
 */
internal class BotLogin : CorePlugin(Manifest("BotLogin")) {
    // Not hidden: unlike most core plugins this one has a user-facing settingsTab (the intents
    // picker), and hidden core plugins are filtered out of the Plugins list entirely, which would
    // make that settings page unreachable. See PluginManager.getVisiblePlugins().
    init {
        manifest.description = "Speaks the Bot Gateway/REST protocol for sessions logged in with a bot token"
        settingsTab = SettingsTab(IntentsSheet::class.java, SettingsTab.Type.BOTTOM_SHEET)
    }

    override fun start(context: Context) {
        patchAuthHeader()
        patchGatewayIdentify()
        patchAgeGate()
    }

    override fun stop(context: Context) = patcher.unpatchAll()

    private fun patchAuthHeader() {
        // Every REST request (Aliucord's own helpers and Discord's real RequiredHeadersInterceptor)
        // reads the Authorization header value from here.
        patcher.instead<RestAPI.AppHeadersProvider>("getAuthToken") { param ->
            val token = param.callOriginal() as String?
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
            if (!BotSession.isBot) return@instead param.callOriginal()

            @Suppress("UNCHECKED_CAST")
            val identifyDataProvider = ReflectUtils.getField(this, "identifyDataProvider") as Function0<GatewaySocket.IdentifyData?>
            val identifyData = identifyDataProvider.invoke() ?: return@instead param.callOriginal()

            @Suppress("UNCHECKED_CAST")
            val properties = ReflectUtils.getField(this, "identifyProperties") as Map<String, Any?>
            val token = identifyData.token

            // Mirror the bookkeeping the original doIdentify() does so heartbeat/resume logic
            // downstream still sees consistent state.
            ReflectUtils.setField(this, "seq", 0)
            ReflectUtils.setField(this, "sessionId", null)
            ReflectUtils.setField(this, "connectionState", 3) // GatewaySocket.IDENTIFYING
            ReflectUtils.setField(this, "identifyStartTime", System.currentTimeMillis())
            ReflectUtils.setField(this, "token", token)

            val outgoing = Outgoing(Opcode.IDENTIFY, BotIdentifyPayload(token, properties, BotSession.intents))
            val send = GatewaySocket::class.java.getDeclaredMethod(
                "send", Outgoing::class.java, Boolean::class.javaPrimitiveType, Gson::class.java
            ).apply { isAccessible = true }
            send.invoke(this, outgoing, false, Gson())
        }
    }

    // Bot accounts never go through the normal registration birthday flow, so MeUser.hasBirthday
    // is permanently false for them. StoreAuthentication.getShouldShowAgeGate() gates on exactly
    // that flag (for accounts created after 2021-02-05) to decide whether to show the
    // "help make Discord safer" age-verification screen, which a bot session can never satisfy -
    // it would just loop forever. Reporting hasBirthday=true for bot sessions skips the gate.
    private fun patchAgeGate() {
        patcher.instead<MeUser>("getHasBirthday") { param ->
            if (BotSession.isBot) true else param.callOriginal()
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
