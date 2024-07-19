package nl.c1rcu1tb04rd.minecraft.geotools;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class SplineUtil {

    public static List<BlockPos> generateSpline(List<BlockPos> points, int segments) {
        HashSet<BlockPos> splinePoints = new HashSet<>();

        if (points.size() < 2) {
            return points; // Not enough points to form a spline
        }

        for (int i = 0; i < points.size() - 1; i++) {
            BlockPos p0 = points.get(Math.max(i - 1, 0));
            BlockPos p1 = points.get(i);
            BlockPos p2 = points.get(i + 1);
            BlockPos p3 = points.get(Math.min(i + 2, points.size() - 1));

            for (int j = 0; j < segments; j++) {
                double t = (double) j / (double) segments;
                double[] interpolated = catmullRomSpline(
                        new double[]{p0.getX(), p0.getY(), p0.getZ()},
                        new double[]{p1.getX(), p1.getY(), p1.getZ()},
                        new double[]{p2.getX(), p2.getY(), p2.getZ()},
                        new double[]{p3.getX(), p3.getY(), p3.getZ()},
                        t
                );

                BlockPos blockPos = new BlockPos((int) interpolated[0], (int) interpolated[1], (int) interpolated[2]);
                splinePoints.add(blockPos);
            }
        }

        return new ArrayList<>(splinePoints);
    }

    private static double[] catmullRomSpline(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;

        double x = 0.5 * ((2 * p1[0]) +
                (-p0[0] + p2[0]) * t +
                (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 +
                (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3);

        double y = 0.5 * ((2 * p1[1]) +
                (-p0[1] + p2[1]) * t +
                (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 +
                (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3);

        double z = 0.5 * ((2 * p1[2]) +
                (-p0[2] + p2[2]) * t +
                (2 * p0[2] - 5 * p1[2] + 4 * p2[2] - p3[2]) * t2 +
                (-p0[2] + 3 * p1[2] - 3 * p2[2] + p3[2]) * t3);

        return new double[]{x, y, z};
    }
}