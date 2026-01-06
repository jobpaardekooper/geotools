package nl.c1rcu1tb04rd.minecraft.geotools.mixin.client;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "close", at = @At("RETURN"))
	private void geotools$close(CallbackInfo ci) {
		// If you store the renderer somewhere else, call that instead.
		// This assumes you can reach your OutlineRenderer singleton.
		// (Implement a proper singleton if needed.)
	}
}
