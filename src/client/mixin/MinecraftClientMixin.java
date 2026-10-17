package in.sayu.client.mixin;

import in.sayu.client.SayuClient;
import in.sayu.client.gui.SayuChatScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client tick hook for the Sayu keybind.
 *
 * <p>This replaces {@code ClientTickEvents.END_CLIENT_TICK} from Fabric API's
 * {@code fabric-events-api-v1} module, which is not on this project's compile
 * classpath. Vanilla Minecraft already polls registered {@code KeyMapping}s
 * in {@link Minecraft#handleKeybinds()}, but that does not propagate
 * {@code consumeClick()} to us without a tick hook. This mixin injects at the
 * HEAD of {@link Minecraft#tick()} (called every game tick while the world is
 * running and not paused) and drains any pending clicks on the Sayu keybind,
 * opening the chat screen when no other screen is open.</p>
 *
 * <p>Vanilla does not call {@code KeyMapping.setDown()} (and therefore does
 * not accumulate {@code clickCount}) while any {@code Screen} is open, so
 * {@code consumeClick()} returns false while the Sayu screen (or any other
 * screen) is open. This means the keybind cannot recursively re-open the
 * Sayu screen while it is already visible.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void sayu$onClientTick(CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        // Only handle the keybind while in-game with no other screen open.
        if (self.screen != null || self.player == null) return;
        // Drain any pending keypresses. Each press -> exactly one open.
        while (SayuClient.OPEN_SAYU_SCREEN.consumeClick()) {
            self.setScreen(new SayuChatScreen());
        }
    }
}
