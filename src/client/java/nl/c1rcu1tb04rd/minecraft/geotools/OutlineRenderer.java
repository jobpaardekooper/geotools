package nl.c1rcu1tb04rd.minecraft.geotools;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.Set;

public final class OutlineRenderer {

    public static void render(WorldRenderContext context, Set<BlockPos> green, Set<BlockPos> red) {
        if ((green == null || green.isEmpty()) && (red == null || red.isEmpty())) return;

        // IMPORTANT: In the .world API you generally buffer into the context's consumers.
        VertexConsumerProvider consumers = context.consumers();

        MatrixStack matrices = context.matrices();
        Vec3d cam = context.worldState().cameraRenderState.pos;

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        // This layer is intended for block-outline style rendering. [page:14]
        VertexConsumer vc = consumers.getBuffer(RenderLayers.secondaryBlockOutline());

        if (red != null) {
            for (BlockPos p : red) drawBlockOutline(matrices, vc, p, 0x80FF0000); // 50% alpha red
        }
        if (green != null) {
            for (BlockPos p : green) drawBlockOutline(matrices, vc, p, 0x8000FF00); // 50% alpha green
        }

        matrices.pop();
    }

    private static void drawBlockOutline(MatrixStack matrices, VertexConsumer vc, BlockPos pos, int argb) {
        double o = 0.002; // reduce z-fighting
        Box box = new Box(
                pos.getX() - o, pos.getY() - o, pos.getZ() - o,
                pos.getX() + 1 + o, pos.getY() + 1 + o, pos.getZ() + 1 + o
        );
        VoxelShape shape = VoxelShapes.cuboid(box);

        // Minecraft helper that emits the correct vertex elements for outline rendering. [page:15]
        VertexRendering.drawOutline(matrices, vc, shape, 0.0, 0.0, 0.0, argb, 5.0f);
    }
}
