package in.sayu.client.gui;

import in.sayu.client.SayuService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dedicated AI chat screen for Sayu.
 *
 * <p>Layout:</p>
 * <pre>
 *  +----------------------------------------------------+
 *  | Header: title + model + search | M: ... | S: ... |   <- 28px
 *  +----------------------------------------------------+
 *  |                                                    |
 *  |             scrollable transcript                 |
 *  |             (user / Sayu / error / thinking)       |
 *  |                                                    |
 *  +----------------------------------------------------+
 *  | [composer EditBox................] [Send] [Clear] |   <- 56px
 *  +----------------------------------------------------+
 * </pre>
 *
 * <h2>Rendering</h2>
 * Dark modern panel that visually fits Minecraft. Transcript is wrapped via
 * {@link net.minecraft.client.gui.Font#split} and clipped via
 * {@link GuiGraphics#enableScissor}. User / Sayu / error / pending rows are
 * distinguished by header label and colour. Paragraph breaks are preserved.
 *
 * <h2>Lifecycle</h2>
 * The screen registers itself as a {@link SayuService.Listener} on
 * {@link #init()} and unregisters on {@link #removed()}. If the screen closes
 * while a request is in flight, the request keeps running (the service owns
 * the future); when it completes the result is appended to history exactly
 * once and is visible when the screen reopens.
 *
 * <h2>State sharing</h2>
 * All conversation history, model, search, thinking, and memory state lives
 * in {@link SayuService}. The legacy {@code @sayu} chat commands and this GUI
 * read/write the same state, so changes from one path are immediately
 * reflected in the other.
 */
public class SayuChatScreen extends Screen implements SayuService.Listener {

    // --- Layout constants ---
    private static final int HEADER_HEIGHT = 28;
    private static final int COMPOSER_HEIGHT = 56;
    private static final int PADDING = 8;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int LINE_SPACING = 2;
    private static final int SCROLL_STEP = 20;  // pixels per scroll notch

    // --- Widgets ---
    private EditBox composer;
    private Button sendButton;
    private Button clearButton;
    private Button modelButton;
    private Button searchButton;
    private Button retryButton;

    // --- Scroll / snapshot state ---
    /** Current vertical scroll offset in pixels (0 = top). */
    private int scrollOffset = 0;
    /** Computed during render; total wrapped content height in pixels. */
    private int totalContentHeight = 0;
    /**
     * If set to {@link Integer#MAX_VALUE}, the next render will pin the scroll
     * to the bottom (used after sending / receiving so the latest message is
     * visible).
     */
    private int pendingScrollRequest = 0;

    /** Snapshot refreshed each frame from the service. */
    private List<SayuService.DisplayMessage> displayMessages = new ArrayList<>();

    /** Clear-button confirmation state (double-click to confirm). */
    private boolean clearConfirming = false;
    private int clearConfirmTicksRemaining = 0;

    public SayuChatScreen() {
        super(Component.translatable("screen.sayu.title"));
    }

    // ---------- Lifecycle ----------

    @Override
    protected void init() {
        SayuService service = SayuService.INSTANCE;
        service.setGuiActive(true);
        service.addListener(this);

        int w = this.width;
        int composerTop = this.height - COMPOSER_HEIGHT;

        // --- Header cycle buttons (top-right) ---
        int modelBtnW = 110;
        int searchBtnW = 70;
        int btnH = 16;
        int btnY = (HEADER_HEIGHT - btnH) / 2;

        searchButton = Button.builder(
                Component.literal("S: " + service.getSearchMode()),
                b -> cycleSearch()
        ).bounds(w - PADDING - searchBtnW, btnY, searchBtnW, btnH).build();
        addRenderableWidget(searchButton);

        modelButton = Button.builder(
                Component.literal("M: " + shortModelName(service.getCurrentModel())),
                b -> cycleModel()
        ).bounds(w - PADDING - searchBtnW - 4 - modelBtnW, btnY, modelBtnW, btnH).build();
        addRenderableWidget(modelButton);

        // --- Composer area ---
        int sendW = 56;
        int clearW = 56;
        int composerLeft = PADDING;
        int composerRightEdge = w - PADDING - sendW - clearW - 8;
        int composerWidth = Math.max(120, composerRightEdge - composerLeft);

        composer = new EditBox(this.font, composerLeft, composerTop + 8,
                composerWidth, 20, Component.translatable("screen.sayu.composer"));
        composer.setMaxLength(4000);
        composer.setBordered(true);
        composer.setCanLoseFocus(false);
        composer.setResponder(s -> updateSendButtonState());
        addRenderableWidget(composer);
        setInitialFocus(composer);

        sendButton = Button.builder(Component.translatable("screen.sayu.send"), b -> onSend())
                .bounds(composerLeft + composerWidth + 4, composerTop + 8, sendW, 20)
                .build();
        addRenderableWidget(sendButton);

        clearButton = Button.builder(Component.translatable("screen.sayu.clear"), b -> onClearPressed())
                .bounds(composerLeft + composerWidth + sendW + 8, composerTop + 8, clearW, 20)
                .build();
        addRenderableWidget(clearButton);

        // Retry button (conditionally visible). Positioned just above composer on the right.
        retryButton = Button.builder(Component.translatable("screen.sayu.retry"), b -> onRetryPressed())
                .bounds(this.width - PADDING - 80, composerTop - 22, 80, 18)
                .build();
        retryButton.visible = false;
        addRenderableWidget(retryButton);

        refreshDisplayMessages();
        updateSendButtonState();
    }

    @Override
    public void removed() {
        SayuService service = SayuService.INSTANCE;
        service.removeListener(this);
        service.setGuiActive(false);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ---------- SayuService.Listener (all on client thread) ----------

    @Override public void onThinkingStart() {
        refreshDisplayMessages();
        pendingScrollRequest = Integer.MAX_VALUE;
        updateSendButtonState();
    }

    @Override public void onReplyComplete(String vis, String thk) {
        refreshDisplayMessages();
        pendingScrollRequest = Integer.MAX_VALUE;
        updateSendButtonState();
    }

    @Override public void onError(String msg, String failed) {
        refreshDisplayMessages();
        pendingScrollRequest = Integer.MAX_VALUE;
        updateSendButtonState();
    }

    @Override public void onHistoryCleared() {
        refreshDisplayMessages();
        scrollOffset = 0;
        pendingScrollRequest = 0;
    }

    @Override public void onStateChanged() {
        SayuService s = SayuService.INSTANCE;
        if (modelButton != null) {
            modelButton.setMessage(Component.literal("M: " + shortModelName(s.getCurrentModel())));
        }
        if (searchButton != null) {
            searchButton.setMessage(Component.literal("S: " + s.getSearchMode()));
        }
        refreshDisplayMessages();
    }

    // ---------- Snapshot ----------

    private void refreshDisplayMessages() {
        displayMessages = SayuService.INSTANCE.getDisplayMessages();
    }

    // ---------- Actions ----------

    private void onSend() {
        if (composer == null) return;
        String text = composer.getValue();
        if (text == null || text.isBlank()) return;
        SayuService service = SayuService.INSTANCE;
        if (service.isRequestInFlight()) return;

        String prompt = text.strip();
        if (service.sendPrompt(prompt)) {
            composer.setValue("");
            updateSendButtonState();
            refreshDisplayMessages();
            pendingScrollRequest = Integer.MAX_VALUE;
        } else {
            // Race: a request started between our check and the service's
            // CAS. Keep composer text so the user can retry.
            updateSendButtonState();
        }
    }

    private void onClearPressed() {
        SayuService service = SayuService.INSTANCE;
        if (service.isRequestInFlight()) return;
        if (clearConfirming) {
            service.resetConversationHistory();
            if (clearButton != null) {
                clearButton.setMessage(Component.translatable("screen.sayu.clear"));
            }
            clearConfirming = false;
            clearConfirmTicksRemaining = 0;
            refreshDisplayMessages();
            scrollOffset = 0;
            pendingScrollRequest = 0;
        } else {
            clearConfirming = true;
            clearConfirmTicksRemaining = 60;  // 3 seconds at 20 tps
            if (clearButton != null) {
                clearButton.setMessage(Component.translatable("screen.sayu.clear.confirm")
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }

    private void onRetryPressed() {
        SayuService service = SayuService.INSTANCE;
        if (service.isRequestInFlight()) return;
        if (service.retryLastFailed()) {
            refreshDisplayMessages();
            pendingScrollRequest = Integer.MAX_VALUE;
        }
    }

    private void updateSendButtonState() {
        if (sendButton == null) return;
        boolean canSend = composer != null
                && !composer.getValue().isBlank()
                && !SayuService.INSTANCE.isRequestInFlight();
        sendButton.active = canSend;
    }

    private void cycleModel() {
        SayuService s = SayuService.INSTANCE;
        List<String> models = new ArrayList<>(SayuService.SUPPORTED_MODELS);
        String curr = s.getCurrentModel();
        int idx = models.indexOf(curr);
        int next = (idx + 1) % models.size();
        if (s.setCurrentModel(models.get(next))) {
            modelButton.setMessage(Component.literal("M: " + shortModelName(s.getCurrentModel())));
        }
    }

    private void cycleSearch() {
        SayuService s = SayuService.INSTANCE;
        SayuService.SearchMode[] vals = SayuService.SearchMode.values();
        int idx = Arrays.asList(vals).indexOf(s.getSearchMode());
        int next = (idx + 1) % vals.length;
        s.setSearchMode(vals[next]);
        searchButton.setMessage(Component.literal("S: " + vals[next]));
    }

    /** Short label for the model button, e.g. "gemini-3.8-flash-thinking" -> "3.8T". */
    private static String shortModelName(String m) {
        if (m == null) return "?";
        if (m.startsWith("gemini-")) {
            String s = m.substring("gemini-".length());
            String[] parts = s.split("-");
            StringBuilder sb = new StringBuilder(parts[0]);  // 3.8 / 3.5 / 3.1
            boolean isPro = s.contains("-pro");
            boolean isLite = s.contains("-lite");
            boolean isThinking = s.endsWith("-thinking");
            if (isPro) sb.append("P");
            if (isLite) sb.append("L");
            if (isThinking) sb.append("T");
            return sb.toString();
        }
        if (m.startsWith("nano-banana")) {
            if (m.equals("nano-banana-pro")) return "NBP";
            if (m.equals("nano-banana-2")) return "NB2";
            if (m.equals("nano-banana-2-lite")) return "NB2L";
            return "NB";
        }
        return m.length() <= 12 ? m : m.substring(0, 12);
    }

    // ---------- Tick ----------

    @Override
    public void tick() {
        super.tick();
        // Note: EditBox in 1.21.1 has no public tick() method; cursor blink is
        // handled internally via Util.getMillis(), so we do not need to tick it.

        // Auto-reset the clear-confirmation timer
        if (clearConfirming) {
            if (clearConfirmTicksRemaining > 0) {
                clearConfirmTicksRemaining--;
            } else {
                clearConfirming = false;
                if (clearButton != null) {
                    clearButton.setMessage(Component.translatable("screen.sayu.clear"));
                }
            }
        }

        // Show the retry button only when there is an unresolved error
        SayuService s = SayuService.INSTANCE;
        boolean hasError = !s.getLastError().isBlank();
        if (retryButton != null) {
            retryButton.visible = hasError && !s.isRequestInFlight();
        }

        updateSendButtonState();
        refreshDisplayMessages();
    }

    // ---------- Rendering ----------

    @Override
    public void renderBackground(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Replace the default dirt-tiled background with a single dark fill so
        // super.render() (which calls this) does not clobber our header / composer bands.
        gfx.fill(0, 0, this.width, this.height, 0xF0101018);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // 1. Dark full-screen background
        renderBackground(gfx, mouseX, mouseY, partialTick);

        // 2. Header band + accent line
        gfx.fill(0, 0, this.width, HEADER_HEIGHT, 0xFF1F2330);
        gfx.fill(0, HEADER_HEIGHT, this.width, HEADER_HEIGHT + 1, 0xFF3D4A6B);

        // 3. Header text (left side): title + model + search indicator
        Component header = buildHeaderComponent();
        gfx.drawString(this.font, header, PADDING,
                (HEADER_HEIGHT - this.font.lineHeight) / 2, 0xFFFFFFFF);

        // 4. Composer band background (drawn BEFORE widgets so widgets sit on top)
        int composerTop = this.height - COMPOSER_HEIGHT;
        gfx.fill(0, composerTop, this.width, this.height, 0xFF1F2330);
        gfx.fill(0, composerTop, this.width, composerTop + 1, 0xFF3D4A6B);

        // 5. Widgets (composer, send, clear, model, search, retry) — super.render
        //    calls our (dark-fill-only) renderBackground, which is a no-op here
        //    because we already filled above, then iterates renderables.
        super.render(gfx, mouseX, mouseY, partialTick);

        // 6. Transcript (clipped to viewport between header and composer)
        renderTranscript(gfx);

        // 7. Scrollbar
        renderScrollbar(gfx);

        // 8. "Sayu is thinking..." indicator above the composer
        if (SayuService.INSTANCE.isRequestInFlight()) {
            gfx.drawString(this.font,
                    Component.literal("Sayu is thinking...")
                            .withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY),
                    PADDING, composerTop - this.font.lineHeight - 4, 0xFFFFFFFF);
        }
    }

    private Component buildHeaderComponent() {
        SayuService s = SayuService.INSTANCE;
        return Component.literal("")
                .append(Component.literal("Sayu").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD))
                .append(Component.literal("  \u00b7  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("Model: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(s.getCurrentModel()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  |  Search: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(s.getSearchMode().toString()).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("  |  Turns: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(Integer.toString(s.getTurnCount())).withStyle(ChatFormatting.GRAY));
    }

    /** Build and draw the wrapped transcript inside the clipped viewport. */
    private void renderTranscript(GuiGraphics gfx) {
        int top = HEADER_HEIGHT + PADDING;
        int bottom = this.height - COMPOSER_HEIGHT - PADDING;
        int left = PADDING;
        int right = this.width - PADDING - SCROLLBAR_WIDTH;
        int innerWidth = Math.max(40, right - left);
        int lineH = this.font.lineHeight + LINE_SPACING;

        // Build wrapped line list with role headers and paragraph preservation.
        List<RenderedLine> lines = new ArrayList<>();
        for (SayuService.DisplayMessage dm : displayMessages) {
            // Role header line
            Component header;
            if (dm.isPending()) {
                header = Component.literal("Sayu")
                        .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC)
                        .append(Component.literal(" is thinking...")
                                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            } else if ("user".equals(dm.role())) {
                header = Component.literal("You")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
            } else if (dm.isError()) {
                header = Component.literal("Error")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            } else {
                header = Component.literal("Sayu")
                        .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
            }
            for (FormattedCharSequence fc : this.font.split(header, innerWidth)) {
                lines.add(new RenderedLine(fc));
            }

            // Body
            if (!dm.isPending()) {
                String text = dm.content() == null ? "" : dm.content();
                if (!text.isEmpty()) {
                    ChatFormatting bodyStyle = dm.isError() ? ChatFormatting.RED
                            : "user".equals(dm.role()) ? ChatFormatting.WHITE
                            : ChatFormatting.GRAY;
                    String[] paragraphs = text.split("\n", -1);
                    for (String para : paragraphs) {
                        if (para.isEmpty()) {
                            lines.add(new RenderedLine(null));  // blank line preserves paragraph breaks
                            continue;
                        }
                        for (FormattedCharSequence fc : this.font.split(
                                Component.literal(para).withStyle(bodyStyle), innerWidth)) {
                            lines.add(new RenderedLine(fc));
                        }
                    }
                }
            }
            // Spacer between messages
            lines.add(new RenderedLine(null));
        }

        totalContentHeight = lines.size() * lineH;
        int viewportHeight = bottom - top;

        // Apply any pending scroll request first
        if (pendingScrollRequest == Integer.MAX_VALUE) {
            scrollOffset = Math.max(0, totalContentHeight - viewportHeight);
        } else if (pendingScrollRequest > 0) {
            scrollOffset = Math.max(0, Math.min(pendingScrollRequest,
                    Math.max(0, totalContentHeight - viewportHeight)));
        }
        pendingScrollRequest = 0;

        // Clamp scroll
        if (totalContentHeight <= viewportHeight) {
            scrollOffset = 0;
        } else {
            int maxScroll = totalContentHeight - viewportHeight;
            if (scrollOffset < 0) scrollOffset = 0;
            if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        }

        // Clip and draw
        gfx.enableScissor(left, top, right, bottom);
        int y = top - scrollOffset;
        for (RenderedLine line : lines) {
            if (y + lineH >= top && y <= bottom && line.formatted() != null) {
                gfx.drawString(this.font, line.formatted(), left + 4, y + 1, 0xFFFFFFFF, false);
            }
            y += lineH;
        }
        gfx.disableScissor();
    }

    private void renderScrollbar(GuiGraphics gfx) {
        int top = HEADER_HEIGHT + PADDING;
        int bottom = this.height - COMPOSER_HEIGHT - PADDING;
        int viewportHeight = bottom - top;
        int x = this.width - PADDING - SCROLLBAR_WIDTH;

        gfx.fill(x, top, x + SCROLLBAR_WIDTH, bottom, 0xFF2A2A33);
        if (totalContentHeight > viewportHeight) {
            int thumbH = Math.max(20, viewportHeight * viewportHeight / totalContentHeight);
            int maxScroll = totalContentHeight - viewportHeight;
            int thumbY = top + (int) ((float) scrollOffset / maxScroll * (viewportHeight - thumbH));
            gfx.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbH, 0xFF6A7AAE);
        }
    }

    // ---------- Input ----------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Esc closes
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        // Enter / Numpad Enter: send when composer has focus.
        // Shift+Enter inserts a newline (best-effort; EditBox is single-line
        // visually but stores the \n in its value, so the prompt is multi-line).
        if (getFocused() == composer
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
                if (composer != null) composer.insertText("\n");
                return true;
            }
            onSend();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // verticalAmount > 0 = scroll up (show earlier content)
        // verticalAmount < 0 = scroll down (show later content)
        int delta = (int) Math.signum(verticalAmount) * SCROLL_STEP;
        scrollOffset -= delta;
        return true;
    }

    // ---------- Inner types ----------

    /** A single rendered line; {@code formatted == null} means a blank spacer line. */
    private record RenderedLine(FormattedCharSequence formatted) {}
}