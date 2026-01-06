package nl.c1rcu1tb04rd.minecraft.geotools;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;

import java.util.*;

public class GeoToolsClient implements ClientModInitializer {
	private static final List<BlockPos> selectedCorners = new ArrayList<>();
	private static boolean selectionActive = false;
	private static String selectionType = "";

	private final OutlineRenderer outlineRenderer = new OutlineRenderer();

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);
		UseBlockCallback.EVENT.register(this::onBlockUse);

		// IMPORTANT: With the .world API, do the extraction+draw in a supported phase.
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(this::onRenderWorld);
	}

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, net.minecraft.command.CommandRegistryAccess commandRegistryAccess) {
		final LiteralCommandNode<FabricClientCommandSource> selectCommandNode = dispatcher.register(
				ClientCommandManager.literal("select")
						.then(ClientCommandManager.argument("type", StringArgumentType.word())
								.suggests((context, builder) -> builder.suggest("cuboid").suggest("plane").suggest("curve").buildFuture())
								.executes(context -> {
									String type = StringArgumentType.getString(context, "type");
									if ("cuboid".equals(type) || "plane".equals(type) || "curve".equals(type)) {
										startSelection(context.getSource(), type);
									} else {
										context.getSource().sendFeedback(Text.of("Unknown selection type"));
									}
									return 1;
								}))
						.then(ClientCommandManager.literal("clear")
								.executes(context -> {
									clearSelection();
									context.getSource().sendFeedback(Text.of("Selection cleared"));
									return 1;
								}))
						.then(ClientCommandManager.literal("stop")
								.executes(context -> {
									selectionActive = false;
									context.getSource().sendFeedback(Text.of("Selection finished"));
									return 1;
								}))
		);

		dispatcher.register(ClientCommandManager.literal("sel").redirect(selectCommandNode));
	}

	private static void startSelection(FabricClientCommandSource source, String type) {
		selectedCorners.clear();
		selectionActive = true;
		selectionType = type;

		int blockCount = -1;
		if (type.equals("cuboid")) blockCount = 8;
		else if (type.equals("plane")) blockCount = 4;

		if (blockCount != -1) {
			source.sendFeedback(Text.of("Selection started. Click " + blockCount + " blocks to define the " + type + "."));
		} else {
			source.sendFeedback(Text.of("Selection started. Click blocks to define the " + type + ". When done: '/select stop'"));
		}
	}

	private void clearSelection() {
		selectedCorners.clear();
		selectionActive = false;
		selectionType = "";
	}

	private ActionResult onBlockUse(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
		if (!world.isClient()) return ActionResult.PASS;

		int requiredCorners = requiredCornerCount(selectionType);

		if (selectionActive && (requiredCorners == -1 || selectedCorners.size() < requiredCorners)) {
			BlockPos pos = hitResult.getBlockPos();
			selectedCorners.add(pos.toImmutable());
			player.sendMessage(Text.of("Block selected: " + pos.toShortString()), false);

			if (requiredCorners != -1 && selectedCorners.size() == requiredCorners) {
				selectionActive = false;
				player.sendMessage(Text.of(selectionType.substring(0, 1).toUpperCase() + selectionType.substring(1) + " selection completed."), false);
			}
			return ActionResult.FAIL;
		}

		return ActionResult.PASS;
	}

	private void onRenderWorld(WorldRenderContext context) {
		if (selectedCorners.isEmpty()) return;

		// Green = clicked control points
		Set<BlockPos> green = new HashSet<>(selectedCorners);
		Set<BlockPos> red = new HashSet<>();

		if ("curve".equals(selectionType)) {
			// Old behavior: always show the spline as soon as we have enough points,
			// even while selectionActive and also after /select stop.
			if (selectedCorners.size() >= 2) {
				int segments = 100; // same as old code
				List<BlockPos> sorted = sortCorners(new ArrayList<>(selectedCorners), selectionType);
				List<BlockPos> splinePoints = SplineUtil.generateSpline(sorted, segments);
				red.addAll(splinePoints);
			}

			red.removeAll(green);
			outlineRenderer.render(context, green, red);
			return;
		}

		// Cuboid/plane: keep your current “only show edges when complete” logic.
		int requiredCorners = requiredCornerCount(selectionType);
		if (requiredCorners != -1 && selectedCorners.size() == requiredCorners) {
			List<BlockPos> sorted = sortCorners(new ArrayList<>(selectedCorners), selectionType);

			if (selectionType.equals("cuboid")) {
				addEdgeBlocksCuboid(sorted, red);
			} else if (selectionType.equals("plane")) {
				addEdgeBlocksPlane(sorted, red);
			}

			red.removeAll(green);
		}

		outlineRenderer.render(context, green, red);
	}

	private static int requiredCornerCount(String selectionType) {
		return switch (selectionType) {
			case "cuboid" -> 8;
			case "plane" -> 4;
			default -> -1;
		};
	}

	// ----- Sorting helpers (your original logic, lightly cleaned) -----

	private static List<BlockPos> sortCorners(List<BlockPos> corners, String selectionType) {
		if (selectionType.equals("cuboid")) {
			corners.sort(Comparator.comparingInt(BlockPos::getY));
			List<BlockPos> bottom = sortFaceCorners(new ArrayList<>(corners.subList(0, 4)));
			List<BlockPos> top = sortFaceCorners(new ArrayList<>(corners.subList(4, 8)));
			List<BlockPos> out = new ArrayList<>(8);
			out.addAll(bottom);
			out.addAll(top);
			return out;
		}
		if (selectionType.equals("plane")) {
			return fixPlanePerfectDiagonal(sortFaceCorners(corners));
		}
		return corners;
	}

	private static List<BlockPos> sortFaceCorners(List<BlockPos> faceCorners) {
		double centerX = faceCorners.stream().mapToInt(BlockPos::getX).average().orElse(0);
		double centerZ = faceCorners.stream().mapToInt(BlockPos::getZ).average().orElse(0);

		faceCorners.sort((p1, p2) -> {
			double a1 = Math.atan2(p1.getZ() - centerZ, p1.getX() - centerX);
			double a2 = Math.atan2(p2.getZ() - centerZ, p2.getX() - centerX);
			return Double.compare(a1, a2);
		});

		return faceCorners;
	}

	private static List<BlockPos> fixPlanePerfectDiagonal(List<BlockPos> list) {
		if (list.size() != 4) throw new IllegalArgumentException("List must contain exactly 4 BlockPos elements");

		List<BlockPos> group1 = new ArrayList<>();
		List<BlockPos> group2 = new ArrayList<>();

		group1.add(list.get(0));
		for (int i = 1; i < list.size(); i++) {
			BlockPos pos = list.get(i);
			if (pos.getX() == group1.get(0).getX() && pos.getZ() == group1.get(0).getZ()) group1.add(pos);
			else group2.add(pos);
		}

		if (group1.size() == 2 && group2.size() == 2) {
			group1.sort(Comparator.comparingInt(Vec3i::getY));
			group2.sort(Comparator.comparingInt(Vec3i::getY));
			return List.of(group2.get(1), group1.get(1), group1.get(0), group2.get(0));
		}

		return list;
	}

	// ----- Edge generation (Bresenham, but add to a Set) -----

	private static void addEdgeBlocksPlane(List<BlockPos> c, Set<BlockPos> out) {
		bresenhamBlocks(c.get(0), c.get(1), out);
		bresenhamBlocks(c.get(1), c.get(2), out);
		bresenhamBlocks(c.get(2), c.get(3), out);
		bresenhamBlocks(c.get(3), c.get(0), out);
	}

	private static void addEdgeBlocksCuboid(List<BlockPos> c, Set<BlockPos> out) {
		// bottom face 0-1-2-3
		bresenhamBlocks(c.get(0), c.get(1), out);
		bresenhamBlocks(c.get(1), c.get(2), out);
		bresenhamBlocks(c.get(2), c.get(3), out);
		bresenhamBlocks(c.get(3), c.get(0), out);

		// top face 4-5-6-7
		bresenhamBlocks(c.get(4), c.get(5), out);
		bresenhamBlocks(c.get(5), c.get(6), out);
		bresenhamBlocks(c.get(6), c.get(7), out);
		bresenhamBlocks(c.get(7), c.get(4), out);

		// vertical edges
		bresenhamBlocks(c.get(0), c.get(4), out);
		bresenhamBlocks(c.get(1), c.get(5), out);
		bresenhamBlocks(c.get(2), c.get(6), out);
		bresenhamBlocks(c.get(3), c.get(7), out);
	}

	private static void bresenhamBlocks(BlockPos start, BlockPos end, Set<BlockPos> out) {
		int x1 = start.getX(), y1 = start.getY(), z1 = start.getZ();
		int x2 = end.getX(), y2 = end.getY(), z2 = end.getZ();

		int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), dz = Math.abs(z2 - z1);
		int sx = x1 < x2 ? 1 : -1;
		int sy = y1 < y2 ? 1 : -1;
		int sz = z1 < z2 ? 1 : -1;

		int err1, err2;
		if (dx >= dy && dx >= dz) {
			err1 = 2 * dy - dx; err2 = 2 * dz - dx;
			while (x1 != x2) {
				out.add(new BlockPos(x1, y1, z1));
				if (err1 > 0) { y1 += sy; err1 -= 2 * dx; }
				if (err2 > 0) { z1 += sz; err2 -= 2 * dx; }
				err1 += 2 * dy; err2 += 2 * dz;
				x1 += sx;
			}
		} else if (dy >= dx && dy >= dz) {
			err1 = 2 * dx - dy; err2 = 2 * dz - dy;
			while (y1 != y2) {
				out.add(new BlockPos(x1, y1, z1));
				if (err1 > 0) { x1 += sx; err1 -= 2 * dy; }
				if (err2 > 0) { z1 += sz; err2 -= 2 * dy; }
				err1 += 2 * dx; err2 += 2 * dz;
				y1 += sy;
			}
		} else {
			err1 = 2 * dy - dz; err2 = 2 * dx - dz;
			while (z1 != z2) {
				out.add(new BlockPos(x1, y1, z1));
				if (err1 > 0) { y1 += sy; err1 -= 2 * dz; }
				if (err2 > 0) { x1 += sx; err2 -= 2 * dz; }
				err1 += 2 * dy; err2 += 2 * dx;
				z1 += sz;
			}
		}
		out.add(new BlockPos(x1, y1, z1));
	}

	// Expose for mixin cleanup
	public OutlineRenderer getOutlineRenderer() {
		return outlineRenderer;
	}
}
