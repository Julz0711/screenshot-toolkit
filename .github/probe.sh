set +e
for j in $(find ~/.gradle /home/runner/work -name '*.jar' 2>/dev/null); do
  if unzip -l "$j" 2>/dev/null | grep -q 'net/minecraft/client/Minecraft.class'; then MC="$j"; fi
done
echo "MC jar: $MC"
FAPI=$(find ~/.gradle -name 'fabric-*.jar' | grep -E 'fabric-(screen-api|key-mapping|rendering|lifecycle-events|client-gametest|resource-loader)' | tr '\n' ':')
CP="$MC:$FAPI:$(find ~/.gradle -name '*.jar' | grep -E 'org.lwjgl|com.mojang|joml|gson|guava' | tr '\n' ':')"
P() { echo "##### $1"; javap -p -cp "$CP" "$1" 2>&1 | grep -v 'lambda\$'; }
C() { echo "##### CODE $1"; javap -p -c -cp "$CP" "$1" 2>&1 | grep -E '^  [a-z].*\(|invoke|getfield|putfield|getstatic|ldc' ; }
echo "== packages"
unzip -l "$MC" | grep -oE 'net/minecraft/client/[A-Za-z/]*\.class' | grep -v '\$' | grep -iE 'screenshot|toast|gui/screens/[A-Z]|texture/|Keyboard|Mouse|gui/components/[A-Z]|Util|platform/' | sed 's/.class$//' | sort
unzip -l "$MC" | grep -oE '(com/mojang/blaze3d|net/minecraft/util|net/minecraft)/[A-Za-z]*\.class' | grep -iE 'NativeImage|Util\.|Screenshot|Clipboard' | sort
for c in net.minecraft.client.Screenshot com.mojang.blaze3d.platform.NativeImage net.minecraft.client.KeyboardHandler \
  net.minecraft.client.renderer.texture.DynamicTexture net.minecraft.client.renderer.texture.AbstractTexture net.minecraft.client.renderer.texture.TextureManager \
  net.minecraft.client.gui.GuiGraphicsExtractor net.minecraft.client.gui.screens.Screen net.minecraft.client.gui.components.events.AbstractContainerEventHandler \
  net.minecraft.client.gui.components.AbstractWidget net.minecraft.client.gui.components.Button 'net.minecraft.client.gui.components.Button$Builder' net.minecraft.client.gui.components.EditBox net.minecraft.client.gui.components.CycleButton \
  net.minecraft.client.gui.components.toasts.Toast net.minecraft.client.gui.components.toasts.ToastManager net.minecraft.client.gui.components.toasts.SystemToast \
  net.minecraft.client.gui.screens.ConfirmScreen net.minecraft.client.gui.Gui net.minecraft.client.Options net.minecraft.client.Minecraft net.minecraft.Util \
  net.minecraft.util.Util 'net.minecraft.util.Util$OS' net.minecraft.client.input.MouseButtonEvent net.minecraft.client.input.KeyEvent net.minecraft.client.input.MouseButtonInfo \
  com.mojang.blaze3d.pipeline.RenderTarget net.minecraft.client.renderer.RenderPipelines net.minecraft.client.multiplayer.ServerData net.minecraft.client.gui.components.ChatComponent \
  net.minecraft.client.renderer.GameRenderer net.minecraft.client.gui.screens.options.OptionsSubScreen net.minecraft.client.gui.components.ObjectSelectionList net.minecraft.client.gui.components.AbstractSelectionList \
  net.minecraft.client.gui.components.Renderable net.minecraft.client.gui.layouts.HeaderAndFooterLayout net.minecraft.client.input.InputWithModifiers net.minecraft.client.input.CharacterEvent \
  net.fabricmc.fabric.api.client.screen.v1.ScreenEvents net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext net.fabricmc.fabric.api.client.gametest.v1.TestInput \
  net.minecraft.server.MinecraftServer net.minecraft.world.level.storage.WorldData com.mojang.blaze3d.platform.Window com.mojang.blaze3d.platform.InputConstants; do P "$c"; done
C net.minecraft.client.Screenshot
C net.minecraft.client.KeyboardHandler | grep -iE -B2 -A40 'screenshot' | head -150
echo "== screen in code"; javap -p -c -cp "$CP" net.minecraft.client.Minecraft | grep -iE 'Field .*screen|Method .*Screen|toast|Field .*gui:|hideGui' | sort | uniq -c | head -40
echo "== options hideGui"; javap -p -cp "$CP" net.minecraft.client.Options | grep -iE 'hideGui|screenshot|keyScreenshot|hide'
