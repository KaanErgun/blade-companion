package com.kaanergun.bladecompanion.core;

/** Coordinates always refer to the displayed image, excluding fit-center letterboxing. */
public final class FrameCoordinates {
    private FrameCoordinates() { }

    public static final class Point {
        public final double x;
        public final double y;

        public Point(double x, double y) {
            if (!finite(x) || !finite(y) || x < 0 || x > 1 || y < 0 || y > 1) {
                throw new IllegalArgumentException("Point must be finite and normalized");
            }
            this.x = x;
            this.y = y;
        }
    }

    public static final class Viewport {
        public final double left;
        public final double top;
        public final double width;
        public final double height;

        public Viewport(int imageWidth, int imageHeight, int viewWidth, int viewHeight) {
            if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
                throw new IllegalArgumentException("Image and viewport dimensions must be positive");
            }
            double scale = Math.min((double) viewWidth / imageWidth, (double) viewHeight / imageHeight);
            width = imageWidth * scale;
            height = imageHeight * scale;
            left = (viewWidth - width) / 2;
            top = (viewHeight - height) / 2;
        }

        /** Returns null for letterbox touches; never clamps them onto a different image position. */
        public Point fromView(double x, double y) {
            if (!finite(x) || !finite(y) || x < left || x > left + width || y < top || y > top + height) {
                return null;
            }
            // The hit test already excluded letterboxing; cap only floating-point edge rounding.
            return new Point(Math.max(0, Math.min(1, (x - left) / width)),
                    Math.max(0, Math.min(1, (y - top) / height)));
        }

        public double viewX(Point point) { return left + point.x * width; }
        public double viewY(Point point) { return top + point.y * height; }
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
