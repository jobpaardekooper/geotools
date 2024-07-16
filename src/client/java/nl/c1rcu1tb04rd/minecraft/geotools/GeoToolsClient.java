package nl.c1rcu1tb04rd.minecraft.geotools;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

public class GeoToolsClient implements ClientModInitializer {
	private static final List<BlockPos> selectedCorners = new ArrayList<>();
	private static boolean selectionActive = false;
	private static String selectionType = "";

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);
		UseBlockCallback.EVENT.register(this::onBlockUse);
		WorldRenderEvents.LAST.register(this::onRenderWorld);
	}

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess commandRegistryAccess) {
		final LiteralCommandNode<FabricClientCommandSource> selectCommandNode = dispatcher.register(ClientCommandManager.literal("select")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument("type", StringArgumentType.word())
						.suggests((context, builder) -> builder.suggest("cuboid").suggest("plane").buildFuture())
						.executes(context -> {
							String type = StringArgumentType.getString(context, "type");
							if ("cuboid".equals(type) || "plane".equals(type)) {
								startSelection(context.getSource(), type);
							} else {
								context.getSource().sendFeedback(Text.of("Unknown selection type"));
							}
							return 1;
						}))
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("clear")
						.executes(context -> {
							clearSelection();
							context.getSource().sendFeedback(Text.of("Selection cleared"));
							return 1;
						})));

		dispatcher.register(ClientCommandManager.literal("sel").redirect(selectCommandNode));
	}

	private static void startSelection(FabricClientCommandSource source, String type) {
		selectedCorners.clear();
		selectionActive = true;
		selectionType = type;
		source.sendFeedback(Text.of("Selection started. Click " + (type.equals("cuboid") ? "8" : "4") + " blocks to define the " + type + "."));
	}

	private void clearSelection() {
		selectedCorners.clear();
		selectionActive = false;
		selectionType = "";
	}

	private ActionResult onBlockUse(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
		if (!world.isClient()) {
			return ActionResult.PASS;
		}

		int requiredCorners = selectionType.equals("cuboid") ? 8 : 4;
		if (selectionActive && selectedCorners.size() < requiredCorners) {
			BlockPos pos = hitResult.getBlockPos();
			selectedCorners.add(pos);
			player.sendMessage(Text.of("Block selected: " + pos.toShortString()), false);
			if (selectedCorners.size() == requiredCorners) {
				selectionActive = false;
				player.sendMessage(Text.of(selectionType.substring(0, 1).toUpperCase() + selectionType.substring(1) + " selection completed."), false);
			}
			return ActionResult.FAIL;
		}
		return ActionResult.PASS;
	}

	private void onRenderWorld(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) {
			return;
		}

		drawCorners(context.matrixStack(), context.camera());

		int requiredCorners = selectionType.equals("cuboid") ? 8 : 4;
		if (selectedCorners.size() != requiredCorners) {
			return;
		}

		List<BlockPos> sortedCorners = sortCorners(selectedCorners);

		if (selectionType.equals("cuboid")) {
			BlockPos corner1 = sortedCorners.get(0);
			BlockPos corner2 = sortedCorners.get(1);
			BlockPos corner3 = sortedCorners.get(2);
			BlockPos corner4 = sortedCorners.get(3);
			BlockPos corner5 = sortedCorners.get(4);
			BlockPos corner6 = sortedCorners.get(5);
			BlockPos corner7 = sortedCorners.get(6);
			BlockPos corner8 = sortedCorners.get(7);

			drawSelectionOutline(context.matrixStack(), context.camera(), corner1, corner2, corner3, corner4, corner5, corner6, corner7, corner8);
		} else if (selectionType.equals("plane")) {
			BlockPos corner1 = sortedCorners.get(0);
			BlockPos corner2 = sortedCorners.get(1);
			BlockPos corner3 = sortedCorners.get(2);
			BlockPos corner4 = sortedCorners.get(3);

			drawPlaneOutline(context.matrixStack(), context.camera(), corner1, corner2, corner3, corner4);
		}
	}

	private List<BlockPos> sortCorners(List<BlockPos> corners) {
		if (selectionType.equals("cuboid")) {
			// Sort corners by y-coordinate
			corners.sort(Comparator.comparingInt(BlockPos::getY));

			// Select the first 4 corners as the bottom face and the remaining 4 as the top face
			List<BlockPos> bottomCorners = new ArrayList<>(corners.subList(0, 4));
			List<BlockPos> topCorners = new ArrayList<>(corners.subList(4, 8));

			// Sort the corners within each face
			bottomCorners = sortFaceCorners(bottomCorners);
			topCorners = sortFaceCorners(topCorners);

			// Combine the sorted corners of the bottom and top faces
			List<BlockPos> result = new ArrayList<>(8);
			result.addAll(bottomCorners);
			result.addAll(topCorners);

			return result;
		} else {
			// Sort corners by x and z coordinates for a plane
			return sortFaceCorners(corners);
		}
	}

	private List<BlockPos> sortFaceCorners(List<BlockPos> faceCorners) {
		// Find the centroid of the face
		double centerX = faceCorners.stream().mapToInt(BlockPos::getX).average().orElse(0);
		double centerZ = faceCorners.stream().mapToInt(BlockPos::getZ).average().orElse(0);

		// Sort the corners in counterclockwise order around the centroid
		faceCorners.sort((pos1, pos2) -> {
			double angle1 = Math.atan2(pos1.getZ() - centerZ, pos1.getX() - centerX);
			double angle2 = Math.atan2(pos2.getZ() - centerZ, pos2.getX() - centerX);
			return Double.compare(angle1, angle2);
		});

		return faceCorners;
	}

	private void drawCorners(MatrixStack matrixStack, Camera camera) {
		// TODO: reuse the main parts of drawCorners and drawSekectionOutline
		Vec3d cameraPos = camera.getPos();
		matrixStack.push();
		matrixStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

		RenderSystem.disableDepthTest(); // Disable depth testing
		RenderSystem.enableBlend(); // Enable blending
		RenderSystem.defaultBlendFunc(); // Set the default blend function

		VertexConsumerProvider.Immediate vertexConsumers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vertexConsumer = vertexConsumers.getBuffer(OutlineBoxRenderType.OVERLAY_LINES);

		for (BlockPos corner: selectedCorners) {
			drawBlockOutline(matrixStack, vertexConsumer, corner, true);
		}

		vertexConsumers.draw();

		RenderSystem.enableDepthTest(); // Re-enable depth testing
		RenderSystem.disableBlend(); // Disable blending

		matrixStack.pop();
	}

	private void drawSelectionOutline(MatrixStack matrixStack, Camera camera, BlockPos... corners) {
		Vec3d cameraPos = camera.getPos();
		matrixStack.push();
		matrixStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

		RenderSystem.disableDepthTest(); // Disable depth testing
		RenderSystem.enableBlend(); // Enable blending
		RenderSystem.defaultBlendFunc(); // Set the default blend function

		VertexConsumerProvider.Immediate vertexConsumers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vertexConsumer = vertexConsumers.getBuffer(OutlineBoxRenderType.OVERLAY_LINES);

		// Draw edges between the corners
		drawLine(matrixStack, vertexConsumer, corners[0], corners[1]);
		drawLine(matrixStack, vertexConsumer, corners[1], corners[2]);
		drawLine(matrixStack, vertexConsumer, corners[2], corners[3]);
		drawLine(matrixStack, vertexConsumer, corners[3], corners[0]);

		drawLine(matrixStack, vertexConsumer, corners[4], corners[5]);
		drawLine(matrixStack, vertexConsumer, corners[5], corners[6]);
		drawLine(matrixStack, vertexConsumer, corners[6], corners[7]);
		drawLine(matrixStack, vertexConsumer, corners[7], corners[4]);

		drawLine(matrixStack, vertexConsumer, corners[0], corners[4]);
		drawLine(matrixStack, vertexConsumer, corners[1], corners[5]);
		drawLine(matrixStack, vertexConsumer, corners[2], corners[6]);
		drawLine(matrixStack, vertexConsumer, corners[3], corners[7]);

		vertexConsumers.draw();

		RenderSystem.enableDepthTest(); // Re-enable depth testing
		RenderSystem.disableBlend(); // Disable blending

		matrixStack.pop();
	}

	private void drawPlaneOutline(MatrixStack matrixStack, Camera camera, BlockPos... corners) {
		Vec3d cameraPos = camera.getPos();
		matrixStack.push();
		matrixStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

		RenderSystem.disableDepthTest(); // Disable depth testing
		RenderSystem.enableBlend(); // Enable blending
		RenderSystem.defaultBlendFunc(); // Set the default blend function

		VertexConsumerProvider.Immediate vertexConsumers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer vertexConsumer = vertexConsumers.getBuffer(OutlineBoxRenderType.OVERLAY_LINES);

		// Draw edges between the corners
		drawLine(matrixStack, vertexConsumer, corners[0], corners[1]);
		drawLine(matrixStack, vertexConsumer, corners[1], corners[2]);
		drawLine(matrixStack, vertexConsumer, corners[2], corners[3]);
		drawLine(matrixStack, vertexConsumer, corners[3], corners[0]);

		vertexConsumers.draw();

		RenderSystem.enableDepthTest(); // Re-enable depth testing
		RenderSystem.disableBlend(); // Disable blending

		matrixStack.pop();
	}

	private boolean isCornerBlock(BlockPos pos) {
		return selectedCorners.contains(pos);
	}

	private void drawLine(MatrixStack matrixStack, VertexConsumer vertexConsumer, BlockPos start, BlockPos end) {
		// Based on Bresenham algorithm
		int x1 = start.getX();
		int y1 = start.getY();
		int z1 = start.getZ();
		int x2 = end.getX();
		int y2 = end.getY();
		int z2 = end.getZ();

		int dx = Math.abs(x2 - x1);
		int dy = Math.abs(y2 - y1);
		int dz = Math.abs(z2 - z1);
		int sx = x1 < x2 ? 1 : -1;
		int sy = y1 < y2 ? 1 : -1;
		int sz = z1 < z2 ? 1 : -1;

		int err1, err2;
		if (dx >= dy && dx >= dz) {
			err1 = 2 * dy - dx;
			err2 = 2 * dz - dx;
			while (x1 != x2) {
				if (!isCornerBlock(new BlockPos(x1, y1, z1))) {
					drawBlockOutline(matrixStack, vertexConsumer, new BlockPos(x1, y1, z1), false);
				}
				if (err1 > 0) {
					y1 += sy;
					err1 -= 2 * dx;
				}
				if (err2 > 0) {
					z1 += sz;
					err2 -= 2 * dx;
				}
				err1 += 2 * dy;
				err2 += 2 * dz;
				x1 += sx;
			}
		} else if (dy >= dx && dy >= dz) {
			err1 = 2 * dx - dy;
			err2 = 2 * dz - dy;
			while (y1 != y2) {
				if (!isCornerBlock(new BlockPos(x1, y1, z1))) {
					drawBlockOutline(matrixStack, vertexConsumer, new BlockPos(x1, y1, z1), false);
				}
				if (err1 > 0) {
					x1 += sx;
					err1 -= 2 * dy;
				}
				if (err2 > 0) {
					z1 += sz;
					err2 -= 2 * dy;
				}
				err1 += 2 * dx;
				err2 += 2 * dz;
				y1 += sy;
			}
		} else {
			err1 = 2 * dy - dz;
			err2 = 2 * dx - dz;
			while (z1 != z2) {
				if (!isCornerBlock(new BlockPos(x1, y1, z1))) {
					drawBlockOutline(matrixStack, vertexConsumer, new BlockPos(x1, y1, z1), false);
				}
				if (err1 > 0) {
					y1 += sy;
					err1 -= 2 * dz;
				}
				if (err2 > 0) {
					x1 += sx;
					err2 -= 2 * dz;
				}
				err1 += 2 * dy;
				err2 += 2 * dx;
				z1 += sz;
			}
		}
		if (!isCornerBlock(new BlockPos(x1, y1, z1))) {
			drawBlockOutline(matrixStack, vertexConsumer, new BlockPos(x1, y1, z1), false); // Draw the last point
		}
	}

	private void drawBlockOutline(MatrixStack matrixStack, VertexConsumer vertexConsumer, BlockPos pos, Boolean isCorner) {
		final double offset = 0.001;
		Box box = new Box(((double)pos.getX())-offset, ((double)pos.getY())-offset, ((double)pos.getZ())-offset, ((double)pos.getX())+offset + 1, ((double)pos.getY())+offset + 1, ((double)pos.getZ())+offset + 1);
		if (isCorner) {
			WorldRenderer.drawBox(matrixStack, vertexConsumer, box, 0.0F, 1.0F, 0.0F, 0.5F);
		}
		else {
			WorldRenderer.drawBox(matrixStack, vertexConsumer, box, 1.0F, 0.0F, 0.0F, 0.5F); // Red color with 50% transparency
		}
	}

	public static class OutlineBoxRenderType extends RenderLayer {
		private static final RenderPhase.DepthTest NO_DEPTH_TEST = new RenderPhase.DepthTest("always", GL11.GL_ALWAYS);

		// Simplified RenderLayer configuration
		static final RenderLayer OVERLAY_LINES = of(
				"overlay_lines",
				VertexFormats.LINES,
				VertexFormat.DrawMode.LINES,
				1536,
				RenderLayer.MultiPhaseParameters.builder()
						.program(LINES_PROGRAM)
						.lineWidth(new RenderPhase.LineWidth(OptionalDouble.empty()))
						.layering(VIEW_OFFSET_Z_LAYERING)
						.transparency(TRANSLUCENT_TRANSPARENCY)
						.target(ITEM_ENTITY_TARGET)
						.writeMaskState(ALL_MASK)
						.cull(DISABLE_CULLING)
//						.depthTest(NO_DEPTH_TEST)
						.build(false));

		public OutlineBoxRenderType(
				String name,
				VertexFormat vertexFormat,
				VertexFormat.DrawMode drawMode,
				int expectedBufferSize,
				boolean hasCrumbling,
				boolean translucent,
				Runnable startAction,
				Runnable endAction) {
			super(
					name,
					vertexFormat,
					drawMode,
					expectedBufferSize,
					hasCrumbling,
					translucent,
					startAction,
					endAction);
		}
	}
}