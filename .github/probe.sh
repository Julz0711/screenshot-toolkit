set +e
for j in $(find ~/.gradle /home/runner/work -name '*.jar' 2>/dev/null); do
  if unzip -l "$j" 2>/dev/null | grep -q 'net/minecraft/client/Minecraft.class'; then MC="$j"; fi
done
CP="$MC:$(find ~/.gradle -name '*.jar' | grep -E 'fabric|org.lwjgl|com.mojang|joml|gson|guava|commons-lang|fastutil|slf4j' | tr '\n' ':')"
for c in net.minecraft.client.input.KeyEvent net.minecraft.client.input.MouseButtonEvent net.minecraft.client.input.MouseButtonInfo net.minecraft.client.input.InputWithModifiers net.minecraft.client.gui.components.Checkbox; do echo "===== $c"; javap -p -cp "$CP" $c 2>&1 | head -60; done
javap -p -cp "$CP" net.minecraft.client.Minecraft | grep -iE "down\(|Down"
javap -p -cp "$CP" net.minecraft.client.gui.screens.Screen | grep -iE "down|static"
javap -p -cp "$CP" com.mojang.blaze3d.platform.InputConstants | grep -iE "KEY_A |KEY_A;|MOD_|KEY_ESCAPE"
