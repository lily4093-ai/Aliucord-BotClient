# Aliucord (fork: lily4093-ai/Aliucord)

Fork of https://github.com/Aliucord/Aliucord, cloned to `/home/codelounge/android/aliucord`.

## What this project actually is

This is a **runtime-hooking mod framework** (LSPlant-based) for the real Discord Android app, not a
reimplementation of Discord's client. Actual REST/gateway/auth logic lives inside Discord's own
proprietary, obfuscated code, which is **not present as source anywhere in this repo**. It's pulled
in only as a `compileOnly` binary dependency: `com.discord:discord:<version>` (version pinned in
`gradle/libs.versions.toml`), resolved from `https://maven.aliucord.com/releases`. The actual artifact
at that coordinate is a **de-obfuscated `.apk`**, not a jar (Reposilite 404s on `.jar` — use `.apk`).
`Injector/src/main/java/com/discord/...` only has a handful of small unrelated model stubs; it is not
a mapping of the real classes.

Everything Aliucord does to Discord's behavior is via `com.aliucord.patcher.Patcher`/`PatcherAPI`
(`patcher.before/after/instead<SomeDiscordClass>("methodName", ...)`), hooking obfuscated Discord
methods by name/signature — never by editing Discord's code directly (can't; it's not source here).

To find real method/field names inside Discord's obfuscated classes (needed to write a new hook),
decompile the actual app:
```bash
curl -sL -o discord.apk https://maven.aliucord.com/releases/com/discord/discord/<version>/discord-<version>.apk
# jadx from https://github.com/skylot/jadx/releases
jadx -d decompiled --no-res discord.apk
```
Do this in a throwaway container if you don't want a JDK/jadx/apk footprint on the host — see
`BotLogin` below for what was learned from a decompile pass done 2026-07-06 against version 126021.

## BotLogin feature (added 2026-07-06)

Added bot-token login as an alternate session type, without touching normal user-token login:

- `Aliucord/src/main/java/com/aliucord/BotSession.kt` — persisted `isBot` flag + `intents` bitmask
  (JSON-backed via `SettingsAPI`, independent of Discord's own session objects, which have no
  concept of "bot session").
- `Aliucord/src/main/java/com/aliucord/coreplugins/TokenLogin.java` — added a "Login as bot" checkbox
  next to the existing token-login field; sets `BotSession.isBot` before dispatching login.
- `Aliucord/src/main/java/com/aliucord/coreplugins/BotLogin.kt` — new core plugin, registered in
  `PluginManager.kt`:
  - Patches `RestAPI.AppHeadersProvider.getAuthToken()` (confirmed via decompile to be the single
    method `RequiredHeadersInterceptor`, i.e. Discord's real OkHttp interceptor for *all* app
    traffic, reads the Authorization header value from) to prefix `"Bot "` when `BotSession.isBot`.
  - Replaces `GatewaySocket.doIdentify()` (`instead` hook) when `BotSession.isBot`: builds a Bot
    Gateway IDENTIFY payload (`token`, `properties`, `intents`) instead of the client's
    `OutgoingPayload.Identify` (which has no `intents` field and always sends
    presence/capabilities/client_state — those don't exist in the payload class Aliucord/Discord's
    client code has, so a plain custom data class is sent as the `Outgoing.d` payload instead;
    `Outgoing`/`send()` serialize whatever object is given via Gson, no special typing required).
    Falls back to `XposedBridge.invokeOriginalMethod(...)` for normal user sessions, so user login
    is byte-for-byte unaffected.
  - Adds a bottom-sheet settings screen (checkboxes for GUILDS / GUILD_MESSAGES / MESSAGE_CONTENT /
    DIRECT_MESSAGES / GUILD_MEMBERS) to pick the intents bitmask, default = GUILDS + GUILD_MESSAGES +
    MESSAGE_CONTENT.

**Known gap, not implemented**: hiding user-only UI (Friends tab, relationships) for bot sessions.
`RemoveBilling.kt`'s existing Nitro-hiding patches already apply to bot sessions for free (bots are
never premium), but no research was done on friends-tab view IDs — resource strings weren't
decompiled (`--no-res` was used to keep the decompile fast), and guessing `findViewById` id strings
risks a null-pointer crash. If this matters, decompile with resources included and find the actual
`R.id` name for the friends tab / bottom nav, then hide it the same way `RemoveBilling.kt` does for
Nitro.

**Not verified by a real build/run** — no Android SDK/emulator available in this environment; changes
were written from decompiled evidence only. Build via GitHub Actions and test login with a real bot
token before trusting this in daily use. Gateway close code 4013 = invalid intents, 4014 = disallowed
intents (a requested privileged intent, e.g. GUILD_MEMBERS or MESSAGE_CONTENT, isn't enabled for the
bot in the Discord Developer Portal).
