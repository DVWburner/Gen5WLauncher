package com.gen5w.launcher.ipc;

import java.util.Locale;
import java.util.Map;

/**
 * Hardware-verified Gen5W temperature normalizer and cross-unit (°C <-> °F) converter.
 *
 * On Celsius Gen5W vehicles (Canada, Europe, UK, Australia, Korea, or vehicles set to °C),
 * DATCService leaves the unused DATC_AmbientTempF CAN byte at 0x00 and computes:
 *   convertExtandFahrenheit(0) = 0 - 0x28 = -40.0°F
 * while getAmbientTempC() holds the real ambient temperature (e.g. 14.0°C).
 * Symmetrically, on Fahrenheit vehicles, getAmbientTempC() is unpopulated (-40.0°C / state 255).
 */
public final class TemperatureNormalizer {
    private TemperatureNormalizer() {
    }

    public static void resolveAmbientPair(
            float rawC,
            int stateC,
            float rawF,
            int stateF,
            String tempUnitHint,
            Map<String, String> out) {
        if (out == null) {
            return;
        }
        boolean validC = stateC != 255 && stateC != -1 && !Float.isNaN(rawC) && rawC >= -50f && rawC <= 65f;
        boolean validF = stateF != 255 && stateF != -1 && !Float.isNaN(rawF) && rawF >= -60f && rawF <= 150f;
        boolean preferC = "C".equalsIgnoreCase(tempUnitHint);
        boolean preferF = "F".equalsIgnoreCase(tempUnitHint);

        if (validC && validF) {
            float expectedF = rawC * 9f / 5f + 32f;
            if (Math.abs(rawF - expectedF) > 3.5f) {
                if (Math.abs(rawF + 40f) < 0.7f && Math.abs(rawC + 40f) > 2.0f) {
                    // Unpopulated Fahrenheit CAN byte (0x00 -> -40.0F) on a Celsius vehicle
                    validF = false;
                } else if (Math.abs(rawC + 40f) < 0.7f && Math.abs(rawF + 40f) > 3.5f) {
                    // Unpopulated Celsius CAN byte (0x00 -> -40.0C) on a Fahrenheit vehicle
                    validC = false;
                } else if (preferC) {
                    validF = false;
                } else if (preferF) {
                    validC = false;
                }
            }
        }

        if (validC && !validF) {
            out.put("outside_temp", formatCompact(rawC));
            out.put("outside_temp_f", String.valueOf(Math.round(rawC * 9f / 5f + 32f)));
        } else if (validF && !validC) {
            out.put("outside_temp_f", String.valueOf(Math.round(rawF)));
            float convC = Math.round(((rawF - 32f) * 5f / 9f) * 2f) / 2f;
            out.put("outside_temp", formatCompact(convC));
        } else if (validC && validF) {
            out.put("outside_temp", formatCompact(rawC));
            out.put("outside_temp_f", String.valueOf(Math.round(rawF)));
        }
    }

    /**
     * Repairs vehicle reading maps (including raw HMG-API maps where outside_temp_f is "-40.0"
     * on Celsius cars) so both outside_temp (°C) and outside_temp_f (°F) are always consistent.
     */
    public static void normalizeVehicleMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Float cObj = parseFloatOrNull(values.get("outside_temp"));
        Float fObj = parseFloatOrNull(values.get("outside_temp_f"));
        String unitHint = values.get("climate.temp_unit");
        if (cObj != null && fObj != null) {
            resolveAmbientPair(cObj.floatValue(), 0, fObj.floatValue(), 0, unitHint, values);
        } else if (cObj != null) {
            float c = cObj.floatValue();
            if (c >= -50f && c <= 65f) {
                values.put("outside_temp", formatCompact(c));
                values.put("outside_temp_f", String.valueOf(Math.round(c * 9f / 5f + 32f)));
            }
        } else if (fObj != null) {
            float f = fObj.floatValue();
            // Do not synthesize Celsius from an isolated -40.0F reading if the car is known to be Celsius
            if ("C".equalsIgnoreCase(unitHint) && Math.abs(f + 40f) < 0.7f) {
                values.remove("outside_temp_f");
            } else if (f >= -60f && f <= 150f) {
                values.put("outside_temp_f", String.valueOf(Math.round(f)));
                float convC = Math.round(((f - 32f) * 5f / 9f) * 2f) / 2f;
                values.put("outside_temp", formatCompact(convC));
            }
        }
    }

    public static boolean isClimateValueCelsius(float rawVal, String carTempUnit) {
        if ("C".equalsIgnoreCase(carTempUnit)) return true;
        if ("F".equalsIgnoreCase(carTempUnit)) return false;
        return rawVal <= 42f;
    }

    public static float convertClimateTemp(float rawVal, String carTempUnit, String requestedUnit) {
        boolean carIsC = isClimateValueCelsius(rawVal, carTempUnit);
        String u = requestedUnit == null ? "" : requestedUnit.trim().toUpperCase(Locale.US);
        boolean wantF = u.contains("F") && !u.contains("C");
        boolean wantC = u.contains("C") && !u.contains("F");
        if (wantF && carIsC) {
            return Math.round(rawVal * 9f / 5f + 32f);
        }
        if (wantC && !carIsC) {
            return Math.round(((rawVal - 32f) * 5f / 9f) * 2f) / 2f;
        }
        return rawVal;
    }

    public static String formatCompact(float v) {
        if (Math.abs(v - Math.round(v)) < 0.05f) {
            return String.valueOf(Math.round(v));
        }
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static Float parseFloatOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "-".equals(t) || "--".equals(t)) return null;
        try {
            float v = Float.parseFloat(t);
            return Float.isNaN(v) || Float.isInfinite(v) ? null : Float.valueOf(v);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
