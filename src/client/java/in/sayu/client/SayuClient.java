package in.sayu.client;

import in.sayu.client.gui.SayuChatScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Client entrypoint for Sayu.
 *
 * <p>Wires up three things:</p>
 * <ol>
 *   <li>The legacy {@code @sayu} chat-command path (preserved as a fallback),
 *       now delegating all request and history work to {@link SayuService}.</li>
 *   <li>A configurable keybinding (default: K) that opens the dedicated
 *       {@link SayuChatScreen} GUI. The keybind itself is registered here via
 *       {@link KeyBindingHelper}; the actual tick polling that consumes clicks
 *       and opens the screen lives in the
 *       {@code in.sayu.client.mixin.MinecraftClientMixin} mixin (because this
 *       project does not include Fabric API's {@code fabric-events-api-v1}
 *       module, so {@code ClientTickEvents} is not on the compile classpath).</li>
 *   <li>A vanilla-chat listener on {@link SayuService} that surfaces
 *       "thinking" indicators, replies, and errors to Minecraft chat
 *       <em>only when the GUI is closed</em>. While the GUI is open it stays
 *       quiet so responses are not duplicated in vanilla chat.</li>
 * </ol>
 *
 * <p>The original {@code @sayu} subcommands ({@code clear}, {@code status},
 * {@code help}, {@code model}, {@code think}, {@code search}, {@code memory})
 * are unchanged in behaviour; they now simply forward to {@link SayuService}
 * mutators.</p>
 */
public class SayuClient implements ClientModInitializer {

    /**
     * Keybinding that opens the dedicated Sayu chat screen. Default key is K,
     * which does not conflict with any vanilla Minecraft 1.21.1 control. The
     * user can rebind it under Controls &rarr; Sayu.
     *
     * <p>This field is read by {@code in.sayu.client.mixin.MinecraftClientMixin}
     * to detect keypresses. Do not rename it without also updating the mixin.</p>
     */
    public static final KeyMapping OPEN_SAYU_SCREEN = new KeyMapping(
            "key.sayu.open",
            GLFW.GLFW_KEY_K,
            "key.categories.sayu"
    );

    @Override
    public void onInitializeClient() {
        // Force the shared service singleton to initialise early.
        SayuService service = SayuService.INSTANCE;

        // Default-display listener: routes Sayu output to vanilla Minecraft chat
        // ONLY when the dedicated GUI is not open. While the GUI is open it stays
        // quiet so the player does not see the same reply twice.
        SayuService.Listener vanillaListener = new SayuService.Listener() {
            @Override
            public void onThinkingStart() {
                if (service.isGuiActive()) return;
                displayLocalMessage("Sayu is thinking...", ChatFormatting.GRAY);
            }

            @Override
            public void onReplyComplete(String visibleReply, String thinkingText) {
                if (service.isGuiActive()) return;  // GUI handles display
                boolean showThinking = service.isShowThinkingProcess();
                if (showThinking && thinkingText != null && !thinkingText.isEmpty()) {
                    displayLocalMessage("[Thought] " + thinkingText, ChatFormatting.DARK_GRAY);
                }
                displayLocalMessage(visibleReply, ChatFormatting.AQUA);
            }

            @Override
            public void onError(String userMessage, String failedPrompt) {
                if (service.isGuiActive()) return;
                displayLocalMessage(userMessage, ChatFormatting.RED);
            }
        };
        service.addListener(vanillaListener);

        // 1. Legacy fallback: @sayu commands (unchanged UX, routed via SayuService)
        ClientSendMessageEvents.ALLOW_CHAT.register(this::onAllowChatMessage);

        // 2. Register the Sayu keybinding so it appears in Controls and gets
        //    polled by vanilla MC's KeyboardHandler. The actual consumeClick()
        //    polling that opens the screen happens in MinecraftClientMixin
        //    (HEAD injection on Minecraft.tick()), because this project does
        //    not include fabric-events-api-v1 on its compile classpath.
        KeyBindingHelper.registerKeyBinding(OPEN_SAYU_SCREEN);

        SayuService.LOGGER.info(
                "Sayu initialised (model={}, search={}). Press the Sayu keybind (default: K) " +
                        "to open the chat screen, or type @sayu in chat for the legacy command path.",
                service.getCurrentModel(), service.getSearchMode()
        );
    }

    // ---------------- Legacy @sayu chat command path ----------------

    private boolean onAllowChatMessage(String message) {
        if (message == null || !startsWithTrigger(message)) {
            return true;  // Leave normal Minecraft chat messages untouched
        }

        String rawInput = message.substring(SayuService.INSTANCE.getTriggerPrefix().length()).trim();
        if (rawInput.isEmpty()) {
            displayLocalMessage("Enter a message after @sayu, or type '@sayu help' for commands.", ChatFormatting.RED);
            return false;
        }
        if (handleLocalSubcommand(rawInput)) {
            return false;  // Handled locally; do not send to server or LLM API
        }

        // Route the prompt through the shared service — same history, HTTP pipeline,
        // model, search, and thinking settings as the GUI uses.
        if (!SayuService.INSTANCE.sendPrompt(rawInput)) {
            displayLocalMessage("Sayu is still replying to your previous message. Please wait...", ChatFormatting.YELLOW);
        }
        return false;  // Cancel sending the @sayu message to the Minecraft server
    }

    private boolean startsWithTrigger(String message) {
        String trigger = SayuService.INSTANCE.getTriggerPrefix();
        if (message.length() < trigger.length()) return false;
        if (!message.regionMatches(true, 0, trigger, 0, trigger.length())) return false;
        return message.length() == trigger.length()
                || Character.isWhitespace(message.charAt(trigger.length()));
    }

    private boolean handleLocalSubcommand(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        SayuService s = SayuService.INSTANCE;

        // 1. Clear / Reset conversation history
        if (lower.equals("clear") || lower.equals("reset")) {
            if (s.isRequestInFlight()) {
                displayLocalMessage("Please wait for the active reply to finish before clearing history.", ChatFormatting.YELLOW);
                return true;
            }
            s.resetConversationHistory();
            displayLocalMessage("Conversation history cleared.", ChatFormatting.GREEN);
            return true;
        }

        // 2. Help command
        if (lower.equals("help")) {
            displayLocalMessage(
                    "Press the Sayu key (default K) to open the chat screen. " +
                            "Chat commands: '@sayu <msg>', '@sayu clear', '@sayu status', " +
                            "'@sayu model [id]', '@sayu think <on|off|show|hide>', '@sayu search <auto|on|off>', " +
                            "'@sayu memory <note|clear>'.",
                    ChatFormatting.GOLD);
            return true;
        }

        // 3. Status command
        if (lower.equals("status")) {
            displayLocalMessage(String.format(
                    "Model: %s | Search: %s | Show  thoughts: %s | History: %d turns (%d/%d chars)",
                    s.getCurrentModel(), s.getSearchMode(),
                    s.isShowThinkingProcess() ? "ON" : "OFF",
                    s.getTurnCount(), s.getDialogueCharCount(), s.getMaxHistoryChars()
            ), ChatFormatting.GREEN);
            return true;
        }

        // 4. Model selection: @sayu model [id|list]
        if (lower.equals("model") || lower.startsWith("model ")) {
            String arg = input.substring("model".length()).trim().toLowerCase(Locale.ROOT);
            if (arg.isEmpty() || arg.equals("list")) {
                displayLocalMessage("Current model: " + s.getCurrentModel() + ". Available: " +
                        String.join(", ", SayuService.SUPPORTED_MODELS), ChatFormatting.AQUA);
                return true;
            }
            if (!s.setCurrentModel(arg)) {
                displayLocalMessage("Unknown model preset '" + arg + "'. Type '@sayu model list' to see supported models.", ChatFormatting.RED);
                return true;
            }
            displayLocalMessage("Switched model to: " + s.getCurrentModel(), ChatFormatting.GREEN);
            return true;
        }

        // 5. Thinking mode: @sayu think <on|off|show|hide>
        if (lower.equals("think") || lower.startsWith("think ")) {
            String arg = input.substring("think".length()).trim().toLowerCase(Locale.ROOT);
            if (arg.equals("show")) {
                s.setShowThinkingProcess(true);
                displayLocalMessage("Internal reasoning will now be shown in chat.", ChatFormatting.GREEN);
                return true;
            }
            if (arg.equals("hide")) {
                s.setShowThinkingProcess(false);
                displayLocalMessage("Internal reasoning will now be hidden from chat.", ChatFormatting.GREEN);
                return true;
            }
            if (arg.equals("on")) {
                if (s.setThinkingEnabled(true)) {
                    displayLocalMessage("Thinking mode enabled (model: " + s.getCurrentModel() + ").", ChatFormatting.GREEN);
                } else {
                    displayLocalMessage("Thinking already enabled (model: " + s.getCurrentModel() + ").", ChatFormatting.YELLOW);
                }
                return true;
            }
            if (arg.equals("off")) {
                if (s.setThinkingEnabled(false)) {
                    displayLocalMessage("Thinking mode disabled (model: " + s.getCurrentModel() + ").", ChatFormatting.GREEN);
                } else {
                    displayLocalMessage("Thinking already disabled (model: " + s.getCurrentModel() + ").", ChatFormatting.YELLOW);
                }
                return true;
            }
            displayLocalMessage("Usage: '@sayu think <on|off|show|hide>' (Current model: " + s.getCurrentModel() + ")", ChatFormatting.YELLOW);
            return true;
        }

        // 6. Web search control: @sayu search <auto|on|off>
        if (lower.equals("search") || lower.startsWith("search ")) {
            String arg = input.substring("search".length()).trim().toLowerCase(Locale.ROOT);
            SayuService.SearchMode mode;
            switch (arg) {
                case "on": mode = SayuService.SearchMode.ON; break;
                case "off": mode = SayuService.SearchMode.OFF; break;
                case "auto": mode = SayuService.SearchMode.AUTO; break;
                default:
                    displayLocalMessage("Current search mode: " + s.getSearchMode() + ". Usage: '@sayu search <auto|on|off>'.", ChatFormatting.YELLOW);
                    return true;
            }
            s.setSearchMode(mode);
            displayLocalMessage("Web search mode set to " + mode + ".", ChatFormatting.GREEN);
            return true;
        }

        // 7. Session memory note: @sayu memory <note|clear>
        if (lower.equals("memory") || lower.startsWith("memory ")) {
            String arg = input.substring("memory".length()).trim();
            if (arg.isEmpty()) {
                String current = s.getCustomMemoryNote().isEmpty() ? "(none)" : s.getCustomMemoryNote();
                displayLocalMessage("Active session memory note: " + current, ChatFormatting.AQUA);
                return true;
            }
            if (arg.equalsIgnoreCase("clear")) {
                s.setCustomMemoryNote("");
                displayLocalMessage("Session memory note cleared.", ChatFormatting.GREEN);
                return true;
            }
            s.setCustomMemoryNote(arg);
            displayLocalMessage("Updated session memory note.", ChatFormatting.GREEN);
            return true;
        }

        return false;
    }

    /**
     * Vanilla-chat display helper used by the legacy fallback path and by the
     * default-display listener above. Marshals onto the Minecraft client thread
     * via {@link Minecraft#execute} so callers from any thread are safe.
     */
    public static void displayLocalMessage(String text, ChatFormatting color) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
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
