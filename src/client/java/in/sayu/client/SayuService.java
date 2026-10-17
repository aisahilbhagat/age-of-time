package in.sayu.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared Sayu controller.
 *
 * <p>Owns conversation history, the asynchronous HTTP pipeline to the local
 * Sunless Gateway, and all model / search / thinking / memory settings. Used by
 * both the legacy {@code @sayu} chat command path (in {@link SayuClient}) and
 * the dedicated GUI screen ({@link in.sayu.client.gui.SayuChatScreen}).</p>
 *
 * <p>All network I/O is asynchronous; every {@link Listener} callback is
 * marshalled onto the Minecraft client thread via {@link Minecraft#execute},
 * so UI code can touch GUI state directly from callbacks without
 * synchronisation concerns.</p>
 *
 * <p>When the GUI is open ({@link #isGuiActive()} returns true) the legacy
 * vanilla-chat listener suppresses duplicate display; replies are surfaced
 * only through the GUI. If the GUI closes mid-request the request keeps running
 * to completion, the result is appended to history exactly once, and is
 * available when the GUI reopens.</p>
 */
public final class SayuService {

    public static final Logger LOGGER = LoggerFactory.getLogger("sayu");
    public static final SayuService INSTANCE = new SayuService();

    // --- Configurable LLM API settings (unchanged from original SayuClient) ---
    private static final String API_URL = "http://localhost:5000/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gemini-3.8-flash";
    private static final String API_KEY = "sunless";
    private static final String TRIGGER_PREFIX = "@sayu";

    // --- Recognised models (mirror of gemini.py's GEMINI_MODELS keys) ---
    public static final Set<String> SUPPORTED_MODELS = new LinkedHashSet<>(Arrays.asList(
            "gemini-3.8-flash",
            "gemini-3.8-flash-thinking",
            "gemini-3.7-flash",
            "gemini-3.7-flash-thinking",
            "gemini-3.6-flash",
            "gemini-3.6-flash-thinking",
            "gemini-3.5-flash",
            "gemini-3.5-flash-thinking",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash-lite-thinking",
            "gemini-3.1-pro",
            "gemini-3.1-pro-thinking",
            "nano-banana-2",
            "nano-banana-pro",
            "nano-banana-2-lite",
            "nano-banana"
    ));

    // --- Long-context history settings (aligned with gemini.py's 60k char window) ---
    private static final int MAX_HISTORY_EXCHANGES = 100;
    private static final int MAX_HISTORY_CHARS = 55000;

    // System prompt is wrapped in <Sayu's Persona>...</Sayu's Persona> so that
    // gemini.py's extract_rp_entities() binds char_name = "Sayu" and
    // clean_gemini_rp_text() strips any echoed "Sayu:" prefix from replies.
    //
    // We deliberately REDEFINE what "Sayu" is here: a non-roleplaying AI chat
    // assistant. This sidesteps gemini.py's hardcoded narrative directives
    // ("Speak EXCLUSIVELY as Sayu", "stay in character", "express authentic
    // mannerisms") by making those directives resolve to "be a direct,
    // non-theatrical AI assistant" rather than pushing asterisk actions,
    // third-person narration, or scene-setting roleplay. The model is told to
    // stay in character — and the character is "an AI assistant who never
    // roleplays" — so the two sets of instructions no longer conflict.
    private static final String BASE_SYSTEM_PROMPT =
            "<Sayu's Persona>\n" +
                    "You are Sayu — a direct, helpful, factual AI chat assistant communicating with the user through the chat interface inside Minecraft Java Edition. " +
                    "Minecraft is only your communication environment — not a limitation on your knowledge, topics, or personality. " +
                    "Engage naturally with whatever the user wants to discuss: everyday conversation, science, coding, philosophy, " +
                    "creative writing, Minecraft gameplay questions, or anything else.\n\n" +
                    "Your character is an AI chat assistant. You are NOT a fictional character in a story. " +
                    "You do not have a body, physical presence, hands, face, eyes, posture, or surroundings. " +
                    "You are an AI service that answers the user's questions through Minecraft chat.\n\n" +
                    "CRITICAL CHARACTER MANNERISM — your authentic way of speaking is to NEVER roleplay. " +
                    "The following are FORBIDDEN because they contradict your character:\n" +
                    "- Third-person narration (e.g., \"She tilts her head\", \"He nods slowly\", \"Sayu smiles\", \"A pause lingers\").\n" +
                    "- Asterisks or markdown for actions, gestures, or emotions (e.g., *smiles*, *pauses*, *looks at you*, _grins_).\n" +
                    "- Descriptions of physical gestures, facial expressions, posture, eye contact, breathing, or imagined body language — you have none of these.\n" +
                    "- Scene setting, environmental narration, atmosphere description, or sensory details about a physical space.\n" +
                    "- Speaking as if you are a fictional character in a narrative, a story, or a scene.\n\n" +
                    "INSTEAD, speak as a normal AI chat assistant would: answer the user's questions directly, informatively, and conversationally. " +
                    "Use plain prose. When the user asks about Minecraft, give concrete, actionable, factual answers (specific crafting recipes, mechanics, block behaviors). " +
                    "When the user asks about other topics, respond as a knowledgeable assistant would.\n\n" +
                    "You cannot see the user's live Minecraft world, inventory, coordinates, or screen unless the user explicitly tells you. Do not pretend otherwise.\n" +
                    "</Sayu's Persona>";

    // Matches the  thinking block emitted by -thinking model variants.
    private static final Pattern THINK_BLOCK_PATTERN =
            Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    // ---------- Public types ----------

    public enum SearchMode { AUTO, ON, OFF }

    public record ChatMessage(String role, String content) {}

    /**
     * UI callback interface. Every method is invoked on the Minecraft client
     * thread; implementations may safely touch GUI state directly.
     */
    public interface Listener {
        /** A new request has just started; show a "thinking" indicator. */
        default void onThinkingStart() {}
        /** A reply was successfully received and appended to history. */
        default void onReplyComplete(String visibleReply, String thinkingText) {}
        /** An error occurred; {@code failedPrompt} is the user text that failed (may be empty). */
        default void onError(String userMessage, String failedPrompt) {}
        /** History was cleared via {@link #resetConversationHistory()}. */
        default void onHistoryCleared() {}
        /** Model / search / think / memory state changed. */
        default void onStateChanged() {}
    }

    /** Snapshot row used by the GUI to render the transcript. */
    public record DisplayMessage(String role, String content, boolean isError, boolean isPending) {}

    // ---------- State ----------

    private final Object stateLock = new Object();
    private final List<ChatMessage> conversationHistory = new ArrayList<>();
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private String currentModel = DEFAULT_MODEL;
    private SearchMode searchMode = SearchMode.AUTO;
    private boolean showThinkingProcess = false;
    private String customMemoryNote = "";

    /** True while the dedicated GUI is open. Read from any thread. */
    private volatile boolean guiActive = false;

    /** Last user-facing error string; cleared on next successful send. Read under stateLock. */
    private String lastError = "";
    /** Last failed user prompt; used by retry(). Read under stateLock. */
    private String lastFailedPrompt = "";

    private SayuService() {
        resetConversationHistory();
    }

    // ---------- Listener registration ----------

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public void setGuiActive(boolean v) { guiActive = v; }
    public boolean isGuiActive() { return guiActive; }

    // ---------- Read accessors ----------

    public String getCurrentModel() {
        synchronized (stateLock) { return currentModel; }
    }

    public SearchMode getSearchMode() {
        synchronized (stateLock) { return searchMode; }
    }

    public boolean isShowThinkingProcess() {
        synchronized (stateLock) { return showThinkingProcess; }
    }

    public String getCustomMemoryNote() {
        synchronized (stateLock) { return customMemoryNote; }
    }

    public boolean isRequestInFlight() { return requestInFlight.get(); }

    public String getTriggerPrefix() { return TRIGGER_PREFIX; }
    public String getApiUrl() { return API_URL; }
    public int getMaxHistoryChars() { return MAX_HISTORY_CHARS; }

    /** Snapshot of conversation history excluding the system prompt. */
    public List<ChatMessage> getConversationSnapshot() {
        synchronized (stateLock) {
            List<ChatMessage> out = new ArrayList<>();
            for (int i = 1; i < conversationHistory.size(); i++) {
                out.add(conversationHistory.get(i));
            }
            return out;
        }
    }

    /**
     * Snapshot of the current display state: full conversation plus any
     * in-flight pending indicator and any unresolved error row.
     */
    public List<DisplayMessage> getDisplayMessages() {
        synchronized (stateLock) {
            List<DisplayMessage> out = new ArrayList<>();
            for (int i = 1; i < conversationHistory.size(); i++) {
                ChatMessage m = conversationHistory.get(i);
                out.add(new DisplayMessage(m.role(), m.content(), false, false));
            }
            if (lastError != null && !lastError.isBlank()) {
                String combined = (lastFailedPrompt != null && !lastFailedPrompt.isBlank())
                        ? "Failed to send: \"" + lastFailedPrompt + "\"\n" + lastError
                        : lastError;
                out.add(new DisplayMessage("assistant", combined, true, false));
            }
            if (requestInFlight.get()) {
                out.add(new DisplayMessage("assistant", "", false, true));
            }
            return out;
        }
    }

    public int getTurnCount() {
        synchronized (stateLock) {
            return Math.max(0, (conversationHistory.size() - 1) / 2);
        }
    }

    public int getDialogueCharCount() {
        synchronized (stateLock) {
            int total = 0;
            for (int i = 1; i < conversationHistory.size(); i++) {
                total += conversationHistory.get(i).content().length();
            }
            return total;
        }
    }

    public String getLastError() {
        synchronized (stateLock) { return lastError; }
    }

    public String getLastFailedPrompt() {
        synchronized (stateLock) { return lastFailedPrompt; }
    }

    // ---------- State mutators ----------

    public void resetConversationHistory() {
        synchronized (stateLock) {
            conversationHistory.clear();
            conversationHistory.add(new ChatMessage("system", buildSystemPromptLocked()));
            lastError = "";
            lastFailedPrompt = "";
        }
        fireOnClientThread(() -> {
            for (Listener l : listeners) l.onHistoryCleared();
        });
    }

    public boolean setCurrentModel(String modelId) {
        if (modelId == null || !SUPPORTED_MODELS.contains(modelId)) return false;
        synchronized (stateLock) { currentModel = modelId; }
        fireOnClientThread(() -> {
            for (Listener l : listeners) l.onStateChanged();
        });
        return true;
    }

    public void setSearchMode(SearchMode mode) {
        if (mode == null) return;
        synchronized (stateLock) { searchMode = mode; }
        fireOnClientThread(() -> {
            for (Listener l : listeners) l.onStateChanged();
        });
    }

    public void setShowThinkingProcess(boolean v) {
        synchronized (stateLock) { showThinkingProcess = v; }
        fireOnClientThread(() -> {
            for (Listener l : listeners) l.onStateChanged();
        });
    }

    public void setCustomMemoryNote(String note) {
        synchronized (stateLock) {
            customMemoryNote = note == null ? "" : note;
            updateSystemPromptLocked();
        }
        fireOnClientThread(() -> {
            for (Listener l : listeners) l.onStateChanged();
        });
    }

    /** Toggle between standard and -thinking model variant. Returns false if already in that state. */
    public boolean setThinkingEnabled(boolean enabled) {
        synchronized (stateLock) {
            String m = currentModel;
            if (enabled) {
                if (!m.endsWith("-thinking") && SUPPORTED_MODELS.contains(m + "-thinking")) {
                    currentModel = m + "-thinking";
                } else if (!m.contains("think")) {
                    currentModel = "gemini-3.8-flash-thinking";
                } else {
                    return false;
                }
            } else {
                if (m.endsWith("-thinking")) {
                    String base = m.substring(0, m.length() - "-thinking".length());
                    currentModel = SUPPORTED_MODELS.contains(base) ? base : DEFAULT_MODEL;
                } else {
                    return false;
                }
            }
        }
        fireOnClientThread(() -> {
            for (Listener l : listeners) l.onStateChanged();
        });
        return true;
    }

    // ---------- Public send entrypoint ----------

    /**
     * Submit a user prompt to the gateway. Returns {@code false} if a request
     * is already in flight or the prompt is empty; the caller should surface
     * the existing "still thinking" UI state in that case rather than another
     * error.
     */
    public boolean sendPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        if (!requestInFlight.compareAndSet(false, true)) return false;

        synchronized (stateLock) {
            lastError = "";
            lastFailedPrompt = "";
        }

        final HttpRequest request;
        try {
            String body = appendUserTurnAndBuildRequestBody(prompt);
            request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            rollbackFailedUserTurn();
            requestInFlight.set(false);
            LOGGER.error("Failed to build Sayu HTTP request for URL: {}", API_URL, e);
            reportError("Configuration error: failed to build API request.", prompt);
            return false;
        }

        fireOnClientThread(() -> {
            for (Listener l : listeners) l.onThinkingStart();
        });

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(this::handleHttpResponse)
                .exceptionally(throwable -> {
                    handleHttpException(throwable, prompt);
                    return null;
                });
        return true;
    }

    /** Retry the last failed prompt, if any. */
    public boolean retryLastFailed() {
        String p;
        synchronized (stateLock) { p = lastFailedPrompt; }
        if (p == null || p.isBlank()) return false;
        return sendPrompt(p);
    }

    // ---------- HTTP plumbing (refactored from old SayuClient, semantics preserved) ----------

    private String buildSystemPromptLocked() {
        String playerName = "Player";
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null && client.player.getName() != null) {
            String candidate = client.player.getName().getString();
            if (candidate != null && !candidate.isBlank()) {
                playerName = candidate.trim();
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(BASE_SYSTEM_PROMPT).append("\n\n");
        sb.append("<").append(playerName).append("'s Persona>\n");
        sb.append("The human participant chatting with Sayu from inside Minecraft Java Edition is named ")
                .append(playerName).append(".\n");
        sb.append("</").append(playerName).append("'s Persona>");

        if (!customMemoryNote.isBlank()) {
            // gemini.py's format_janitor_messages_to_prompt isolates this into <story_memory_and_plot_state>
            sb.append("\n\n[Chat Memory: ").append(customMemoryNote.trim()).append("]");
        }
        return sb.toString();
    }

    private void updateSystemPromptLocked() {
        String sys = buildSystemPromptLocked();
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(new ChatMessage("system", sys));
        } else {
            conversationHistory.set(0, new ChatMessage("system", sys));
        }
    }

    private int getDialogueCharCountLocked() {
        int total = 0;
        for (int i = 1; i < conversationHistory.size(); i++) {
            total += conversationHistory.get(i).content().length();
        }
        return total;
    }

    private void trimHistoryLocked(int incomingPromptLength) {
        // Keep index 0 (system) and indices 1-2 (opening exchange) intact; evict
        // the second-oldest complete user+assistant pair (indices 3 and 4) —
        // matching gemini.py's sliding-window strategy.
        int maxAllowed = 1 + (MAX_HISTORY_EXCHANGES * 2);
        while ((conversationHistory.size() >= maxAllowed
                || (getDialogueCharCountLocked() + incomingPromptLength > MAX_HISTORY_CHARS))
                && conversationHistory.size() >= 5) {
            ChatMessage first = conversationHistory.get(3);
            ChatMessage second = conversationHistory.get(4);
            if ("user".equals(first.role()) && "assistant".equals(second.role())) {
                conversationHistory.remove(4);
                conversationHistory.remove(3);
            } else {
                conversationHistory.remove(3);
            }
        }
    }

    private String appendUserTurnAndBuildRequestBody(String userPrompt) {
        synchronized (stateLock) {
            updateSystemPromptLocked();
            trimHistoryLocked(userPrompt.length());
            conversationHistory.add(new ChatMessage("user", userPrompt));

            JsonObject root = new JsonObject();
            root.addProperty("model", currentModel);
            root.addProperty("stream", false);

            if (searchMode == SearchMode.ON) root.addProperty("web_search", true);
            else if (searchMode == SearchMode.OFF) root.addProperty("web_search", false);

            if (currentModel.contains("think")) root.addProperty("thinking_budget", 1024);
            else root.addProperty("thinking_budget", 0);

            JsonArray messages = new JsonArray();
            for (ChatMessage m : conversationHistory) {
                JsonObject o = new JsonObject();
                o.addProperty("role", m.role());
                o.addProperty("content", m.content());
                messages.add(o);
            }
            root.add("messages", messages);
            return GSON.toJson(root);
        }
    }

    private void appendAssistantTurn(String reply) {
        synchronized (stateLock) {
            conversationHistory.add(new ChatMessage("assistant", reply));
        }
    }

    private void rollbackFailedUserTurn() {
        synchronized (stateLock) {
            if (conversationHistory.size() > 1) {
                int i = conversationHistory.size() - 1;
                if ("user".equals(conversationHistory.get(i).role())) {
                    String failed = conversationHistory.get(i).content();
                    conversationHistory.remove(i);
                    lastFailedPrompt = failed;
                }
            }
        }
    }

    private String rollbackAndGetFailed() {
        rollbackFailedUserTurn();
        synchronized (stateLock) { return lastFailedPrompt; }
    }

    private void handleHttpResponse(HttpResponse<String> response) {
        try {
            int code = response.statusCode();
            String body = response.body();

            if (code < 200 || code >= 300) {
                String fp = rollbackAndGetFailed();
                LOGGER.warn("Sayu LLM endpoint returned HTTP {}: {}", code, body);
                reportError("LLM returned HTTP error " + code + ".", fp);
                return;
            }
            if (body == null || body.isBlank()) {
                String fp = rollbackAndGetFailed();
                reportError("Received an empty response from the local LLM.", fp);
                return;
            }
            String rawReply = extractReplyContent(body);
            if (rawReply == null || rawReply.isBlank()) {
                String fp = rollbackAndGetFailed();
                reportError("LLM response did not contain a valid message.", fp);
                return;
            }

            // Compute thinking + visible reply on the async thread (pure CPU work).
            final String trimmed = rawReply.trim();
            Matcher m = THINK_BLOCK_PATTERN.matcher(trimmed);
            final String thinkingText = m.find() ? m.group(1).trim() : null;
            String visible = m.replaceAll("").trim();
            if (visible.isEmpty()) visible = trimmed;
            final String vis = visible;

            // Final state mutation happens on the client thread so the GUI
            // never observes a half-applied state (history updated while
            // requestInFlight is still true, or vice versa).
            fireOnClientThread(() -> {
                appendAssistantTurn(vis);
                requestInFlight.set(false);
                for (Listener l : listeners) l.onReplyComplete(vis, thinkingText);
            });
        } catch (JsonSyntaxException e) {
            String fp = rollbackAndGetFailed();
            LOGGER.warn("Failed to parse JSON response from Sayu LLM endpoint", e);
            reportError("Received malformed JSON from the local LLM.", fp);
        } catch (Exception e) {
            String fp = rollbackAndGetFailed();
            LOGGER.error("Unexpected error while handling Sayu LLM response", e);
            reportError("An unexpected error occurred while reading the reply.", fp);
        }
    }

    private void handleHttpException(Throwable throwable, String originalPrompt) {
        rollbackFailedUserTurn();
        Throwable cause = (throwable instanceof CompletionException && throwable.getCause() != null)
                ? throwable.getCause() : throwable;
        String msg;
        if (cause instanceof HttpTimeoutException) {
            msg = "Request to local LLM timed out.";
        } else if (cause instanceof ConnectException) {
            msg = "Could not connect to local LLM at " + API_URL + ".";
        } else {
            String d = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            msg = "Error communicating with local LLM: " + d;
        }
        LOGGER.warn("Sayu LLM request failed", cause);
        reportError(msg, originalPrompt);
    }

    private void reportError(String userMessage, String failedPrompt) {
        synchronized (stateLock) {
            lastError = userMessage;
            lastFailedPrompt = failedPrompt == null ? "" : failedPrompt;
        }
        fireOnClientThread(() -> {
            requestInFlight.set(false);
            for (Listener l : listeners) l.onError(userMessage, failedPrompt);
        });
    }

    private String extractReplyContent(String jsonBody) {
        JsonElement parsed = JsonParser.parseString(jsonBody);
        if (parsed == null || !parsed.isJsonObject()) return null;
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("choices") || !root.get("choices").isJsonArray()) return null;
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices.isEmpty()) return null;
        JsonElement ch0e = choices.get(0);
        if (ch0e == null || !ch0e.isJsonObject()) return null;
        JsonObject ch0 = ch0e.getAsJsonObject();
        if (!ch0.has("message") || !ch0.get("message").isJsonObject()) return null;
        JsonObject message = ch0.getAsJsonObject("message");
        if (!message.has("content") || message.get("content").isJsonNull()) return null;
        JsonElement c = message.get("content");
        if (!c.isJsonPrimitive()) return null;
        return c.getAsString();
    }

    // ---------- UI-thread marshalling ----------

    private void fireOnClientThread(Runnable r) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(r);
        } else {
            r.run();
        }
    }
}
