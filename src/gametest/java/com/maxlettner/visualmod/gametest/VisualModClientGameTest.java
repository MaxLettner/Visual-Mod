package com.maxlettner.visualmod.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.MixinEnvironment;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import com.maxlettner.visualmod.DynamicLights;
import com.maxlettner.visualmod.VisualConfig;
import com.maxlettner.visualmod.VisualModClient;

/**
 * Builds a sealed, pitch-black stone room with ores on the far wall, then checks each feature
 * with screenshots and a brightness measurement of the middle of the screen.
 *
 * <p>Run with {@code ./gradlew runClientGameTest}. Screenshots end up in {@code build/run/clientGameTest/screenshots}.
 */
public class VisualModClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.adjustSettings(creator -> creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
				.create()) {
			singleplayer.getConnection().waitForChunksRender();

			// Force every mixin to apply now; a wrong target fails the test here.
			MixinEnvironment.getCurrentEnvironment().audit();

			BlockPos p = context.computeOnClient(client -> client.player.blockPosition());
			int x = p.getX(), y = p.getY(), z = p.getZ();

			// Sealed 9x5x9 stone room around the player: no sky light gets in.
			cmd(singleplayer, "time set midnight");
			cmd(singleplayer, "fill %d %d %d %d %d %d minecraft:stone hollow", x - 5, y - 1, z - 5, x + 5, y + 5, z + 5);
			// Ores on the north wall.
			cmd(singleplayer, "setblock %d %d %d minecraft:diamond_ore", x - 2, y + 1, z - 5);
			cmd(singleplayer, "setblock %d %d %d minecraft:iron_ore", x, y + 1, z - 5);
			cmd(singleplayer, "setblock %d %d %d minecraft:coal_ore", x + 2, y + 1, z - 5);
			cmd(singleplayer, "setblock %d %d %d minecraft:gold_ore", x - 1, y + 2, z - 5);
			cmd(singleplayer, "setblock %d %d %d minecraft:emerald_ore", x + 1, y + 2, z - 5);
			cmd(singleplayer, "tp @p %d.5 %d %d.5 180 0", x, y, z + 2);
			context.waitTicks(5);
			context.getInput().lookAt(new BlockPos(x, y + 1, z - 5));
			settle(context, singleplayer);

			double dark = shot(context, "1_dark_room_empty_hand");
			check(!context.computeOnClient(client -> DynamicLights.hasSources()), "no light sources with an empty hand");

			// Held torch must light up the room.
			cmd(singleplayer, "item replace entity @p weapon.mainhand with minecraft:torch");
			settle(context, singleplayer);
			check(context.computeOnClient(client -> DynamicLights.hasSources()), "holding a torch registers a light source");
			double torch = shot(context, "2_holding_torch");
			check(torch > dark + 15, "holding a torch brightens the room (%.1f -> %.1f)".formatted(dark, torch));

			// Putting it away must remove the light again.
			cmd(singleplayer, "item replace entity @p weapon.mainhand with minecraft:air");
			settle(context, singleplayer);
			check(!context.computeOnClient(client -> DynamicLights.hasSources()), "light source removed after putting the torch away");
			double after = shot(context, "3_torch_put_away");
			check(Math.abs(after - dark) < 5, "room is dark again (%.1f vs %.1f)".formatted(after, dark));

			// A mob/armor stand holding a lantern.
			cmd(singleplayer, "summon minecraft:armor_stand %d %d %d", x, y, z - 2);
			context.waitTicks(5);
			cmd(singleplayer, "item replace entity @e[type=minecraft:armor_stand,limit=1] weapon.mainhand with minecraft:lantern");
			settle(context, singleplayer);
			double stand = shot(context, "4_armor_stand_with_lantern");
			check(stand > dark + 10, "armor stand holding a lantern lights the room (%.1f)".formatted(stand));
			cmd(singleplayer, "kill @e[type=minecraft:armor_stand]");

			// A dropped glowstone block.
			cmd(singleplayer, "summon minecraft:item %d.5 %d %d.5 {Item:{id:\"minecraft:glowstone\",count:1},PickupDelay:32767}", x, y, z - 3);
			settle(context, singleplayer);
			double dropped = shot(context, "5_dropped_glowstone");
			check(dropped > dark + 10, "dropped glowstone lights the room (%.1f)".formatted(dropped));
			cmd(singleplayer, "kill @e[type=minecraft:item]");
			settle(context, singleplayer);

			// Ore glow off, for comparison with screenshot 1.
			VisualConfig.oreGlow = false;
			CompletableFuture<?> reload = context.computeOnClient(client -> client.reloadResourcePacks());
			context.waitFor(client -> reload.isDone(), 20 * 60 * 5);
			settle(context, singleplayer);
			double noGlow = shot(context, "6_ore_glow_off");
			check(dark > noGlow + 0.5, "ores are brighter with ore glow on (%.2f on vs %.2f off)".formatted(dark, noGlow));
			VisualConfig.oreGlow = true;
		}
	}

	private static void cmd(TestSingleplayerContext singleplayer, String command, Object... args) {
		singleplayer.getServer().runCommand(args.length == 0 ? command : command.formatted(args));
	}

	/** Waits for commands to reach the client, light sources to update and chunks to re-mesh. */
	private static void settle(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		singleplayer.getConnection().waitForClientboundPackets();
		context.waitTicks(10);
		singleplayer.getConnection().waitForChunksRender();
		context.waitTicks(5);
	}

	/** Takes a screenshot and returns the mean brightness (0-255) of the middle third of the screen. */
	private static double shot(ClientGameTestContext context, String name) {
		Path path = context.takeScreenshot(name);

		try (InputStream in = Files.newInputStream(path); NativeImage image = NativeImage.read(in)) {
			int w = image.getWidth(), h = image.getHeight();
			long sum = 0;
			int count = 0;

			for (int py = h / 3; py < 2 * h / 3; py++) {
				for (int px = w / 3; px < 2 * w / 3; px++) {
					int argb = image.getPixel(px, py);
					sum += ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
					count += 3;
				}
			}

			double brightness = (double) sum / count;
			VisualModClient.LOGGER.info("[gametest] {}: brightness {}", name, "%.2f".formatted(brightness));
			return brightness;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}

		VisualModClient.LOGGER.info("[gametest] OK: {}", what);
	}
}
