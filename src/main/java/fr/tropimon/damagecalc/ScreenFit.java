package fr.tropimon.damagecalc;

/** Fits an existing panel without changing the player's global GUI setting. */
record ScreenFit(int viewportWidth, int viewportHeight, int width, int height, float scale) {
    static ScreenFit of(int width, int height, int minimumWidth, int minimumHeight) {
        float scale = Math.min(1F, Math.min(width / (float) minimumWidth, height / (float) minimumHeight));
        return new ScreenFit(width, height, (int) Math.floor(width / scale),
                (int) Math.floor(height / scale), scale);
    }

    double local(double coordinate) { return coordinate / scale; }
    int pixel(double coordinate) { return (int) Math.floor(coordinate * scale); }
}
