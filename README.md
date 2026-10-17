# Sayu Minecraft Mod — Technical & User Guide

Sayu is a client-side AI integration mod for **Minecraft Java Edition 1.21.1** (Fabric Loader, Mojang mappings) that connects to your local OpenAI-compatible Sunless Gateway API (`http://localhost:5000/v1/chat/completions`).

The mod offers **two ways to talk to Sayu**:

1. A dedicated **GUI chat screen** opened with the **K** key (default, rebindable in `Options → Controls → Sayu`).
2. The legacy **`@sayu` chat command** path (kept as a fallback when you don't want to open the GUI).

Both paths share the same conversation history, HTTP pipeline, model, search, and thinking settings via a single `SayuService` controller — so messages sent via the GUI are visible in the `@sayu` path and vice versa.

---

## 1. Core Implementations & Architecture

* **Shared `SayuService` controller** — A thread-safe singleton that owns conversation history, the async HTTP pipeline to the gateway, and all model/search/thinking/memory state. Both the GUI and the legacy `@sayu` command path delegate to this single instance, so conversation state is consistent across both. No networking or history code is duplicated.
* **Dedicated `SayuChatScreen` GUI** — A custom `Screen` with a dark modern panel layout: header band (title + model/search/turn-count indicators + `M:` / `S:` cycle buttons), scrollable transcript (wrapped via `Font.split`, clipped via `GuiGraphics.enableScissor`), and a fixed composer area at the bottom (`EditBox` + `Send` + `Clear`, conditional `Retry` button).
* **Configurable keybinding (default K)** — Registered via Fabric's `KeyBindingHelper`. The actual click-polling that opens the screen happens in `MinecraftClientMixin` (HEAD injection on `Minecraft.tick()`), because this project's compile classpath does not include `fabric-events-api-v1`. Vanilla MC polls registered `KeyMapping`s in `Minecraft.handleKeybinds()`, accumulating `clickCount` only while no `Screen` is open — so pressing K while the Sayu screen (or any other screen) is open does nothing, preventing recursive opens.
* **Listener-based UI dispatch** — `SayuService` fires `onThinkingStart` / `onReplyComplete` / `onError` / `onHistoryCleared` / `onStateChanged` events. Every callback is marshalled onto the Minecraft client thread via `Minecraft.getInstance().execute(...)`. The legacy path installs a vanilla-chat listener that suppresses output while the GUI is open (no duplicate display); the GUI installs itself as a listener on `init()` and unregisters on `removed()`.
* **Lifecycle safety** — The HTTP request future is owned by `SayuService`, not by the screen. If you close the GUI mid-request, the request keeps running, appends the assistant reply to history exactly once, and the result is visible when you reopen the GUI. The success-path final state mutation (append assistant turn + clear `requestInFlight` + fire `onReplyComplete`) is queued as a single client-thread callback so the GUI never observes a half-applied state.
* **Asynchronous networking** — HTTP POST requests via Java's built-in `HttpClient#sendAsync` so Minecraft's render thread never blocks. Connect timeout 10s, request timeout 120s.
* **Long-context conversation history** — In-memory chronological list of OpenAI-compatible messages (`system`, `user`, `assistant`), capped at 100 exchanges / 55,000 characters to stay safely under `gemini.py`'s 60,000-character sliding-window safeguard. Eviction preserves the system prompt and the opening exchange while dropping the oldest subsequent complete user+assistant pair.
* **Automatic failure rollback & retry** — On connection timeout, network refusal, empty response, malformed JSON, or HTTP error, the failed user turn is rolled back from history (so retries don't double-append) and the prompt is cached as `lastFailedPrompt`. The GUI surfaces a Retry button above the composer; the legacy chat path surfaces errors as `[Sayu] <error message>` lines.
* **Gateway feature alignment (`gemini.py`)**:
    * **Entity & persona binding** — System prompt is wrapped in `<Sayu's Persona>...</Sayu's Persona>` so `gemini.py`'s `extract_rp_entities()` binds `char_name = "Sayu"` and `clean_gemini_rp_text()` strips any echoed `Sayu:` prefix from replies. The user's name is wrapped in `<{PlayerName}'s Persona>` so anti-godmoding applies to the right name.
    * **Memory isolation** — `@sayu memory <note>` is injected as `[Chat Memory: ...]` in the system prompt — `gemini.py` extracts it into a dedicated `<story_memory_and_plot_state>` block.
    * **Thinking mode control** — Toggles between standard and `-thinking` model variants and strips `...` Chain-of-Thought blocks before saving the assistant turn to history (so reasoning doesn't pollute subsequent turns). Optionally renders the thinking block in dark gray via `@sayu think show`.
    * **Web search grounding** — Sets the `web_search` JSON flag explicitly to `true`/`false` when search mode is `ON`/`OFF`, or omits it for `AUTO` (gateway intent detection).
* **Non-roleplay behavior by default** — Sayu is configured as a direct, factual, non-roleplaying AI chat assistant. The system prompt explicitly forbids third-person narration, asterisks-for-actions, physical gestures, scene setting, and "fictional character" framing. This redefines Sayu's character so `gemini.py`'s "stay in character" directive resolves to "be a non-RP AI assistant" rather than pushing dramatic/theatrical roleplay. If you ever want RP back for a session, use `@sayu memory Roleplay freely with asterisks and narration.` to flip the behavior — no code change needed.

---

## 2. Available Commands

There are two control surfaces: the **GUI** (opened with **K**) and the **legacy `@sayu` chat commands** (typed in vanilla Minecraft chat). Both share the same conversation state.

### GUI Controls (Sayu chat screen)

| Control | Behavior |
| --- | --- |
| **K** (default, rebindable in `Options → Controls → Sayu`) | Opens the Sayu chat screen while in-game with no other screen open. |
| **Enter** (while composer is focused) | Sends the message. No `@sayu` prefix needed. |
| **Shift + Enter** | Inserts a newline into the composer (best-effort; the prompt becomes multi-line). |
| **Esc** | Closes the Sayu screen. |
| **Mouse wheel** over the transcript | Scrolls up/down through long conversations. |
| **Send** button | Same as Enter — sends the composer text. Disabled while a request is in-flight or the composer is empty. |
| **Clear** button | First click renames it to "Confirm Clear?" (gold, ~3s window). Second click actually clears history. |
| **M:** button (top-right) | Cycles through supported models (3.8 → 3.8T → 3.7 → 3.7T → ... → 3.1P → 3.1PT → NB2 → NBP → ... → back to 3.8). |
| **S:** button (top-right) | Cycles `AUTO → ON → OFF → AUTO` for web search mode. |
| **Retry** button (above composer) | Appears only when the last request errored. Click to re-send the failed prompt. |
| "Sayu is thinking..." indicator (above composer) | Visible while a request is in-flight. |
| Header indicators | Show current model, search mode, and turn count. |

### Legacy Chat Commands

All commands are typed directly into normal Minecraft chat prefixed with `@sayu`. They are handled entirely locally and are **never** broadcasted to the multiplayer server.

| Command | Description |
| --- | --- |
| `@sayu <message>` | Sends your prompt along with full multi-turn conversation history to the local LLM. |
| `@sayu clear` (or `@sayu reset`) | Wipes the current dialogue history, re-initializes the system prompt, and confirms locally. |
| `@sayu status` | Displays active configurations (current model, search mode, thinking visibility, turn count, and character length). |
| `@sayu help` | Prints a quick reference of all available Sayu chat commands. |
| `@sayu model [id\|list]` | Switches the active model or lists all supported gateway presets (e.g., `gemini-3.8-flash`, `gemini-3.8-flash-thinking`, `gemini-3.1-pro`). |
| `@sayu think <on\|off\|show\|hide>` | Toggles thinking model variants or controls whether internal `...` reasoning blocks are displayed in chat. |
| `@sayu search <auto\|on\|off>` | Sets web search grounding mode (uses automatic gateway intent detection by default, or forces `web_search=true/false`). |
| `@sayu memory <note\|clear>` | Pins a persistent session context note (or use `clear` to remove it). |

---

## 3. Project File Structure

| File | Role |
| --- | --- |
| `src/main/resources/fabric.mod.json` | Mod metadata, dependencies, entrypoints (`in.sayu.Sayu` main, `in.sayu.client.SayuClient` client), mixin JSON references. |
| `src/client/java/in/sayu/client/SayuClient.java` | Client entrypoint. Wires up the legacy `@sayu` chat listener, registers the **K** keybinding via `KeyBindingHelper`, and installs a vanilla-chat listener on `SayuService` that displays output only when the GUI is closed. |
| `src/client/java/in/sayu/client/SayuService.java` | **NEW.** Shared singleton controller: conversation history, async HTTP pipeline, model/search/thinking/memory state, listener dispatch with client-thread marshalling. Both the GUI and the legacy `@sayu` path use this one service. |
| `src/client/java/in/sayu/client/gui/SayuChatScreen.java` | **NEW.** The dedicated `Screen`: dark modern theme, scrollable wrapped transcript, composer with Send/Clear, conditional Retry, M:/S: cycle buttons, lifecycle-safe async updates. |
| `src/client/java/in/sayu/client/mixin/MinecraftClientMixin.java` | **NEW.** HEAD injection on `Minecraft.tick()` that polls `SayuClient.OPEN_SAYU_SCREEN.consumeClick()` and opens the Sayu screen while no other screen is open. Replaces `ClientTickEvents` from `fabric-events-api-v1` (not on this project's compile classpath). |
| `src/client/resources/sayu.client.mixins.json` | Mixin config JSON. Must list `"MinecraftClientMixin"` in the `client` array. The `package` field should be `in.sayu.client.mixin`. |
| `src/main/resources/assets/sayu/lang/en_us.json` | **NEW.** Translation keys for the keybind (`key.sayu.open`, `key.categories.sayu`) and screen labels. |
| `gemini.py` | **(Read-Only Reference)** Local Sunless Gateway backend. Processes the OpenAI-compatible request, manages account sessions, handles search grounding, and interacts with Google's Batchexecute RPC backend. **Never modified by the mod client.** |
