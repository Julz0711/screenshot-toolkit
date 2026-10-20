set +e
for j in $(find ~/.gradle /home/runner/work -name '*.jar' 2>/dev/null); do
  if unzip -l "$j" 2>/dev/null | grep -q 'net/minecraft/client/Minecraft.class'; then MC="$j"; fi
done
CP="$MC:$(find ~/.gradle -name '*.jar' | grep -E 'fabric|org.lwjgl|com.mojang|joml|gson|guava|commons-lang|fastutil|slf4j' | tr '\n' ':')"
M() { echo "===== $1 :: $2"; javap -p -c -cp "$CP" "$1" 2>&1 | awk -v m="$2" 'index($0,m)&&/^  [a-z]/{p=1} p{print} p&&/^$/{p=0}' | grep -vE '^\s+[0-9]+: (aload|iload|dload|areturn|ireturn|return|iconst|dup|pop)' | head -120; }
M net.minecraft.client.gui.components.AbstractWidget "mouseClicked("
M net.minecraft.client.gui.components.AbstractWidget "mouseReleased("
M net.minecraft.client.gui.components.AbstractWidget "onClick("
M net.minecraft.client.gui.components.AbstractWidget "onRelease("
M net.minecraft.client.gui.components.AbstractButton "onClick("
M net.minecraft.client.gui.components.AbstractButton "onRelease("
M net.minecraft.client.gui.components.AbstractButton "mouseReleased("
M net.minecraft.client.gui.components.AbstractButton "mouseClicked("
M net.minecraft.client.gui.components.events.ContainerEventHandler "mouseClicked("
M net.minecraft.client.gui.components.events.ContainerEventHandler "mouseReleased("
M net.minecraft.client.gui.screens.Screen "mouseClicked("
M net.minecraft.client.gui.screens.Screen "mouseReleased("
M net.minecraft.client.MouseHandler "onButton("
javap -p -cp "$CP" net.minecraft.client.gui.components.AbstractButton net.minecraft.client.gui.components.Button
