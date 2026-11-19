package com.maxlettner.visualmod.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.MixinEnvironment;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import com.maxlettner.visualmod.ColoredLights;
import com.maxlettner.visualmod.DynamicLights;
import com.maxlettner.visualmod.VisualModClient;

/**
 * Builds a sealed, pitch-black stone room, then checks each dynamic light source with screenshots
 * and a brightness measurement of the whole screen.
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
			cmd(singleplayer, "gamerule send_command_feedback false");
			cmd(singleplayer, "time set midnight");
			cmd(singleplayer, "fill %d %d %d %d %d %d minecraft:stone hollow", x - 5, y - 1, z - 5, x + 5, y + 5, z + 5);
			// Stand 4 blocks from the north wall, with the HUD hidden so only the world is measured.
			cmd(singleplayer, "tp @p %d.5 %d %d.5 180 0", x, y, z - 1);
			context.runOnClient(client -> {
				if (!client.gui.hud.isHidden()) {
					client.gui.hud.toggle();
				}
			});
			context.waitTicks(5);
			context.getInput().lookAt(new BlockPos(x, y + 1, z - 5));
			settle(context, singleplayer);

			double dark = shot(context, "1_dark_room_empty_hand");
			check(dark < 20, "the sealed room is dark without a light source (mean brightness %.1f)".formatted(dark));
			check(!context.computeOnClient(client -> DynamicLights.hasSources()), "no light sources with an empty hand");

			// Held torch must light up the room.
			cmd(singleplayer, "item replace entity @p weapon.mainhand with minecraft:torch");
			settle(context, singleplayer);
			check(context.computeOnClient(client -> DynamicLights.hasSources()), "holding a torch registers a light source");
			double torch = shot(context, "2_holding_torch");
			check(torch > dark * 1.5, "holding a torch brightens the room (%.1f -> %.1f)".formatted(dark, torch));

			// Putting it away must remove the light again.
			cmd(singleplayer, "item replace entity @p weapon.mainhand with minecraft:air");
			settle(context, singleplayer);
			check(!context.computeOnClient(client -> DynamicLights.hasSources()), "light source removed after putting the torch away");
			double after = shot(context, "3_torch_put_away");
			check(Math.abs(after - dark) < 2, "room is dark again (%.1f vs %.1f)".formatted(after, dark));

			// A mob/armor stand holding a lantern.
			cmd(singleplayer, "summon minecraft:armor_stand %d.5 %d %d.5", x + 2, y, z - 3);
			context.waitTicks(5);
			cmd(singleplayer, "item replace entity @e[type=minecraft:armor_stand,limit=1] weapon.mainhand with minecraft:lantern");
			settle(context, singleplayer);
			double stand = shot(context, "4_armor_stand_with_lantern");
			check(stand > dark * 1.3, "armor stand holding a lantern lights the room (%.1f)".formatted(stand));
			cmd(singleplayer, "kill @e[type=minecraft:armor_stand]");

			// A dropped glowstone block.
			cmd(singleplayer, "summon minecraft:item %d.5 %d %d.5 {Item:{id:\"minecraft:glowstone\",count:1},PickupDelay:32767}", x - 2, y, z - 3);
			settle(context, singleplayer);
			double dropped = shot(context, "5_dropped_glowstone");
			check(dropped > dark * 1.3, "dropped glowstone lights the room (%.1f)".formatted(dropped));
			cmd(singleplayer, "kill @e[type=minecraft:item]");
			settle(context, singleplayer);

			// Coloured light: each placed torch type tints the room in its own colour.
			coloredTorch(context, singleplayer, "torch", x, y, z, 0, "warm orange: red > green > blue");
			coloredTorch(context, singleplayer, "soul_torch", x, y, z, 2, "cyan-blue: blue dominates");
			coloredTorch(context, singleplayer, "redstone_torch", x, y, z, 0, "red dominates");
			coloredTorch(context, singleplayer, "copper_torch", x, y, z, 1, "green dominates");
			check(context.computeOnClient(client -> ColoredLights.indexedSourceCount()) == 0, "removed torches leave the colour index");

			// A held soul torch tints the room blue too.
			cmd(singleplayer, "item replace entity @p weapon.mainhand with minecraft:soul_torch");
			settle(context, singleplayer);
			shot(context, "10_holding_soul_torch");
			check(lastRgb[2] > lastRgb[0], "held soul torch gives blue light (r %.1f, b %.1f)".formatted(lastRgb[0], lastRgb[2]));
			cmd(singleplayer, "item replace entity @p weapon.mainhand with minecraft:air");
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

	private static int coloredShot = 6;

	/** Places a torch in the room, checks that colour channel {@code channel} (0 r, 1 g, 2 b) is the strongest, removes it. */
	private static void coloredTorch(ClientGameTestContext context, TestSingleplayerContext singleplayer, String block, int x, int y, int z, int channel, String expected) {
		cmd(singleplayer, "setblock %d %d %d minecraft:%s", x, y, z - 3, block);
		settle(context, singleplayer);
		check(context.computeOnClient(client -> ColoredLights.indexedSourceCount()) == 1, block + " is in the colour index");
		shot(context, (coloredShot++) + "_placed_" + block);
		double[] c = lastRgb.clone();
		boolean ok = c[channel] > c[(channel + 1) % 3] && c[channel] > c[(channel + 2) % 3];

		if (block.equals("torch")) {
			ok = c[0] > c[1] && c[1] > c[2];
		}

		check(ok, "%s light is %s (r %.1f, g %.1f, b %.1f)".formatted(block, expected, c[0], c[1], c[2]));
		cmd(singleplayer, "setblock %d %d %d minecraft:air", x, y, z - 3);
		settle(context, singleplayer);
	}

	/** Mean red, green and blue (0-255) of the last screenshot. */
	private static double[] lastRgb = new double[3];

	/** Takes a screenshot and returns the mean brightness (0-255) of the whole screen. */
	private static double shot(ClientGameTestContext context, String name) {
		Path path = context.takeScreenshot(name);

		try (InputStream in = Files.newInputStream(path); NativeImage image = NativeImage.read(in)) {
			int w = image.getWidth(), h = image.getHeight();
			long sum = 0;
			int count = 0;
			long red = 0, green = 0, blue = 0;

			for (int py = 0; py < h; py++) {
				for (int px = 0; px < w; px++) {
					int argb = image.getPixel(px, py);
					red += (argb >> 16) & 0xFF;
					green += (argb >> 8) & 0xFF;
					blue += argb & 0xFF;
					sum += ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
					count += 3;
				}
			}

			double brightness = (double) sum / count;
			lastRgb = new double[] {red * 3.0 / count, green * 3.0 / count, blue * 3.0 / count};
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
