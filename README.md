---

# Sayu Minecraft Mod — Technical & User Guide

Sayu is a client-side AI integration mod for **Minecraft Java Edition 1.21.1** (Fabric Loader, Mojang mappings) that connects seamlessly to your local OpenAI-compatible Sunless Gateway API (`http://localhost:5000/v1/chat/completions`).

---

## 1. Core Implementations & Architecture

* **Chat Interception (`ClientSendMessageEvents.ALLOW_CHAT`):** Intercepts player chat messages starting with `@sayu` (case-insensitively). It prevents the trigger message from being sent to the Minecraft server so it stays entirely local.
* **Asynchronous Networking (`HttpClient`):** Executes HTTP POST requests asynchronously via Java's built-in `HttpClient#sendAsync` to guarantee that Minecraft's main client/render thread never blocks or lags.
* **Main-Thread UI Marshaling:** All local chat feedback (`[Sayu] Sayu is thinking...`, errors, and model replies) is safely pushed back onto the client thread via `Minecraft.getInstance().execute(...)`.
* **Robust Multi-Turn Conversation History:** Maintains an in-memory chronological list of OpenAI-compatible message objects (`system`, `user`, `assistant`), capped safely under a 55,000-character window to align with the gateway's sliding-window safeguard.


* **Automatic Failure Rollback:** If a connection timeout, network refusal, empty response, or malformed JSON occurs, the unsent/failed user turn is automatically removed from history so turns never become orphaned or desynchronized.
* **Gateway Feature Integration (`gemini.py` alignment):**
* **Entity & Persona Binding:** Automatically binds `char_name = "Sayu"` using `<Sayu's Persona>` in the system instructions.


* **Thinking Mode & Reasoning Control:** Supports toggling and stripping internal `<think>...</think>` Chain-of-Thought reasoning blocks.


* **Web Search Grounding Controls:** Manages automatic or explicit web search flags.


* **Persistent Chat Memory Pinning:** Allows pinning custom context notes using gateway-supported `[Chat Memory: ...]` tags.





---

## 2. Available Commands

All commands are typed directly into normal Minecraft chat prefixed with `@sayu`. They are handled entirely locally and are **never** broadcasted to the multiplayer server.

| Command | Description |
| --- | --- |
| `@sayu <message>` | Sends your prompt along with full multi-turn conversation history to the local LLM. |
| `@sayu clear` (or `@sayu reset`) | Wipes the current dialogue history, re-initializes the system prompt, and confirms locally. |
| `@sayu status` | Displays active configurations (current model, search mode, thinking visibility, turn count, and character length). |
| `@sayu help` | Prints a quick reference of all available Sayu chat commands. |
| `@sayu model [id]` | Switches the active model or lists all supported gateway presets (e.g., `gemini-3.8-flash`, `gemini-3.8-flash-thinking`, `gemini-3.1-pro`).

|
| `@sayu think <on|off|show|hide>` | Toggles thinking mode variants or controls whether internal `<think>` reasoning blocks are displayed in chat. |
| `@sayu search <auto|on|off>` | Sets web search grounding mode (uses automatic gateway intent detection by default, or forces `web_search=true/false`).

|
| `@sayu memory <note>` | Pins a persistent session context note (or use `@sayu memory clear` to remove it). |

---

## 3. Project File Structure & Modified Files

* **`fabric.mod.json`** (`src/main/resources/fabric.mod.json`): Configures the mod metadata, dependencies, and client entrypoint pointing to `in.sayu.client.SayuClient`.


* **`SayuClient.java`** (`src/client/java/in/sayu/client/SayuClient.java`): The core client-side mod implementation handling chat events, JSON serialization, asynchronous HTTP calls, thread safety, and all local subcommands.


* **`gemini.py`**: **(Read-Only Reference)** Located in your local Sunless Gateway backend. It processes the OpenAI-compatible request, manages account sessions, handles search grounding, and interacts with Google's Batchexecute RPC backend. *(Never modified by the mod client).*



---

## 4. How to Build and Test

### Step 1: Compile the Mod

Open your project terminal in IntelliJ (or PowerShell at your project root) and run:

```powershell
.\gradlew.bat build

```

Verify that the build outputs **`BUILD SUCCESSFUL`**.

### Step 2: Locate and Install the JAR

* **JAR Location:** `build/libs/sayu-1.0.0.jar`
* Copy this `.jar` file into your Minecraft instance's `.minecraft/mods` folder (alongside Fabric API), or launch directly using the **Minecraft Client** run configuration in IntelliJ.



### Step 3: In-Game Verification Checklist

1. **Start Gateway:** Ensure your local Sunless Gateway is running at `http://localhost:5000/v1/chat/completions`.
2. **Normal Chat Test:** Type `Hello everyone` in chat — verify it sends to the server normally.
3. **General Conversation Test:** Type `@sayu Let's brainstorm ideas for a sci-fi novel.` — verify Sayu replies in-character without forcing Minecraft-only restrictions.
4. **Memory Continuity Test:** Type `@sayu What topic did we just start discussing?` — verify Sayu recalls the novel brainstorming context from the prior turn.
5. **Thinking Mode Test:** Type `@sayu think on` followed by `@sayu solve x + 5 = 12` — verify internal reasoning and output behavior.
6. **Command Check:** Type `@sayu status` and `@sayu clear` to verify local state management.