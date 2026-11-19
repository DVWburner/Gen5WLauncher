package android.graphics;

public final class Color {
    private Color() {}

    public static int parseColor(String colorString) {
        if (colorString == null || colorString.isEmpty() || colorString.charAt(0) != '#') {
            throw new IllegalArgumentException("Unknown color");
        }
        String hex = colorString.substring(1);
        long v = Long.parseLong(hex, 16);
        if (hex.length() == 6) {
            v |= 0xFF000000L;
        } else if (hex.length() != 8) {
            throw new IllegalArgumentException("Unknown color");
        }
        return (int) v;
    }
}
