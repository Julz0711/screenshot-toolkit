package com.julz.screenshottoolkit.mixin;

import com.julz.screenshottoolkit.client.ScreenshotCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Consumer;

/**
 * Fabric API has no screenshot event, so this wraps the callback vanilla hands to the screenshot
 * code when the screenshot key is pressed. The callback runs once the file is written, with the
 * "Saved screenshot as" message that links to it.
 */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
    @ModifyArg(
            method = "grab(Lnet/minecraft/client/Minecraft;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Screenshot;grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V"),
            index = 2)
    private static Consumer<Component> screenshotToolkit$wrapCallback(Consumer<Component> vanilla) {
        return ScreenshotCapture.wrap(Minecraft.getInstance(), vanilla);
    }
}
