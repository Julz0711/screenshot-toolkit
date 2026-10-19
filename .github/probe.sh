set +e
for j in $(find ~/.gradle /home/runner/work -name '*.jar' 2>/dev/null); do
  if unzip -l "$j" 2>/dev/null | grep -q 'net/minecraft/client/Minecraft.class'; then MC="$j"; fi
done
CP="$MC:$(find ~/.gradle -name '*.jar' | grep -E 'fabric|org.lwjgl|com.mojang|joml|gson|guava|commons-lang|fastutil|slf4j' | tr '\n' ':')"
P() { echo "##### $1"; javap -p -cp "$CP" "$1" 2>&1 | grep -v 'lambda\$' | grep -E "${2:-.}"; }
P net.minecraft.client.gui.Hud
P 'net.minecraft.network.chat.ClickEvent$OpenFile'
P net.minecraft.client.gui.components.events.ContainerEventHandler
P net.minecraft.client.gui.components.events.GuiEventListener
P net.minecraft.server.MinecraftServer 'WorldData|getWorldPath|LevelName'
P net.minecraft.client.multiplayer.ClientLevel 'Time|time|dimension|Clock|clock'
P net.minecraft.world.level.Level 'Time|time|dimension|Clock|clock'
P net.minecraft.world.entity.Entity ' get[XYZ]\(|getYRot|getXRot|position\(|level\('
P net.minecraft.client.Minecraft 'showDebugChat|getCurrentServer|execute|tell|schedule|getToastManager|getWindow|isWindowActive|hasSingleplayer|getSingleplayerServer|setScreen'
P 'net.minecraft.client.gui.components.toasts.Toast$Visibility'
P net.minecraft.client.renderer.RenderPipelines 'GUI'
P net.minecraft.client.gui.components.Tooltip 'static'
P net.minecraft.client.KeyMapping 'public'
P 'net.minecraft.client.KeyMapping$Category'
P com.mojang.blaze3d.platform.ClipboardManager
P org.lwjgl.sdl.SDLClipboard
P net.minecraft.resources.ResourceKey 'identifier|location|public'
P net.minecraft.resources.Identifier 'public static|toString'
P net.minecraft.client.gui.components.AbstractButton 'public|protected'
P net.minecraft.client.gui.components.StringWidget 'public'
P net.minecraft.client.gui.components.Checkbox 'public'
P net.minecraft.client.gui.layouts.LinearLayout 'public static|public'
P net.minecraft.network.chat.Component 'static'
P net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
P net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
P net.minecraft.client.gui.components.ImageButton 'public'
P net.minecraft.client.gui.components.SpriteIconButton 'public'
P net.minecraft.client.gui.screens.Screen 'mouse|Mouse|key'
echo "== who calls Screenshot.grab / takeScreenshot"
for c in $(unzip -l "$MC" | grep -oE 'net/minecraft/client/[A-Za-z/$]*\.class' | sed 's/.class$//;s#/#.#g'); do
  out=$(javap -p -c -cp "$CP" "$c" 2>/dev/null | grep -E '^  [a-z].*\(|Screenshot\.(grab|takeScreenshot)|ClickEvent\$OpenFile|hideGui|OpenFile\.|openFile|openPath|openUri|SDL_OpenURL|showItemInFolder|ShellExecute' | grep -B1 -E 'Screenshot\.|OpenFile|hideGui|openFile|openPath|openUri|SDL_OpenURL')
  if [ -n "$out" ]; then echo "--- $c"; echo "$out"; fi
done
echo "== classes with open in name"
unzip -l "$MC" | grep -oE '[A-Za-z/]*(Open|Opener|Browser|Desktop|FileUtil|Platform)[A-Za-z]*\.class' | sort -u
echo "== CODE Screen.defaultHandleClickEvent"
javap -p -c -cp "$CP" net.minecraft.client.gui.screens.Screen | sed -n '/defaultHandleClickEvent/,/^$/p' | grep -E 'invoke|getfield|checkcast|instanceof' | head -60
echo "== CODE Gui.handleKeybinds screenshot"
javap -p -c -cp "$CP" net.minecraft.client.gui.Gui | sed -n '/void handleKeybinds/,/^$/p' | grep -E 'invoke|getfield' | head -40
javap -p -c -cp "$CP" net.minecraft.client.KeyboardHandler | sed -n '/void keyPress/,/^$/p' | grep -E -B3 -A3 'keyScreenshot|Screenshot' | head -60
echo "== Hud CODE hide"
javap -p -c -cp "$CP" net.minecraft.client.gui.Hud | grep -iE 'hide|hidden' | sort | uniq -c | head
javap -p -c -cp "$CP" net.minecraft.client.renderer.GameRenderer | grep -iE 'hide|hidden|renderHand|Hand' | sort | uniq -c | head -20
