package com.julz.screenshottoolkit.mixin;

import com.julz.screenshottoolkit.client.CleanShot;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** runTick runs once per rendered frame; the clean-shot key needs one frame drawn without the HUD. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void screenshotToolkit$onFrame(CallbackInfo ci) {
        CleanShot.onFrame((Minecraft) (Object) this);
    }
}
