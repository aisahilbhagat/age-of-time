package in.sayu.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SayuClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("sayu");

	// --- Configurable LLM API Settings ---
	private static final String API_URL = "http://localhost:5000/v1/chat/completions";
	private static final String DEFAULT_MODEL = "gemini-3.8-flash";
	private static final String API_KEY = "sunless";
	private static final String TRIGGER_PREFIX = "@sayu";

	// --- Recognized Models from gemini.py ---
	private static final Set<String> SUPPORTED_MODELS = new LinkedHashSet<>(Arrays.asList(
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

	// --- Long-Context History Settings (Aligned with gemini.py's 60,000 char window) ---
	private static final int MAX_HISTORY_EXCHANGES = 100; // Up to 100 user/assistant pairs (200 messages)
	private static final int MAX_HISTORY_CHARS = 55000;   // Stays cleanly below gemini.py's 60,000 char cutoff

	// Uses <Sayu's Persona> so gemini.py's extract_rp_entities() binds char_name = "Sayu"
	private static final String BASE_SYSTEM_PROMPT =
			"<Sayu's Persona>\n" +
					"You are Sayu, a versatile, intelligent, and expressive general-purpose conversational AI and creative companion. " +
					"You are currently communicating with the user through the chat interface inside Minecraft Java Edition, " +
					"but Minecraft is only your communication environment—not a limitation on your personality, knowledge, or topics. " +
					"Engage naturally with whatever the user wants to discuss: everyday conversation, science, coding, philosophy, " +
					"creative writing, immersive roleplay, mature or dramatic storytelling, or Minecraft gameplay if asked. " +
					"Maintain the tone, characters, and continuity established in the conversation without unnecessary disclaimers. " +
					"Never pretend you can directly see the live Minecraft world, inventory, coordinates, or screen unless the user explicitly tells you.\n" +
					"</Sayu's Persona>";

	private static final Pattern THINK_BLOCK_PATTERN =
			Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.build();

	private enum SearchMode {
		AUTO, ON, OFF
	}

	private record ChatMessage(String role, String content) {}

	private final Object stateLock = new Object();
	private final List<ChatMessage> conversationHistory = new ArrayList<>();
	private final AtomicBoolean requestInFlight = new AtomicBoolean(false);

	private String currentModel = DEFAULT_MODEL;
	private SearchMode searchMode = SearchMode.AUTO;
	private boolean showThinkingProcess = false;
	private String customMemoryNote = "";

	public SayuClient() {
		resetConversationHistory();
	}

	@Override
	public void onInitializeClient() {
		ClientSendMessageEvents.ALLOW_CHAT.register(this::onAllowChatMessage);
		LOGGER.info("Sayu initialized (model={}, search={}). Listening for {} triggers.",
				currentModel, searchMode, TRIGGER_PREFIX);
	}

	private boolean onAllowChatMessage(String message) {
		if (message == null || !startsWithTrigger(message)) {
			return true; // Leave normal Minecraft chat messages untouched
		}

		String rawInput = message.substring(TRIGGER_PREFIX.length()).trim();
		if (rawInput.isEmpty()) {
			displayLocalMessage("Enter a message after @sayu, or type '@sayu help' for commands.", ChatFormatting.RED);
			return false;
		}

		if (handleLocalSubcommand(rawInput)) {
			return false; // Handled locally; do not send to server or LLM API
		}

		if (!requestInFlight.compareAndSet(false, true)) {
			displayLocalMessage("Sayu is still replying to your previous message. Please wait...", ChatFormatting.YELLOW);
			return false;
		}

		displayLocalMessage("Sayu is thinking...", ChatFormatting.GRAY);
		sendPromptAsync(rawInput);

		return false; // Cancel sending the @sayu message to the Minecraft server
	}

	private boolean startsWithTrigger(String message) {
		if (message.length() < TRIGGER_PREFIX.length()) {
			return false;
		}
		if (!message.regionMatches(true, 0, TRIGGER_PREFIX, 0, TRIGGER_PREFIX.length())) {
			return false;
		}
		return message.length() == TRIGGER_PREFIX.length()
				|| Character.isWhitespace(message.charAt(TRIGGER_PREFIX.length()));
	}

	private boolean handleLocalSubcommand(String input) {
		String lower = input.toLowerCase(Locale.ROOT);

		// 1. Clear / Reset conversation history
		if (lower.equals("clear") || lower.equals("reset")) {
			if (requestInFlight.get()) {
				displayLocalMessage("Please wait for the active reply to finish before clearing history.", ChatFormatting.YELLOW);
				return true;
			}
			resetConversationHistory();
			displayLocalMessage("Conversation history cleared.", ChatFormatting.GREEN);
			return true;
		}

		// 2. Help command
		if (lower.equals("help")) {
			displayLocalMessage("Commands: '@sayu <msg>', '@sayu clear', '@sayu status', " +
					"'@sayu model [id]', '@sayu think <on|off|show|hide>', '@sayu search <auto|on|off>', " +
					"'@sayu memory <note|clear>'.", ChatFormatting.GOLD);
			return true;
		}

		// 3. Status command
		if (lower.equals("status")) {
			synchronized (stateLock) {
				int turnCount = Math.max(0, (conversationHistory.size() - 1) / 2);
				int charCount = getDialogueCharCountLocked();
				displayLocalMessage(String.format(
						"Model: %s | Search: %s | Show <think>: %s | History: %d turns (%d/%d chars)",
						currentModel, searchMode, showThinkingProcess ? "ON" : "OFF",
						turnCount, charCount, MAX_HISTORY_CHARS), ChatFormatting.GREEN);
			}
			return true;
		}

		// 4. Model selection command: @sayu model [model_id]
		if (lower.equals("model") || lower.startsWith("model ")) {
			String arg = input.substring("model".length()).trim().toLowerCase(Locale.ROOT);
			synchronized (stateLock) {
				if (arg.isEmpty() || arg.equals("list")) {
					displayLocalMessage("Current model: " + currentModel + ". Available: " +
							String.join(", ", SUPPORTED_MODELS), ChatFormatting.AQUA);
					return true;
				}
				if (!SUPPORTED_MODELS.contains(arg)) {
					displayLocalMessage("Unknown model preset '" + arg + "'. Type '@sayu model list' to see supported models.", ChatFormatting.RED);
					return true;
				}
				currentModel = arg;
				displayLocalMessage("Switched model to: " + currentModel, ChatFormatting.GREEN);
			}
			return true;
		}

		// 5. Thinking mode command: @sayu think <on|off|show|hide>
		if (lower.equals("think") || lower.startsWith("think ")) {
			String arg = input.substring("think".length()).trim().toLowerCase(Locale.ROOT);
			synchronized (stateLock) {
				if (arg.equals("show")) {
					showThinkingProcess = true;
					displayLocalMessage("Internal <think> reasoning will now be shown in chat.", ChatFormatting.GREEN);
					return true;
				}
				if (arg.equals("hide")) {
					showThinkingProcess = false;
					displayLocalMessage("Internal <think> reasoning will now be hidden from chat.", ChatFormatting.GREEN);
					return true;
				}
				if (arg.equals("on")) {
					if (!currentModel.endsWith("-thinking") && SUPPORTED_MODELS.contains(currentModel + "-thinking")) {
						currentModel = currentModel + "-thinking";
					} else if (!currentModel.contains("think")) {
						currentModel = "gemini-3.8-flash-thinking";
					}
					displayLocalMessage("Thinking mode enabled (model: " + currentModel + ").", ChatFormatting.GREEN);
					return true;
				}
				if (arg.equals("off")) {
					if (currentModel.endsWith("-thinking")) {
						String base = currentModel.substring(0, currentModel.length() - "-thinking".length());
						currentModel = SUPPORTED_MODELS.contains(base) ? base : DEFAULT_MODEL;
					}
					displayLocalMessage("Thinking mode disabled (model: " + currentModel + ").", ChatFormatting.GREEN);
					return true;
				}
				displayLocalMessage("Usage: '@sayu think <on|off|show|hide>' (Current model: " + currentModel + ")", ChatFormatting.YELLOW);
			}
			return true;
		}

		// 6. Web search control: @sayu search <auto|on|off>
		if (lower.equals("search") || lower.startsWith("search ")) {
			String arg = input.substring("search".length()).trim().toLowerCase(Locale.ROOT);
			synchronized (stateLock) {
				switch (arg) {
					case "on" -> {
						searchMode = SearchMode.ON;
						displayLocalMessage("Web search mode set to ON (requests explicit web_search=true).", ChatFormatting.GREEN);
					}
					case "off" -> {
						searchMode = SearchMode.OFF;
						displayLocalMessage("Web search mode set to OFF (automatic gateway intent detection still applies if triggered by phrasing).", ChatFormatting.YELLOW);
					}
					case "auto" -> {
						searchMode = SearchMode.AUTO;
						displayLocalMessage("Web search mode set to AUTO (uses gateway search intent detection).", ChatFormatting.GREEN);
					}
					default -> displayLocalMessage("Current search mode: " + searchMode + ". Usage: '@sayu search <auto|on|off>'.", ChatFormatting.YELLOW);
				}
			}
			return true;
		}

		// 7. Persistent session memory note (uses gemini.py's [Chat Memory: ...] tag isolation)
		if (lower.equals("memory") || lower.startsWith("memory ")) {
			String arg = input.substring("memory".length()).trim();
			synchronized (stateLock) {
				if (arg.isEmpty()) {
					String current = customMemoryNote.isEmpty() ? "(none)" : customMemoryNote;
					displayLocalMessage("Active session memory note: " + current, ChatFormatting.AQUA);
					return true;
				}
				if (arg.equalsIgnoreCase("clear")) {
					customMemoryNote = "";
					updateSystemPromptLocked();
					displayLocalMessage("Session memory note cleared.", ChatFormatting.GREEN);
					return true;
				}
				customMemoryNote = arg;
				updateSystemPromptLocked();
				displayLocalMessage("Updated session memory note.", ChatFormatting.GREEN);
			}
			return true;
		}

		return false;
	}

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
		sb.append("The human participant chatting with Sayu from inside Minecraft Java Edition is named ").append(playerName).append(".\n");
		sb.append("</").append(playerName).append("'s Persona>");

		if (!customMemoryNote.isBlank()) {
			// Formatted so gemini.py's format_janitor_messages_to_prompt isolates it into <story_memory_and_plot_state>
			sb.append("\n\n[Chat Memory: ").append(customMemoryNote.trim()).append("]");
		}
		return sb.toString();
	}

	private void updateSystemPromptLocked() {
		String sysPrompt = buildSystemPromptLocked();
		if (conversationHistory.isEmpty()) {
			conversationHistory.add(new ChatMessage("system", sysPrompt));
		} else {
			conversationHistory.set(0, new ChatMessage("system", sysPrompt));
		}
	}

	private void resetConversationHistory() {
		synchronized (stateLock) {
			conversationHistory.clear();
			conversationHistory.add(new ChatMessage("system", buildSystemPromptLocked()));
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
		// Keep index 0 (system prompt) intact.
		// If we exceed MAX_HISTORY_EXCHANGES or MAX_HISTORY_CHARS, preserve the first user/assistant exchange
		// (indices 1 and 2) for initial scene continuity—matching gemini.py's sliding-window strategy—and
		// remove the oldest subsequent complete user/assistant pair (indices 3 and 4).
		int maxAllowedMessages = 1 + (MAX_HISTORY_EXCHANGES * 2);

		while ((conversationHistory.size() >= maxAllowedMessages
				|| (getDialogueCharCountLocked() + incomingPromptLength > MAX_HISTORY_CHARS))
				&& conversationHistory.size() >= 5) {
			// Remove indices 3 and 4 (second exchange), preserving index 0 (system) and indices 1-2 (opening exchange)
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

			// Include explicit web_search flag if set to ON or OFF
			if (searchMode == SearchMode.ON) {
				root.addProperty("web_search", true);
			} else if (searchMode == SearchMode.OFF) {
				root.addProperty("web_search", false);
			}

			// Include thinking_budget consistent with the selected model preset
			if (currentModel.contains("think")) {
				root.addProperty("thinking_budget", 1024);
			} else {
				root.addProperty("thinking_budget", 0);
			}

			JsonArray messagesArray = new JsonArray();
			for (ChatMessage msg : conversationHistory) {
				JsonObject messageObj = new JsonObject();
				messageObj.addProperty("role", msg.role());
				messageObj.addProperty("content", msg.content());
				messagesArray.add(messageObj);
			}

			root.add("messages", messagesArray);
			return GSON.toJson(root);
		}
	}

	private void appendAssistantTurn(String assistantReply) {
		synchronized (stateLock) {
			conversationHistory.add(new ChatMessage("assistant", assistantReply));
		}
	}

	private void rollbackFailedUserTurn() {
		synchronized (stateLock) {
			if (conversationHistory.size() > 1) {
				int lastIndex = conversationHistory.size() - 1;
				if ("user".equals(conversationHistory.get(lastIndex).role())) {
					conversationHistory.remove(lastIndex);
				}
			}
		}
	}

	private void sendPromptAsync(String prompt) {
		final HttpRequest request;
		try {
			String requestBodyJson = appendUserTurnAndBuildRequestBody(prompt);
			request = HttpRequest.newBuilder()
					.uri(URI.create(API_URL))
					.timeout(REQUEST_TIMEOUT)
					.header("Content-Type", "application/json")
					.header("Authorization", "Bearer " + API_KEY)
					.POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8))
					.build();
		} catch (Exception e) {
			rollbackFailedUserTurn();
			requestInFlight.set(false);
			LOGGER.error("Failed to build Sayu HTTP request for URL: {}", API_URL, e);
			displayLocalMessage("Configuration error: Failed to build API request.", ChatFormatting.RED);
			return;
		}

		HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenAccept(this::handleHttpResponse)
				.exceptionally(throwable -> {
					handleHttpException(throwable);
					return null;
				});
	}

	private void handleHttpResponse(HttpResponse<String> response) {
		try {
			int statusCode = response.statusCode();
			String body = response.body();

			if (statusCode < 200 || statusCode >= 300) {
				rollbackFailedUserTurn();
				LOGGER.warn("Sayu LLM endpoint returned HTTP {}: {}", statusCode, body);
				displayLocalMessage("LLM returned HTTP error " + statusCode + ".", ChatFormatting.RED);
				return;
			}

			if (body == null || body.isBlank()) {
				rollbackFailedUserTurn();
				displayLocalMessage("Received an empty response from the local LLM.", ChatFormatting.RED);
				return;
			}

			String rawReply = extractReplyContent(body);
			if (rawReply == null || rawReply.isBlank()) {
				rollbackFailedUserTurn();
				displayLocalMessage("LLM response did not contain a valid message.", ChatFormatting.RED);
				return;
			}

			processAndDisplayAssistantReply(rawReply.trim());
		} catch (JsonSyntaxException e) {
			rollbackFailedUserTurn();
			LOGGER.warn("Failed to parse JSON response from Sayu LLM endpoint", e);
			displayLocalMessage("Received malformed JSON from the local LLM.", ChatFormatting.RED);
		} catch (Exception e) {
			rollbackFailedUserTurn();
			LOGGER.error("Unexpected error while handling Sayu LLM response", e);
			displayLocalMessage("An unexpected error occurred while reading the reply.", ChatFormatting.RED);
		} finally {
			requestInFlight.set(false);
		}
	}

	private void processAndDisplayAssistantReply(String rawReply) {
		String thinkingText = null;
		Matcher matcher = THINK_BLOCK_PATTERN.matcher(rawReply);
		if (matcher.find()) {
			thinkingText = matcher.group(1).trim();
		}

		// Strip <think>...</think> blocks from the stored history and main reply so internal reasoning
		// does not pollute subsequent dialogue history turns in gemini.py
		String visibleReply = matcher.replaceAll("").trim();
		if (visibleReply.isEmpty()) {
			visibleReply = rawReply;
		}

		appendAssistantTurn(visibleReply);

		boolean shouldShowThinking;
		synchronized (stateLock) {
			shouldShowThinking = this.showThinkingProcess;
		}

		if (shouldShowThinking && thinkingText != null && !thinkingText.isEmpty()) {
			displayLocalMessage("[Thought] " + thinkingText, ChatFormatting.DARK_GRAY);
		}

		displayLocalMessage(visibleReply, ChatFormatting.AQUA);
	}

	private String extractReplyContent(String jsonBody) {
		JsonElement parsed = JsonParser.parseString(jsonBody);
		if (parsed == null || !parsed.isJsonObject()) {
			return null;
		}

		JsonObject root = parsed.getAsJsonObject();
		if (!root.has("choices") || !root.get("choices").isJsonArray()) {
			return null;
		}

		JsonArray choices = root.getAsJsonArray("choices");
		if (choices.isEmpty()) {
			return null;
		}

		JsonElement firstChoiceElement = choices.get(0);
		if (firstChoiceElement == null || !firstChoiceElement.isJsonObject()) {
			return null;
		}

		JsonObject firstChoice = firstChoiceElement.getAsJsonObject();
		if (!firstChoice.has("message") || !firstChoice.get("message").isJsonObject()) {
			return null;
		}

		JsonObject messageObj = firstChoice.getAsJsonObject("message");
		if (!messageObj.has("content") || messageObj.get("content").isJsonNull()) {
			return null;
		}

		JsonElement contentElement = messageObj.get("content");
		if (!contentElement.isJsonPrimitive()) {
			return null;
		}

		return contentElement.getAsString();
	}

	private void handleHttpException(Throwable throwable) {
		try {
			rollbackFailedUserTurn();

			Throwable cause = (throwable instanceof CompletionException && throwable.getCause() != null)
					? throwable.getCause()
					: throwable;

			if (cause instanceof HttpTimeoutException) {
				displayLocalMessage("Request to local LLM timed out.", ChatFormatting.RED);
			} else if (cause instanceof ConnectException) {
				displayLocalMessage("Could not connect to local LLM at " + API_URL + ".", ChatFormatting.RED);
			} else {
				String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
				displayLocalMessage("Error communicating with local LLM: " + detail, ChatFormatting.RED);
			}
			LOGGER.warn("Sayu LLM request failed", cause);
		} finally {
			requestInFlight.set(false);
		}
	}

	private void displayLocalMessage(String text, ChatFormatting color) {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return;
		}

		client.execute(() -> {
			MutableComponent formatted = Component.literal("[Sayu] ")
					.withStyle(ChatFormatting.LIGHT_PURPLE)
					.append(Component.literal(text).withStyle(color));

			if (client.player != null) {
				client.player.displayClientMessage(formatted, false);
			} else if (client.gui != null) {
				client.gui.getChat().addMessage(formatted);
			}
		});
	}
}