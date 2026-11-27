package dev.lightmap.core;

/**
 * The transform between world block coordinates and screen coordinates for one map view:
 * {@code screen = centre + rotate(angle) * scale * (world - origin)}, with screen y pointing down
 * and world +x east, +z south. At angle 0 north is up.
 *
 * <p>Positions are taken relative to the origin in double precision before anything is narrowed to
 * float, so the view stays exact far from the world origin.
 */
public final class MapView {
    private double originX;
    private double originZ;
    private float centreX;
    private float centreY;
    private float scale;
    private float cos = 1;
    private float sin;

    public void set(double originX, double originZ, float centreX, float centreY, float scale, float angle) {
        this.originX = originX;
        this.originZ = originZ;
        this.centreX = centreX;
        this.centreY = centreY;
        this.scale = scale;
        this.cos = (float) Math.cos(angle);
        this.sin = (float) Math.sin(angle);
    }

    /**
     * Angle that puts the direction a player faces at the top of the view.
     *
     * @param yawDegrees Minecraft yaw: 0 faces south, 90 west, 180 north, -90 east
     */
    public static float headingUp(float yawDegrees) {
        return (float) (Math.PI - Math.toRadians(yawDegrees));
    }

    public float screenX(double worldX, double worldZ) {
        double dx = (worldX - originX) * scale;
        double dz = (worldZ - originZ) * scale;
        return (float) (centreX + dx * cos - dz * sin);
    }

    public float screenY(double worldX, double worldZ) {
        double dx = (worldX - originX) * scale;
        double dz = (worldZ - originZ) * scale;
        return (float) (centreY + dx * sin + dz * cos);
    }

    public double worldX(float screenX, float screenY) {
        double sx = screenX - centreX;
        double sy = screenY - centreY;
        return originX + (sx * cos + sy * sin) / scale;
    }

    public double worldZ(float screenX, float screenY) {
        double sx = screenX - centreX;
        double sy = screenY - centreY;
        return originZ + (-sx * sin + sy * cos) / scale;
    }

    public double originX() {
        return originX;
    }

    public double originZ() {
        return originZ;
    }

    public float scale() {
        return scale;
    }

    /**
     * World-space radius that covers a screen rectangle of the given size centred on the view
     * centre, whatever the rotation.
     */
    public double coverRadius(float width, float height) {
        return Math.sqrt((double) width * width + (double) height * height) * 0.5 / scale;
    }
}
