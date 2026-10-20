set +e
for j in $(find ~/.gradle /home/runner/work -name '*.jar' 2>/dev/null); do
  if unzip -l "$j" 2>/dev/null | grep -q 'net/minecraft/client/Minecraft.class'; then MC="$j"; fi
done
CP="$MC:$(find ~/.gradle -name '*.jar' | grep -E 'fabric|org.lwjgl|com.mojang|joml|gson|guava|commons-lang|fastutil|slf4j' | tr '\n' ':')"
M() { echo "===== $1 :: $2"; javap -p -c -cp "$CP" "$1" 2>&1 | awk -v m="$2" 'index($0,m)&&/^  [a-z]/{p=1} p{print} p&&/^$/{p=0}' | head -80; }
M net.minecraft.client.gui.components.AbstractWidget "boolean mouseClicked("
M net.minecraft.client.gui.components.AbstractWidget "boolean isMouseOver("
M net.minecraft.client.gui.components.AbstractWidget "boolean isValidClickButton("
M net.minecraft.client.gui.components.AbstractButton "void onClick("
M net.minecraft.client.gui.components.Button "void onPress("
M net.minecraft.client.gui.components.AbstractWidget "playDownSound("
javap -p -cp "$CP" net.minecraft.client.gui.components.AbstractWidget | grep -iE "click|press|double"
