package com.gen5w.launcher.svc;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.gen5w.launcher.ipc.TemperatureNormalizer;

import hmgapi.client.HmgApi;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Clean-room direct Binder bridge running inside HomeSwitcher (root dalvikvm/app_process).
 * Resolves AIDL transaction codes at runtime via Stub.TRANSACTION_* fields so it adapts
 * cleanly across Gen5W firmware builds without hardcoded transaction numbers.
 */
final class DirectCarServiceBridge {
    private static final String SVC_HSCAN = "tm_hscan_service";
    private static final String IFACE_HSCAN = "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";
    private static final String JAR_HSCAN = "/system/framework/com.hkmc.ext.hcan.jar";

    private static final String SVC_SYSTEM = "com.hkmc.micom.system";
    private static final String IFACE_SYSTEM = "com.hkmc.micom.ISystemService";

    private static final String SVC_DATC = "com.hkmc.micom.datc";
    private static final String IFACE_DATC = "com.hkmc.datc.IDATCService";
    private static final String IFACE_DATC_LISTENER = "com.hkmc.datc.IDATCListener";
    private static final String CLS_DATC_STATUS = "com.hkmc.datc.DatcStatus";

    private static final String SVC_HEV = "com.hkmc.micom.hev";
    private static final String IFACE_HEV = "com.hkmc.hev.IHEVService";

    private static final String SVC_PGS = "com.hkmc.pgs";
    private static final String IFACE_PGS = "com.hkmc.pgs.IPgsService";

    private static final String SVC_BCM = "com.hkmc.micom.bcm";
    private static final String IFACE_BCM = "com.hkmc.bcm.IBCMService";
    private static final String IFACE_BCM_LISTENER = "com.hkmc.bcm.IBCMListener";

    private static final String SVC_USM = "com.hkmc.micom.usm";
    private static final String IFACE_USM = "com.hkmc.usm.IUSMService";

    private static final String SVC_VR = "com.hkmc.vr";
    private static final String IFACE_VR = "com.hkmc.vr.IVRService";

    private static final String SVC_AUDIO = "com.hkmc.micom.audiosetup";
    private static final String IFACE_AUDIO = "com.hkmc.media.IMicomAudioService";

    private static final int MISSING_TX = -2;
    private static final int MAX_AUDIO_LEVEL = 45;

    // Fallback IBCMListener transaction IDs from Gen5W framework (overridden by runtime reflection when present)
    private static final int FB_BCM_ON_DOOR_ALL = 2;
    private static final int FB_BCM_ON_DOOR_LOCK = 3;
    private static final int FB_BCM_ON_SEAT_HEAT = 8;
    private static final int FB_BCM_ON_SEAT_VENT = 9;
    private static final int FB_BCM_ON_PARK_BRAKE = 14;
    private static final int FB_BCM_ON_GEAR_INFO = 19;
    private static final int FB_BCM_ON_REMOTE_HEAT = 20;
    private static final int FB_BCM_ON_WINDOW_OPEN = 30;
    private static final int FB_BCM_ON_DIGITAL_KEY = 37;
    private static final int FB_BCM_ON_MODEM_INFO = 39;
    private static final int FB_BCM_ON_BATT_COND = 45;
    private static final int FB_BCM_ON_SUNROOF_OPEN = 47;
    private static final int FB_BCM_ON_CDM_CHANGED = 48;
    private static final int MAX_OUT_ARRAY_BYTES = 64 * 1024;

    static final class CommandReply {
        final boolean ok;
        final String message;

        CommandReply(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
        }
    }

    private static final class TxEntry {
        final int code;
        final Class<?> returnType;

        TxEntry(int code, Class<?> returnType) {
            this.code = code;
            this.returnType = returnType;
        }
    }

    private final Map<String, IBinder> binderCache = new HashMap<String, IBinder>();
    private final Map<String, TxEntry> txCache = new HashMap<String, TxEntry>();
    private Method mGetService;
    private ClassLoader hscanLoader;

    private volatile int lastKnownFanStep = 3;
    private volatile int lastAudioStream = 3;
    private volatile int lastKnownHeatState = -1;
    private volatile float lastHeaterKw = 0f;
    private volatile Boolean lastKnownCelsius = null;
    private volatile boolean lastRearAutoState = false;
    private volatile Integer cachedHevEvRdyFlag = null;
    private volatile long lastVrSignalsEnableMs = 0L;

    // Cached BCM & DATC listener state (updated asynchronously on change & on initial registration)
    private IBinder registeredBcmService;
    private IBinder bcmListenerToken;
    private IBinder bcmListenerBinder;
    private IBinder registeredDatcService;
    private IBinder datcListenerToken;
    private IBinder datcListenerBinder;
    private volatile int txDatcStatus = -1;
    private volatile Object datcStatusCreator;
    private volatile Method mAmbientF;
    private volatile Method mAmbientC;
    private volatile Method mAmbientStateC;
    private volatile Method mAmbientStateF;
    private volatile Method mDatcStatusTempUnit;
    private volatile int datcOutsideF10 = Integer.MIN_VALUE;
    private volatile int datcOutsideC10 = Integer.MIN_VALUE;
    private volatile int datcAmbientStateC = 0;
    private volatile int datcAmbientStateF = 0;
    private volatile int datcStatusTempUnit = 0;

    private volatile int txBcmDoorAll = -1;
    private volatile int txBcmDoorLock = -1;
    private volatile int txBcmSeatHeat = -1;
    private volatile int txBcmSeatVent = -1;
    private volatile int txBcmParkBrake = -1;
    private volatile int txBcmGearInfo = -1;
    private volatile int txBcmRemoteHeat = -1;
    private volatile int txBcmWindowOpen = -1;
    private volatile int txBcmDigitalKey = -1;
    private volatile int txBcmModemInfo = -1;
    private volatile int txBcmBattCond = -1;
    private volatile int txBcmSunroof = -1;
    private volatile int txBcmCdm = -1;
    private volatile int bcmCdmState = -1;

    private volatile int bcmUnlockFl = -1;
    private volatile int bcmUnlockFr = -1;
    private volatile int bcmUnlockRl = -1;
    private volatile int bcmUnlockRr = -1;
    private volatile int bcmOpenFl = -1;
    private volatile int bcmOpenFr = -1;
    private volatile int bcmOpenRl = -1;
    private volatile int bcmOpenRr = -1;
    private volatile int bcmTrunkOpen = -1;
    private volatile int bcmHoodOpen = -1;
    private volatile int bcmLockState = -1;
    private volatile int bcmWheelHeat = -1;
    private volatile int bcmDefogger = -1;
    private volatile int bcmSeatHeatDrv = -1;
    private volatile int bcmSeatHeatAst = -1;
    private volatile int bcmSeatHeatRl = -1;
    private volatile int bcmSeatHeatRr = -1;
    private volatile int bcmSeatVentDrv = -1;
    private volatile int bcmSeatVentAst = -1;
    private volatile int bcmParkBrake = -1;
    private volatile int bcmBattCond = -1;
    private volatile int bcmSunroofOpen = -1;
    private volatile int bcmWdwFl = -1;
    private volatile int bcmWdwFr = -1;
    private volatile int bcmWdwRl = -1;
    private volatile int bcmWdwRr = -1;

    void pollFast(Map<String, String> out) {
        // HSCAN: Speed, Moving, Steering Angle (navi_getSasAngleVal), Accel & Brake
        Float spd = callFloatArgByte(SVC_HSCAN, IFACE_HSCAN, "getCarSpeed", (byte) 0, true);
        if (spd != null && !Float.isNaN(spd) && spd >= 0f && spd < 400f) {
            out.put("speed", formatCompactFloat(spd));
            out.put("moving", spd > 0.5f ? "1" : "0");
        }
        Integer moving = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "IsCarMoving", true);
        if (moving != null && (moving == 0 || moving == 1)) {
            out.put("moving", moving == 1 ? "1" : "0");
        }
        Float sasRaw = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "navi_getSasAngleVal", true);
        if (sasRaw != null && !Float.isNaN(sasRaw) && sasRaw >= 0f && sasRaw <= 65535f
                && Math.abs(sasRaw - Math.round(sasRaw)) <= 0.05f) {
            int raw16 = Math.round(sasRaw);
            float deg = (raw16 < 0x8000 ? raw16 : raw16 - 0x10000) / 10.0f;
            out.put("steering.angle", formatCompactFloat(deg));
        } else {
            Float sasDeg = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "getSasAngleVal", true);
            if (sasDeg != null && !Float.isNaN(sasDeg) && Math.abs(sasDeg) <= 1080f) {
                out.put("steering.angle", formatCompactFloat(sasDeg));
            }
        }
        Float accel = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "getAccelPdlVal", true);
        if (accel != null && !Float.isNaN(accel) && accel >= 0f && accel <= 1000f) {
            out.put("pedal.accel", formatCompactFloat(accel));
        }
        Float brake = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "getCylPrsrVal", true);
        if (brake != null && !Float.isNaN(brake) && brake >= 0f && brake <= 10000f) {
            out.put("brake.pressure", formatCompactFloat(brake));
        }

        // System: Gear P & HEV EV_RDY (100ms ignition readiness)
        Integer gearP = callIntNoArg(SVC_SYSTEM, IFACE_SYSTEM, "getGearPStatus", false);
        if (gearP != null && (gearP == 0 || gearP == 1)) {
            out.put("gear.park", gearP == 1 ? "1" : "0");
        }
        Integer evReady = pollHevEvReady();
        if (evReady != null && evReady >= 0 && evReady != 0xFFFF) {
            out.put("hv.ready", String.valueOf(evReady));
        }

        // Audio: Volume & Mute
        Integer curStream = callIntNoArg(SVC_AUDIO, IFACE_AUDIO, "getCurStreamType", false);
        int vol = -1;
        int usedStream = -1;
        int[] tryStreams = (curStream != null && curStream >= 0)
                ? new int[] { curStream, 3, 14 }
                : new int[] { 3, 14 };
        for (int s : tryStreams) {
            Integer v = callVolumeGet(s);
            if (v != null && v >= 0 && v <= MAX_AUDIO_LEVEL) {
                vol = v;
                usedStream = s;
                break;
            }
        }
        if (vol >= 0) {
            lastAudioStream = usedStream;
            out.put("audio.volume", String.valueOf(vol));
        }
        Integer muted = callIntNoArg(SVC_AUDIO, IFACE_AUDIO, "getUserMuteState", false);
        if (muted != null) {
            out.put("audio.muted", muted != 0 ? "1" : "0");
        }
    }

    void pollExtended(Map<String, String> out) {
        ensureBcmListenerRegistered();
        ensureDatcListenerRegistered();

        // HSCAN: EV Range, SoC, Odometer, Charging, Tyres, Consumption
        Integer rangeKm = callIntArgByte(SVC_HSCAN, IFACE_HSCAN, "ableDriveDistance", (byte) 0, true);
        if (rangeKm != null && rangeKm >= 0 && rangeKm < 2500) {
            out.put("hv.range", String.valueOf(rangeKm));
        }
        Float soc = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "remainBatteryPercentage", true);
        if (soc != null && !Float.isNaN(soc) && soc >= 0f && soc <= 100f) {
            out.put("hv.soc", formatCompactFloat(soc));
        }
        Float odo = callFloatArgByte(SVC_HSCAN, IFACE_HSCAN, "getOdometer", (byte) 0, true);
        if (odo != null && !Float.isNaN(odo) && odo > 0f) {
            out.put("odometer", formatCompactFloat(odo));
        }
        Integer aux = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getBatSoc", true);
        if (aux != null && aux > 0 && aux <= 100) {
            out.put("battery12v.soc", String.valueOf(aux));
        }
        Integer chg = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getChargingStatus", true);
        if (chg != null && chg >= 0) {
            out.put("charging", String.valueOf(chg));
        }
        Integer chgMin = callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getRemainChargingTime", true);
        if (chgMin != null && chgMin >= 0 && chgMin < 10000) {
            out.put("charging.remain_min", String.valueOf(chgMin));
        }
        putByteFlag(out, "tyre.fl_status", callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getFrontLeftTirePressureStatus", true));
        putByteFlag(out, "tyre.fr_status", callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getFrontRightTirePressureStatus", true));
        putByteFlag(out, "tyre.rl_status", callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getRearLeftTirePressureStatus", true));
        putByteFlag(out, "tyre.rr_status", callIntNoArg(SVC_HSCAN, IFACE_HSCAN, "getRearRightTirePressureStatus", true));

        Float drvKw = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "driveLineEleConsum", true);
        if (drvKw != null && !Float.isNaN(drvKw) && drvKw >= -300f && drvKw <= 600f) {
            out.put("energy.drivetrain_kw", formatCompactFloat(drvKw));
        }
        Float acKw = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "airConEleConsum", true);
        if (acKw != null && !Float.isNaN(acKw) && acKw >= 0f && acKw <= 50000f) {
            out.put("energy.climate_kw", acKw > 100f ? String.format(Locale.US, "%.2f", acKw / 1000f) : formatCompactFloat(acKw));
        }
        Float elecW = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "electricConsum", true);
        if (elecW != null && !Float.isNaN(elecW) && elecW >= 0f && elecW <= 50000f) {
            out.put("energy.electronics_w", String.valueOf(Math.round(elecW)));
        }
        Float heatKw = callNumericNoArgAsFloat(SVC_HSCAN, IFACE_HSCAN, "heaterEleConsum", true);
        if (heatKw != null && !Float.isNaN(heatKw) && heatKw >= 0f && heatKw <= 50000f) {
            float normHeatKw = heatKw > 100f ? (heatKw / 1000f) : heatKw;
            lastHeaterKw = normHeatKw;
            out.put("energy.care_kw", heatKw > 100f ? String.format(Locale.US, "%.2f", normHeatKw) : formatCompactFloat(heatKw));
        }

        Integer guide = callIntNoArg(SVC_PGS, IFACE_PGS, "getRvmGuideLineState", false);
        if (guide != null && (guide == 1 || guide == 2)) {
            out.put("guide_lines", guide == 2 ? "1" : "0");
        }

        // DATC: Climate state & ambient temperature
        pollDatcClimate(out);

        // BCM & USM: Doors, Locks, Trunk/Hatch, Hood, Seats, Windows, Sunroof, Conditioning indicator, EPB
        pollBodyAndSeats(out);
    }

    private void pollDatcClimate(Map<String, String> out) {
        // On Gen5W CAN-DB Gen1+, getTemperatureUnit() stays 0; getVRClimateStatus(13) reads mDatcBypassStatusCache (1=C, 2=F).
        Float fUnit = callDatcStatus(13);
        int unitCode = (fUnit != null && !Float.isNaN(fUnit)) ? Math.round(fUnit) : 0;
        if (unitCode != 1 && unitCode != 2 && (datcStatusTempUnit == 1 || datcStatusTempUnit == 2)) {
            unitCode = datcStatusTempUnit;
        }
        if (unitCode != 1 && unitCode != 2) {
            Integer legacyUnit = callIntNoArg(SVC_DATC, IFACE_DATC, "getTemperatureUnit", false);
            if (legacyUnit != null && (legacyUnit == 1 || legacyUnit == 2)) {
                unitCode = legacyUnit.intValue();
            }
        }
        Float fDrvPre = callDatcStatus(2);
        boolean celsius;
        if (unitCode == 1 || unitCode == 2) {
            celsius = (unitCode == 1);
            lastKnownCelsius = Boolean.valueOf(celsius);
        } else if (fDrvPre != null && !Float.isNaN(fDrvPre) && fDrvPre >= 14f && fDrvPre <= 36f) {
            celsius = true;
            lastKnownCelsius = Boolean.TRUE;
        } else if (fDrvPre != null && !Float.isNaN(fDrvPre) && fDrvPre >= 56f && fDrvPre <= 95f) {
            celsius = false;
            lastKnownCelsius = Boolean.FALSE;
        } else if (lastKnownCelsius != null) {
            celsius = lastKnownCelsius.booleanValue();
        } else {
            // Infer from ambient state if available: US cars report stateC == 255, Celsius cars report stateF == 255 or -40F
            int stC = datcAmbientStateC;
            int stF = datcAmbientStateF;
            celsius = (stC != 255 && stC != -1 && datcOutsideC10 != Integer.MIN_VALUE)
                    && (stF == 255 || stF == -1 || (datcOutsideF10 == -400 && datcOutsideC10 != -400));
        }
        out.put("climate.temp_unit", celsius ? "C" : "F");

        int f10 = datcOutsideF10;
        int c10 = datcOutsideC10;
        if (f10 != Integer.MIN_VALUE || c10 != Integer.MIN_VALUE) {
            float rawC = (c10 == Integer.MIN_VALUE) ? Float.NaN : (c10 / 10f);
            float rawF = (f10 == Integer.MIN_VALUE) ? Float.NaN : (f10 / 10f);
            TemperatureNormalizer.resolveAmbientPair(
                    rawC,
                    c10 == Integer.MIN_VALUE ? 255 : datcAmbientStateC,
                    rawF,
                    f10 == Integer.MIN_VALUE ? 255 : datcAmbientStateF,
                    celsius ? "C" : "F",
                    out);
        }

        Integer drvOnly = callIntNoArg(SVC_DATC, IFACE_DATC, "getDriverOnlyValue", false);
        if (drvOnly != null && drvOnly >= 0) {
            out.put("climate.driver_only", drvOnly == 1 ? "1" : "0");
        }

        Float fOn = callDatcStatus(12);
        boolean isOn = false;
        if (fOn != null && !Float.isNaN(fOn) && fOn >= 0f) {
            isOn = (Math.round(fOn) == 1);
            out.put("climate.on", isOn ? "1" : "0");
        }

        // Gen5W supports 3-step Auto Climate (1=Low, 2=Med, 3=High; 0=Off).
        Float fAuto = callDatcStatus(8);
        if (fAuto != null && !Float.isNaN(fAuto) && fAuto >= 0f) {
            int autoLvl = Math.round(fAuto);
            if (autoLvl >= 0 && autoLvl <= 3) {
                out.put("climate.auto", autoLvl >= 1 ? "1" : "0");
                out.put("climate.auto_level", String.valueOf(autoLvl));
            }
        }

        putDatcBool(out, "climate.air_clean", callDatcStatus(7), 1);
        Float fAc = callDatcStatus(9);
        putDatcBool(out, "climate.ac", fAc, 1);
        putDatcBool(out, "climate.front_defrost", callDatcStatus(10), 1);
        putDatcBool(out, "climate.recirc", callDatcStatus(11), 1);
        Float fSync = callDatcStatus(22);
        boolean synced = false;
        if (fSync != null && !Float.isNaN(fSync) && fSync >= 0f) {
            synced = (Math.round(fSync) == 1);
            out.put("climate.sync", synced ? "1" : "0");
        }

        // Cabin Heat status: query DATC status/info first, then fall back to lastKnownHeatState or PTC draw.
        Float fHeat = callDatcStatus(48);
        Integer heatInfo = (fHeat != null && !Float.isNaN(fHeat) && (Math.round(fHeat) == 0 || Math.round(fHeat) == 1))
                ? Integer.valueOf(Math.round(fHeat))
                : callIntArgInt(SVC_DATC, IFACE_DATC, "getClimateInfo", 24, false);
        if (heatInfo != null && (heatInfo == 0 || heatInfo == 1)) {
            lastKnownHeatState = heatInfo.intValue();
            out.put("climate.heat", heatInfo == 1 ? "1" : "0");
        } else if (lastKnownHeatState >= 0) {
            out.put("climate.heat", (isOn && lastKnownHeatState == 1) ? "1" : "0");
        } else {
            out.put("climate.heat", (isOn && lastHeaterKw > 0.25f) ? "1" : "0");
        }

        // Advanced DATC states via getClimateInfo(int) & getVRClimateStatus(int)
        Integer smartVent = callIntArgInt(SVC_DATC, IFACE_DATC, "getClimateInfo", 26, false);
        if (smartVent != null && (smartVent == 0 || smartVent == 1)) {
            out.put("climate.smart_vent", smartVent == 1 ? "1" : "0");
        }
        Integer autoDefog = callIntArgInt(SVC_DATC, IFACE_DATC, "getClimateInfo", 25, false);
        if (autoDefog != null && (autoDefog == 0 || autoDefog == 1)) {
            out.put("climate.auto_defog", autoDefog == 1 ? "1" : "0");
        }
        Integer afterBlow = callIntArgInt(SVC_DATC, IFACE_DATC, "getClimateInfo", 23, false);
        if (afterBlow != null && (afterBlow == 0 || afterBlow == 1)) {
            out.put("climate.after_blow", afterBlow == 1 ? "1" : "0");
        }
        Integer rearLock = callIntArgInt(SVC_DATC, IFACE_DATC, "getClimateInfo", 27, false);
        if (rearLock != null && (rearLock == 0 || rearLock == 1)) {
            out.put("climate.rear_lock", rearLock == 1 ? "1" : "0");
        }

        // getVRClimateStatus(43) returns getRearOffDisp() (1 = Rear OFF, 0 = Rear ON).
        Float fRearOff = callDatcStatus(43);
        if (fRearOff != null && !Float.isNaN(fRearOff)) {
            int rOff = Math.round(fRearOff);
            if (rOff == 0 || rOff == 1) {
                out.put("climate.rear_on", rOff == 0 ? "1" : "0");
            }
        }

        // DatcBlowerLevel: 0=OFF, 1=LEVEL_0, 2..9=Fan 1..8, 10=LEVEL_AUTO, 15=INVALID.
        Float fFan = callDatcStatus(5);
        if (fFan != null && !Float.isNaN(fFan) && fFan >= 0f) {
            int raw = Math.round(fFan);
            if (raw >= 0 && raw <= 9) {
                int step = raw <= 1 ? 0 : (raw - 1);
                if (step > 0) {
                    lastKnownFanStep = step;
                }
                out.put("climate.fan_speed", String.valueOf(isOn ? step : 0));
            } else if (raw == 10) {
                out.put("climate.fan_speed", String.valueOf(isOn ? lastKnownFanStep : 0));
            }
        }

        Float fMode = callDatcStatus(6);
        if (fMode != null && !Float.isNaN(fMode) && fMode >= 0f) {
            out.put("climate.vent_mode", String.valueOf(Math.round(fMode)));
        }

        Float fDrv = callDatcStatus(2);
        String driverStr = null;
        if (fDrv != null && !Float.isNaN(fDrv) && fDrv >= 10f && fDrv <= 95f) {
            driverStr = celsius ? formatHalf(fDrv) : String.valueOf(Math.round(fDrv));
            out.put("climate.driver_temp", driverStr);
        } else if (fDrv != null && Math.round(fDrv) == 1) {
            driverStr = "LO";
            out.put("climate.driver_temp", "LO");
        } else if (fDrv != null && Math.round(fDrv) == 254) {
            driverStr = "HI";
            out.put("climate.driver_temp", "HI");
        }

        if (synced && driverStr != null) {
            out.put("climate.passenger_temp", driverStr);
        } else if (drvOnly != null && drvOnly == 1) {
            out.put("climate.passenger_temp", "OFF");
        } else if (celsius) {
            Float fPassC = callDatcStatus(29);
            if (fPassC != null && !Float.isNaN(fPassC)) {
                int raw = Math.round(fPassC);
                if (raw == 1) {
                    out.put("climate.passenger_temp", "LO");
                } else if (raw == 254) {
                    out.put("climate.passenger_temp", "HI");
                } else if (fPassC >= 4f && fPassC <= 38f) {
                    double passC = fPassC / 2.0 + 14.0;
                    out.put("climate.passenger_temp", formatHalf((float) passC));
                }
            }
        } else {
            Float fPassF = callDatcStatus(31);
            if (fPassF != null && !Float.isNaN(fPassF)) {
                int raw = Math.round(fPassF);
                if (raw == 1) {
                    out.put("climate.passenger_temp", "LO");
                } else if (raw == 254) {
                    out.put("climate.passenger_temp", "HI");
                } else if (raw >= 58 && raw <= 95) {
                    out.put("climate.passenger_temp", String.valueOf(raw));
                } else if (raw >= 6 && raw <= 34) {
                    out.put("climate.passenger_temp", String.valueOf(raw + 56));
                }
            } else {
                Float fPassC = callDatcStatus(29);
                if (fPassC != null && !Float.isNaN(fPassC) && fPassC >= 4f && fPassC <= 38f) {
                    out.put("climate.passenger_temp", String.valueOf(Math.round(fPassC) + 56));
                }
            }
        }
    }

    private void pollBodyAndSeats(Map<String, String> out) {
        Integer drvOpt = callIntNoArg(SVC_BCM, IFACE_BCM, "getDrvSeatHeatOption", false);
        Integer drvSeat = callIntNoArg(SVC_BCM, IFACE_BCM, "getDrvSeatHeatState", false);
        if (drvSeat == null || drvSeat < 0) drvSeat = callIntNoArg(SVC_USM, IFACE_USM, "getFrontSeatHeat_DrvState", false);
        decodeSeatState(out, "driver", drvSeat, true, drvOpt != null && drvOpt == 1);
        if ((drvSeat == null || drvSeat < 0) && bcmSeatHeatDrv >= 0) {
            out.put("seat_heat.driver", String.valueOf(normalizeLegacyHeaterCallback(bcmSeatHeatDrv)));
        }
        if (bcmSeatVentDrv >= 0 && (drvSeat == null || drvSeat == 2)) {
            out.put("seat_vent.driver", String.valueOf(normalizeVentValue(bcmSeatVentDrv)));
        }

        Integer astOpt = callIntNoArg(SVC_BCM, IFACE_BCM, "getAstSeatHeatOption", false);
        Integer astSeat = callIntNoArg(SVC_BCM, IFACE_BCM, "getAstSeatHeatState", false);
        if (astSeat == null || astSeat < 0) astSeat = callIntNoArg(SVC_USM, IFACE_USM, "getFrontSeatHeat_AstState", false);
        decodeSeatState(out, "passenger", astSeat, true, astOpt != null && astOpt == 1);
        if ((astSeat == null || astSeat < 0) && bcmSeatHeatAst >= 0) {
            out.put("seat_heat.passenger", String.valueOf(normalizeLegacyHeaterCallback(bcmSeatHeatAst)));
        }
        if (bcmSeatVentAst >= 0 && (astSeat == null || astSeat == 2)) {
            out.put("seat_vent.passenger", String.valueOf(normalizeVentValue(bcmSeatVentAst)));
        }

        Integer rlOpt = callIntNoArg(SVC_BCM, IFACE_BCM, "getRLSeatHeatOption", false);
        Integer rlSeat = callIntNoArg(SVC_BCM, IFACE_BCM, "getRLSeatHeatState", false);
        decodeSeatState(out, "rear_left", rlSeat, false, rlOpt != null && rlOpt == 1);
        if ((rlSeat == null || rlSeat < 0) && bcmSeatHeatRl >= 0) {
            out.put("seat_heat.rear_left", String.valueOf(normalizeLegacyHeaterCallback(bcmSeatHeatRl)));
        }

        Integer rrOpt = callIntNoArg(SVC_BCM, IFACE_BCM, "getRRSeatHeatOption", false);
        Integer rrSeat = callIntNoArg(SVC_BCM, IFACE_BCM, "getRRSeatHeatState", false);
        decodeSeatState(out, "rear_right", rrSeat, false, rrOpt != null && rrOpt == 1);
        if ((rrSeat == null || rrSeat < 0) && bcmSeatHeatRr >= 0) {
            out.put("seat_heat.rear_right", String.valueOf(normalizeLegacyHeaterCallback(bcmSeatHeatRr)));
        }

        Integer wFl = callIntNoArg(SVC_BCM, IFACE_BCM, "getDrvWdwOpenState", false);
        Integer wFr = callIntNoArg(SVC_BCM, IFACE_BCM, "getAstWdwOpenState", false);
        Integer wRl = callIntNoArg(SVC_BCM, IFACE_BCM, "getRLWdwOpenState", false);
        Integer wRr = callIntNoArg(SVC_BCM, IFACE_BCM, "getRRWdwOpenState", false);
        putWindowFlag(out, "window.fl", (wFl != null && wFl >= 0) ? wFl : (bcmWdwFl >= 0 ? bcmWdwFl : null));
        putWindowFlag(out, "window.fr", (wFr != null && wFr >= 0) ? wFr : (bcmWdwFr >= 0 ? bcmWdwFr : null));
        putWindowFlag(out, "window.rl", (wRl != null && wRl >= 0) ? wRl : (bcmWdwRl >= 0 ? bcmWdwRl : null));
        putWindowFlag(out, "window.rr", (wRr != null && wRr >= 0) ? wRr : (bcmWdwRr >= 0 ? bcmWdwRr : null));

        Integer sr = callIntNoArg(SVC_BCM, IFACE_BCM, "getSunRoofOpnSta", false);
        if (sr != null && (sr == 0 || sr == 1)) {
            out.put("sunroof.open", sr == 1 ? "1" : "0");
        } else if (bcmSunroofOpen == 0 || bcmSunroofOpen == 1) {
            out.put("sunroof.open", bcmSunroofOpen == 1 ? "1" : "0");
        }

        // Doors, Trunk/Hatch, Hood & Locks from IBCMListener
        putOpenFlag(out, "door.fl", bcmOpenFl);
        putOpenFlag(out, "door.fr", bcmOpenFr);
        putOpenFlag(out, "door.rl", bcmOpenRl);
        putOpenFlag(out, "door.rr", bcmOpenRr);
        if (bcmOpenFl >= 0 || bcmOpenFr >= 0 || bcmOpenRl >= 0 || bcmOpenRr >= 0) {
            boolean anyOpen = (bcmOpenFl == 1) || (bcmOpenFr == 1) || (bcmOpenRl == 1) || (bcmOpenRr == 1);
            out.put("door.any_open", anyOpen ? "1" : "0");
        }
        putLockFlag(out, "door.lock.fl", bcmUnlockFl);
        putLockFlag(out, "door.lock.fr", bcmUnlockFr);
        putLockFlag(out, "door.lock.rl", bcmUnlockRl);
        putLockFlag(out, "door.lock.rr", bcmUnlockRr);
        if (bcmLockState == 0 || bcmLockState == 1) {
            out.put("door.lock_state", bcmLockState == 0 ? "1" : "0");
        } else if (bcmUnlockFl == 0 || bcmUnlockFl == 1) {
            boolean anyUnlocked = (bcmUnlockFl == 1) || (bcmUnlockFr == 1) || (bcmUnlockRl == 1) || (bcmUnlockRr == 1);
            out.put("door.lock_state", anyUnlocked ? "0" : "1");
        }

        // getHsOpnClsState() is EV Charge Door (CDM: 1=ON/Open, 2=OFF/Closed, 3=Invalid), NOT Power Tailgate!
        Integer cdmState = callIntNoArg(SVC_BCM, IFACE_BCM, "getHsOpnClsState", false);
        int effCdm = (cdmState != null && (cdmState == 1 || cdmState == 2)) ? cdmState.intValue() : bcmCdmState;
        if (effCdm == 1 || effCdm == 2) {
            out.put("charge_door.open", effCdm == 1 ? "1" : "0");
        }
        if (bcmTrunkOpen == 0 || bcmTrunkOpen == 1) {
            out.put("trunk.open", bcmTrunkOpen == 1 ? "1" : "0");
            out.put("trunk.ptg_state", bcmTrunkOpen == 1 ? "1" : "0");
        }
        putOpenFlag(out, "hood.open", bcmHoodOpen);

        if (bcmWheelHeat == 0 || bcmWheelHeat == 1) {
            out.put("wheel_heat", bcmWheelHeat == 1 ? "1" : "0");
        }
        if (bcmDefogger == 0 || bcmDefogger == 1) {
            out.put("defogger", bcmDefogger == 1 ? "1" : "0");
        }

        Integer cond = callIntNoArg(SVC_BCM, IFACE_BCM, "getBattCondIndSta", false);
        if ((cond == null || cond < 0) && bcmBattCond >= 0) cond = bcmBattCond;
        if (cond != null && (cond == 0 || cond == 1)) {
            out.put("hv.conditioning_active", cond == 1 ? "1" : "0");
        }

        // Gen5W EPB status on ISystemService.getEPBStatus(): 2 = engaged, 1 = released.
        Integer sysEpb = callIntNoArg(SVC_SYSTEM, IFACE_SYSTEM, "getEPBStatus", false);
        if (sysEpb != null && (sysEpb == 1 || sysEpb == 2)) {
            out.put("parking_brake", sysEpb == 2 ? "1" : "0");
        } else if (bcmParkBrake == 1 || bcmParkBrake == 2) {
            out.put("parking_brake", bcmParkBrake == 2 ? "1" : "0");
        }
    }

    CommandReply executeCommand(String rawCmd, String rawArg, Map<String, String> latest) {
        String cmd = rawCmd == null ? "" : rawCmd.trim().toLowerCase(Locale.US);
        String arg = rawArg == null ? "" : rawArg.trim();
        int colon = cmd.indexOf(':');
        if (colon > 0) {
            if (arg.isEmpty()) {
                arg = cmd.substring(colon + 1).trim();
            }
            cmd = cmd.substring(0, colon).trim();
        }
        Map<String, String> snap = latest != null ? latest : new HashMap<String, String>();
        try {
            return executeCommandInternal(cmd, arg, snap);
        } catch (Throwable t) {
            return new CommandReply(false, "Exception: " + t.getMessage());
        }
    }

    private CommandReply executeCommandInternal(String cmd, String arg, Map<String, String> snap) {

        // --- Doors & Locks ---
        if ("door_lock".equals(cmd) || "door_unlock".equals(cmd) || "door_lock_toggle".equals(cmd)) {
            boolean wantLock = "door_lock".equals(cmd)
                    || ("door_lock_toggle".equals(cmd) && !"1".equals(snap.get("door.lock_state")));
            boolean ok = callBoolWrite1(SVC_BCM, IFACE_BCM, "lockDoor", wantLock);
            if (ok) {
                bcmLockState = wantLock ? 0 : 1;
                callVoidNoArg(SVC_BCM, IFACE_BCM, "getDoorLockStatus");
            }
            return new CommandReply(ok, ok ? (wantLock ? "Doors LOCKED" : "Doors UNLOCKED") : "BCM lockDoor unavailable");
        }
        if ("door_open_all".equals(cmd) || "door_close_all".equals(cmd)) {
            boolean open = "door_open_all".equals(cmd);
            boolean ok = callIntWrite1(SVC_USM, IFACE_USM, "setDoor_AllPowerDoorOpen", open ? 1 : 2);
            return new CommandReply(ok, ok ? (open ? "Power Doors Opening" : "Power Doors Closing") : "USM Power Doors unavailable");
        }

        // --- Trunk / Hatch / Power Tailgate ---
        if ("trunk_open".equals(cmd) || "trunk_close".equals(cmd) || "trunk_toggle".equals(cmd)) {
            boolean currentlyOpen = "1".equals(snap.get("trunk.open")) || bcmTrunkOpen == 1;
            boolean wantOpen = "trunk_open".equals(cmd) || ("trunk_toggle".equals(cmd) && !currentlyOpen);
            if (!wantOpen && "0".equals(snap.get("gear.park"))) {
                return new CommandReply(false, "Refused: Put car in PARK for Trunk/Hatch");
            }
            if (wantOpen && "0".equals(snap.get("gear.park"))) {
                return new CommandReply(false, "Refused: Car must be in PARK to open Trunk");
            }
            ensureVrSignalsEnabled();
            // Ioniq 5 Power Tailgate (PTG): set_PTGCNTL_Cmd_AVN uses 1=OPEN, 0=CLOSE; TrunkTlgt uses 1=OPEN, 2=CLOSE.
            boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_PTGCNTL_Cmd_AVN", wantOpen ? 1 : 0);
            if (!ok) {
                ok = callIntWrite1(SVC_VR, IFACE_VR, "set_TrunkTlgt_Cmd_AVN", wantOpen ? 1 : 2);
            }
            if (!ok) {
                ok = callIntWrite1(SVC_USM, IFACE_USM, "setDoor_PowerTrunk", wantOpen ? 1 : 0);
            }
            return new CommandReply(ok, ok
                    ? (wantOpen ? "Trunk / Hatch OPEN requested" : "Trunk / Hatch CLOSE requested")
                    : "Trunk / PTG command unavailable");
        }
        if ("trunk_height_set".equals(cmd)) {
            int lvl = Math.max(0, Math.min(4, parseIntSafe(arg, 4)));
            boolean ok = callIntWrite1(SVC_USM, IFACE_USM, "setDoor_TrunkOpeningHeight", lvl);
            return new CommandReply(ok, ok ? ("Trunk Height -> Level " + lvl) : "USM Trunk Height unavailable");
        }
        if ("trunk_speed_set".equals(cmd)) {
            int spd = Math.max(0, Math.min(2, parseIntSafe(arg, 1)));
            boolean ok = callIntWrite1(SVC_USM, IFACE_USM, "setDoor_PowerTrunkSpeed", spd);
            return new CommandReply(ok, ok ? ("Trunk Speed -> " + (spd == 1 ? "Normal" : "Fast")) : "USM Speed unavailable");
        }

        // --- Power Windows, Sunroof & Sunshade ---
        // Gen5W C_*WindowCmdAVN & BCM requestWindowAndIMSCmd codes (hardware-verified on Ioniq 5):
        //   1 = Inch UP (~1"), 2 = Inch DOWN (~1"), 3 = 25% Vent, 4 = 50% Half-Open, 5 = FULL UP (Close), 6 = FULL DOWN (Open).
        // Gen5W set_Window_ALL_UPDOWN codes:
        //   0 = ALL 50% Half-Open, 1 = ALL FULL DOWN (Open), 2 = ALL 25% Vent, 4 = ALL FULL UP (Close).
        if ("window_all_open".equals(cmd)) {
            return setAllWindows(1, 6, "All Windows Full DOWN");
        }
        if ("window_all_close".equals(cmd)) {
            return setAllWindows(4, 5, "All Windows Full UP");
        }
        if ("window_all_half".equals(cmd)) {
            return setAllWindows(0, 4, "All Windows 50% Half-Open");
        }
        if ("window_all_vent".equals(cmd)) {
            ensureVrSignalsEnabled();
            boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_Window_ALL_UPDOWN", 2);
            if (ok) {
                sleepQuietly(240L);
                callIntWrite1(SVC_VR, IFACE_VR, "set_RLWDW_Cmd_AVN", 3);
                sleepQuietly(240L);
                callIntWrite1(SVC_VR, IFACE_VR, "set_RRWDW_Cmd_AVN", 3);
            } else {
                ok = callIntWrite5(SVC_BCM, IFACE_BCM, "requestWindowAndIMSCmd", 3, 3, 7, 3, 3);
            }
            return new CommandReply(ok, ok ? "All Windows Vent 25%" : "Window control unavailable");
        }
        if ("window_fl_open".equals(cmd)) return setSingleWindow("set_DrvWDW_Cmd_AVN", 6, 6, 0, 0, 0, "Driver Window Full DOWN");
        if ("window_fl_close".equals(cmd)) return setSingleWindow("set_DrvWDW_Cmd_AVN", 5, 5, 0, 0, 0, "Driver Window Full UP");
        if ("window_fl_half".equals(cmd)) return setSingleWindow("set_DrvWDW_Cmd_AVN", 4, 4, 0, 0, 0, "Driver Window 50%");
        if ("window_fl_vent".equals(cmd)) return setSingleWindow("set_DrvWDW_Cmd_AVN", 3, 3, 0, 0, 0, "Driver Window Vent 25%");
        if ("window_fl_inch_up".equals(cmd)) return setSingleWindow("set_DrvWDW_Cmd_AVN", 1, 1, 0, 0, 0, "Driver Window Inch UP");
        if ("window_fl_inch_down".equals(cmd)) return setSingleWindow("set_DrvWDW_Cmd_AVN", 2, 2, 0, 0, 0, "Driver Window Inch DOWN");

        if ("window_fr_open".equals(cmd)) return setSingleWindow("set_AstWDW_Cmd_AVN", 6, 0, 6, 0, 0, "Pass Window Full DOWN");
        if ("window_fr_close".equals(cmd)) return setSingleWindow("set_AstWDW_Cmd_AVN", 5, 0, 5, 0, 0, "Pass Window Full UP");
        if ("window_fr_half".equals(cmd)) return setSingleWindow("set_AstWDW_Cmd_AVN", 4, 0, 4, 0, 0, "Pass Window 50%");
        if ("window_fr_vent".equals(cmd)) return setSingleWindow("set_AstWDW_Cmd_AVN", 3, 0, 3, 0, 0, "Pass Window Vent 25%");
        if ("window_fr_inch_up".equals(cmd)) return setSingleWindow("set_AstWDW_Cmd_AVN", 1, 0, 1, 0, 0, "Pass Window Inch UP");
        if ("window_fr_inch_down".equals(cmd)) return setSingleWindow("set_AstWDW_Cmd_AVN", 2, 0, 2, 0, 0, "Pass Window Inch DOWN");

        if ("window_rl_open".equals(cmd)) return setSingleWindow("set_RLWDW_Cmd_AVN", 6, 0, 0, 6, 0, "Rear-L Window Full DOWN");
        if ("window_rl_close".equals(cmd)) return setSingleWindow("set_RLWDW_Cmd_AVN", 5, 0, 0, 5, 0, "Rear-L Window Full UP");
        if ("window_rl_half".equals(cmd)) return setSingleWindow("set_RLWDW_Cmd_AVN", 4, 0, 0, 4, 0, "Rear-L Window 50%");
        if ("window_rl_vent".equals(cmd)) return setSingleWindow("set_RLWDW_Cmd_AVN", 3, 0, 0, 3, 0, "Rear-L Window Vent 25%");
        if ("window_rl_inch_up".equals(cmd)) return setSingleWindow("set_RLWDW_Cmd_AVN", 1, 0, 0, 1, 0, "Rear-L Window Inch UP");
        if ("window_rl_inch_down".equals(cmd)) return setSingleWindow("set_RLWDW_Cmd_AVN", 2, 0, 0, 2, 0, "Rear-L Window Inch DOWN");

        if ("window_rr_open".equals(cmd)) return setSingleWindow("set_RRWDW_Cmd_AVN", 6, 0, 0, 0, 6, "Rear-R Window Full DOWN");
        if ("window_rr_close".equals(cmd)) return setSingleWindow("set_RRWDW_Cmd_AVN", 5, 0, 0, 0, 5, "Rear-R Window Full UP");
        if ("window_rr_half".equals(cmd)) return setSingleWindow("set_RRWDW_Cmd_AVN", 4, 0, 0, 0, 4, "Rear-R Window 50%");
        if ("window_rr_vent".equals(cmd)) return setSingleWindow("set_RRWDW_Cmd_AVN", 3, 0, 0, 0, 3, "Rear-R Window Vent 25%");
        if ("window_rr_inch_up".equals(cmd)) return setSingleWindow("set_RRWDW_Cmd_AVN", 1, 0, 0, 0, 1, "Rear-R Window Inch UP");
        if ("window_rr_inch_down".equals(cmd)) return setSingleWindow("set_RRWDW_Cmd_AVN", 2, 0, 0, 0, 2, "Rear-R Window Inch DOWN");

        if ("sunroof_open".equals(cmd) || "sunroof_close".equals(cmd)) {
            ensureVrSignalsEnabled();
            Integer srOpt = callIntNoArg(SVC_VR, IFACE_VR, "get_VR_Sunroof_Option", false);
            // OEM HKMC_VoiceHandler (as.smali): 1 = OPEN, 2 = CLOSE on set_Glass_Cmd_AVN (panoramic) and set_Sunroof_Cmd_AVN (single).
            int val = "sunroof_open".equals(cmd) ? 1 : 2;
            boolean okGlass = callIntWrite1(SVC_VR, IFACE_VR, "set_Glass_Cmd_AVN", val);
            boolean okSr = callIntWrite1(SVC_VR, IFACE_VR, "set_Sunroof_Cmd_AVN", val);
            boolean ok = okGlass || okSr;
            String optNote = (srOpt != null && srOpt == 0)
                    ? " (MICOM SunroofOpt=0; Vision Roof has fixed glass)"
                    : "";
            return new CommandReply(ok, ok
                    ? ("Sunroof " + (val == 1 ? "OPEN" : "CLOSE") + " sent" + optNote)
                    : "Sunroof command unavailable");
        }
        if ("sunshade_open".equals(cmd) || "sunshade_close".equals(cmd)) {
            ensureVrSignalsEnabled();
            Integer srOpt = callIntNoArg(SVC_VR, IFACE_VR, "get_VR_Sunroof_Option", false);
            // OEM HKMC_VoiceHandler (as.smali & ay.smali): set_Blind_Cmd_AVN uses 1 = OPEN, 2 = CLOSE after All_Sunroof_Req(1).
            int val = "sunshade_open".equals(cmd) ? 1 : 2;
            boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_Blind_Cmd_AVN", val);
            String optNote = (srOpt != null && srOpt == 0)
                    ? " (MICOM SunroofOpt=0; Vision Roof shade may be switch-only)"
                    : "";
            return new CommandReply(ok, ok
                    ? ("Sunshade " + (val == 1 ? "OPEN" : "CLOSE") + " sent" + optNote)
                    : "Sunshade command unavailable");
        }

        // --- Climate & Explicit Set AC Controls ---
        if ("climate_toggle".equals(cmd) || "climate_on".equals(cmd) || "climate_off".equals(cmd)) {
            boolean wantOff = "climate_off".equals(cmd)
                    || ("climate_toggle".equals(cmd) && "1".equals(snap.get("climate.on")));
            int fanRaw = wantOff ? 0 : (Math.max(1, Math.min(8, lastKnownFanStep)) + 1);
            boolean ok = wantOff
                    ? (callDatcSetFront(51, 0) || callDatcSetVr(5, 0, 0))
                    : callDatcSetVr(5, fanRaw, 0);
            return new CommandReply(ok, ok ? (wantOff ? "Climate OFF" : "Climate ON") : "DATC unavailable");
        }
        if ("climate_auto_toggle".equals(cmd) || "climate_auto_on".equals(cmd) || "climate_auto_off".equals(cmd) || "climate_auto_set".equals(cmd)) {
            int level = "climate_auto_off".equals(cmd) ? 0
                    : ("climate_auto_on".equals(cmd) ? 1
                    : ("climate_auto_set".equals(cmd) ? Math.max(0, Math.min(3, parseIntSafe(arg, 1)))
                    : ("1".equals(snap.get("climate.auto")) ? 0 : 1)));
            boolean ok = callDatcSetVr(8, level, 0) || callDatcSetFront(8, level);
            return new CommandReply(ok, ok ? (level == 0 ? "Climate AUTO OFF" : ("Climate AUTO ON (Lvl " + level + ")")) : "DATC unavailable");
        }
        if ("climate_ac_toggle".equals(cmd) || "climate_ac_on".equals(cmd) || "climate_ac_off".equals(cmd)) {
            boolean next = "climate_ac_on".equals(cmd)
                    || ("climate_ac_toggle".equals(cmd) && !"1".equals(snap.get("climate.ac")));
            boolean ok = callDatcSetVr(9, next ? 1 : 0, 0) || callDatcSetFront(9, next ? 1 : 0);
            return new CommandReply(ok, ok ? ("A/C " + (next ? "ON" : "OFF")) : "DATC unavailable");
        }
        if ("climate_heat_toggle".equals(cmd) || "climate_heat_on".equals(cmd) || "climate_heat_off".equals(cmd)) {
            boolean curHeat = "1".equals(snap.get("climate.heat")) || lastKnownHeatState == 1;
            boolean next = "climate_heat_on".equals(cmd)
                    || ("climate_heat_toggle".equals(cmd) && !curHeat);
            boolean ok = callDatcSetFront(48, next ? 1 : 0);
            if (ok) lastKnownHeatState = next ? 1 : 0;
            return new CommandReply(ok, ok ? ("Cabin HEAT " + (next ? "ON" : "OFF")) : "DATC Heat unavailable");
        }
        if ("climate_max_ac".equals(cmd)) {
            boolean celsius = isCelsius(snap);
            boolean ok = callDatcSetVr(9, 1, 0);
            callDatcSetVr(11, 1, 0);
            callDatcSetVr(6, 1, 0);
            callDatcSetVr(5, 9, 0);
            applyZoneTempValue(true, celsius ? "17" : "62", snap);
            applyZoneTempValue(false, celsius ? "17" : "62", snap);
            return new CommandReply(ok, ok ? "MAX A/C Applied (LO + Max Fan)" : "DATC unavailable");
        }
        if ("climate_max_defrost".equals(cmd)) {
            boolean celsius = isCelsius(snap);
            boolean ok = callDatcSetVr(10, 1, 0);
            callIntWrite1(SVC_VR, IFACE_VR, "set_RrDefogger_Cmd_AVN", 1);
            callDatcSetVr(5, 9, 0);
            applyZoneTempValue(true, celsius ? "30" : "86", snap);
            return new CommandReply(ok, ok ? "MAX Defrost Applied (Front + Rear)" : "DATC unavailable");
        }
        if ("climate_recirc_toggle".equals(cmd) || "climate_recirc_on".equals(cmd) || "climate_fresh_air".equals(cmd)) {
            boolean nextRecirc = "climate_recirc_on".equals(cmd)
                    || ("climate_recirc_toggle".equals(cmd) && !"1".equals(snap.get("climate.recirc")));
            boolean ok = callDatcSetVr(11, nextRecirc ? 1 : 2, 0);
            return new CommandReply(ok, ok ? (nextRecirc ? "Recirculate Air" : "Fresh Air Intake") : "DATC unavailable");
        }
        if ("climate_sync_toggle".equals(cmd) || "climate_sync_on".equals(cmd) || "climate_sync_off".equals(cmd)) {
            boolean nextSync = "climate_sync_on".equals(cmd)
                    || ("climate_sync_toggle".equals(cmd) && !"1".equals(snap.get("climate.sync")));
            // SyncDispMode: ON = 1, OFF = 2
            boolean ok = callDatcSetVr(22, nextSync ? 1 : 2, 0) || callDatcSetFront(22, nextSync ? 1 : 2);
            return new CommandReply(ok, ok ? ("Dual-Zone SYNC " + (nextSync ? "ON" : "OFF")) : "DATC unavailable");
        }
        if ("climate_driver_only_toggle".equals(cmd) || "climate_driver_only_on".equals(cmd) || "climate_driver_only_off".equals(cmd)) {
            boolean next = "climate_driver_only_on".equals(cmd)
                    || ("climate_driver_only_toggle".equals(cmd) && !"1".equals(snap.get("climate.driver_only")));
            boolean ok = callDatcSetFront(24, next ? 1 : 0);
            return new CommandReply(ok, ok ? ("Driver Only " + (next ? "ON" : "OFF")) : "DATC unavailable");
        }
        if ("climate_front_defrost_toggle".equals(cmd) || "climate_front_defrost_on".equals(cmd) || "climate_front_defrost_off".equals(cmd)) {
            boolean next = "climate_front_defrost_on".equals(cmd)
                    || ("climate_front_defrost_toggle".equals(cmd) && !"1".equals(snap.get("climate.front_defrost")));
            boolean ok = callDatcSetVr(10, next ? 1 : 0, 0);
            return new CommandReply(ok, ok ? ("Front Defrost " + (next ? "ON" : "OFF")) : "DATC unavailable");
        }
        if ("climate_vent_cycle".equals(cmd) || "climate_vent_set".equals(cmd)) {
            int next;
            if ("climate_vent_set".equals(cmd)) {
                next = parseVentArg(arg);
            } else {
                int cur = parseIntSafe(snap.get("climate.vent_mode"), 1);
                next = cur == 1 ? 2 : (cur == 2 ? 3 : (cur == 3 ? 4 : (cur == 4 ? 5 : 1)));
            }
            boolean ok = callDatcSetVr(6, next, 0) || callDatcSetFront(6, next);
            return new CommandReply(ok, ok ? ("Vent -> " + ventModeName(next)) : "DATC unavailable");
        }
        if ("climate_fan_up".equals(cmd) || "climate_fan_down".equals(cmd) || "climate_fan_set".equals(cmd)) {
            int cur = parseIntSafe(snap.get("climate.fan_speed"), lastKnownFanStep);
            int target;
            if ("climate_fan_set".equals(cmd)) {
                target = Math.max(0, Math.min(8, parseIntSafe(arg, cur)));
            } else if ("climate_fan_up".equals(cmd)) {
                target = Math.min(8, Math.max(1, cur + 1));
            } else {
                target = Math.max(0, cur - 1);
            }
            if (target > 0) {
                lastKnownFanStep = target;
            }
            boolean ok = target == 0
                    ? (callDatcSetVr(5, 0, 0) || callDatcSetFront(51, 0))
                    : callDatcSetVr(5, target + 1, 0);
            return new CommandReply(ok, ok ? (target == 0 ? "Climate Fan OFF" : ("Fan Speed -> " + target)) : "DATC unavailable");
        }
        if ("climate_set_temp".equals(cmd)) {
            CommandReply dRes = applyZoneTempValue(true, arg, snap);
            applyZoneTempValue(false, arg, snap);
            return dRes;
        }
        if ("climate_driver_temp_set".equals(cmd)) {
            return applyZoneTempValue(true, arg, snap);
        }
        if ("climate_passenger_temp_set".equals(cmd)) {
            return applyZoneTempValue(false, arg, snap);
        }
        if ("climate_driver_temp_up".equals(cmd) || "climate_driver_temp_down".equals(cmd)) {
            return adjustZoneTemp(true, "climate_driver_temp_up".equals(cmd), snap);
        }
        if ("climate_passenger_temp_up".equals(cmd) || "climate_passenger_temp_down".equals(cmd)) {
            return adjustZoneTemp(false, "climate_passenger_temp_up".equals(cmd), snap);
        }
        if ("climate_smart_vent_toggle".equals(cmd)) {
            boolean next = !"1".equals(snap.get("climate.smart_vent"));
            boolean ok = callBoolWrite1(SVC_DATC, IFACE_DATC, "setSmartVentilation", next);
            return new CommandReply(ok, ok ? ("Smart Vent " + (next ? "ON" : "OFF")) : "DATC SmartVent unavailable");
        }
        if ("climate_auto_defog_toggle".equals(cmd)) {
            boolean next = !"1".equals(snap.get("climate.auto_defog"));
            boolean ok = callBoolWrite1(SVC_DATC, IFACE_DATC, "setAutoDefogSystem", next);
            return new CommandReply(ok, ok ? ("Auto Defog " + (next ? "ON" : "OFF")) : "DATC AutoDefog unavailable");
        }
        if ("climate_after_blow_toggle".equals(cmd)) {
            boolean next = !"1".equals(snap.get("climate.after_blow"));
            boolean ok = callBoolWrite1(SVC_DATC, IFACE_DATC, "setAfterBlow", next);
            return new CommandReply(ok, ok ? ("After-Blow " + (next ? "ON" : "OFF")) : "DATC AfterBlow unavailable");
        }
        if ("climate_air_clean_toggle".equals(cmd)) {
            boolean next = resolveToggleArg(arg, "1".equals(snap.get("climate.air_clean")));
            boolean ok = callDatcSetVr(7, next ? 1 : 0, 0);
            return new CommandReply(ok, ok ? ("Air Cleaning " + (next ? "ON" : "OFF")) : "DATC AirClean unavailable");
        }
        if ("rear_climate_toggle".equals(cmd) || "rear_climate_on".equals(cmd) || "rear_climate_off".equals(cmd)) {
            boolean next = "rear_climate_on".equals(cmd)
                    || ("rear_climate_toggle".equals(cmd) && !"1".equals(snap.get("climate.rear_on")));
            boolean ok = callBoolWrite1(SVC_DATC, IFACE_DATC, "setRearOnOff", next);
            return new CommandReply(ok, ok ? ("Rear Climate " + (next ? "ON" : "OFF")) : "DATC Rear unavailable");
        }
        if ("rear_climate_auto_toggle".equals(cmd)) {
            boolean next = resolveToggleArg(arg, lastRearAutoState);
            boolean ok = callBoolWrite1(SVC_DATC, IFACE_DATC, "setRearAutoDisp", next);
            if (ok) lastRearAutoState = next;
            return new CommandReply(ok, ok ? ("Rear Climate AUTO " + (next ? "ON" : "OFF")) : "DATC Rear unavailable");
        }
        if ("rear_climate_lock_toggle".equals(cmd)) {
            boolean next = resolveToggleArg(arg, "1".equals(snap.get("climate.rear_lock")));
            boolean ok = callBoolWrite1(SVC_DATC, IFACE_DATC, "setRearLockStatus", next);
            return new CommandReply(ok, ok ? ("Rear Controls Lock " + (next ? "ON" : "OFF")) : "DATC Rear Lock unavailable");
        }
        if ("rear_climate_temp_up".equals(cmd) || "rear_climate_temp_down".equals(cmd)) {
            boolean up = "rear_climate_temp_up".equals(cmd);
            boolean ok = callByteWrite1(SVC_DATC, IFACE_DATC, "setRearTempUpDn", (byte) (up ? 1 : 2));
            return new CommandReply(ok, ok ? ("Rear Temp " + (up ? "+" : "-")) : "DATC Rear Temp unavailable");
        }
        if ("rear_climate_fan_up".equals(cmd) || "rear_climate_fan_down".equals(cmd)) {
            boolean up = "rear_climate_fan_up".equals(cmd);
            boolean ok = callByteWrite1(SVC_DATC, IFACE_DATC, "setRearBlowerUpDn", (byte) (up ? 1 : 2));
            return new CommandReply(ok, ok ? ("Rear Fan " + (up ? "+" : "-")) : "DATC Rear Fan unavailable");
        }

        // --- Defrost, Wheel & Seats ---
        if ("rear_defrost_toggle".equals(cmd)) {
            boolean nextOff = "1".equals(snap.get("defogger"));
            int want = nextOff ? 0 : 1;
            boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_RrDefogger_Cmd_AVN", want);
            if (ok) bcmDefogger = want;
            return new CommandReply(ok, ok ? ("Rear Defrost " + (want == 1 ? "ON" : "OFF")) : "VRService unavailable");
        }
        if ("wheel_heat_toggle".equals(cmd)) {
            boolean nextOff = "1".equals(snap.get("wheel_heat"));
            CommandReply hmg = tryHmgCommandChecked("wheel_heat", nextOff ? "off" : "on");
            if (hmg != null) {
                if (hmg.ok) bcmWheelHeat = nextOff ? 0 : 1;
                return hmg.ok ? new CommandReply(true, "Steering Wheel Heat " + (nextOff ? "OFF" : "ON")) : hmg;
            }
            boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_StrgWhlHeated_Cmd_AVN", nextOff ? 0 : 1);
            if (ok) bcmWheelHeat = nextOff ? 0 : 1;
            return new CommandReply(ok, ok ? ("Steering Wheel Heat " + (nextOff ? "OFF" : "ON")) : "Wheel heat command failed");
        }
        if ("seat_heat_driver_cycle".equals(cmd) || "seat_heat_passenger_cycle".equals(cmd)) {
            boolean driver = "seat_heat_driver_cycle".equals(cmd);
            boolean twoStep = isSeatTwoStep(driver ? "driver" : "passenger");
            int cur = parseIntSafe(snap.get(driver ? "seat_heat.driver" : "seat_heat.passenger"), 0);
            int next = twoStep ? (cur <= 0 ? 2 : (cur == 2 ? 1 : 0)) : cycleSeatStep(cur);
            return setSeatClimate(driver ? "driver" : "passenger", true, next, twoStep);
        }
        if ("seat_heat_driver_set".equals(cmd) || "seat_heat_passenger_set".equals(cmd)) {
            boolean driver = "seat_heat_driver_set".equals(cmd);
            String seat = driver ? "driver" : "passenger";
            return setSeatClimate(seat, true, Math.max(0, Math.min(3, parseIntSafe(arg, 0))), isSeatTwoStep(seat));
        }
        if ("seat_vent_driver_cycle".equals(cmd) || "seat_vent_passenger_cycle".equals(cmd)) {
            boolean driver = "seat_vent_driver_cycle".equals(cmd);
            int cur = parseIntSafe(snap.get(driver ? "seat_vent.driver" : "seat_vent.passenger"), 0);
            return setSeatClimate(driver ? "driver" : "passenger", false, cycleSeatStep(cur), false);
        }
        if ("seat_vent_driver_set".equals(cmd) || "seat_vent_passenger_set".equals(cmd)) {
            boolean driver = "seat_vent_driver_set".equals(cmd);
            return setSeatClimate(driver ? "driver" : "passenger", false, Math.max(0, Math.min(3, parseIntSafe(arg, 0))), false);
        }
        if ("seat_heat_rl_cycle".equals(cmd) || "seat_heat_rr_cycle".equals(cmd)) {
            boolean left = "seat_heat_rl_cycle".equals(cmd);
            String seat = left ? "rear_left" : "rear_right";
            boolean twoStep = isSeatTwoStep(seat);
            int cur = parseIntSafe(snap.get(left ? "seat_heat.rear_left" : "seat_heat.rear_right"), 0);
            int next = twoStep ? (cur <= 0 ? 2 : (cur >= 2 ? 1 : 0)) : cycleSeatStep(cur);
            return setSeatClimate(seat, true, next, twoStep);
        }

        // --- Audio & EV ---
        if ("volume_up".equals(cmd) || "volume_down".equals(cmd)) {
            boolean up = "volume_up".equals(cmd);
            int curStream = lastAudioStream;
            Integer queriedStream = callIntNoArg(SVC_AUDIO, IFACE_AUDIO, "getCurStreamType", false);
            if (queriedStream != null && queriedStream >= 0) {
                Integer checkVol = callVolumeGet(queriedStream);
                if (checkVol != null && checkVol >= 0) {
                    curStream = queriedStream;
                    lastAudioStream = curStream;
                }
            }
            Integer curVol = callVolumeGet(curStream);
            int base = (curVol != null && curVol >= 0) ? curVol : parseIntSafe(snap.get("audio.volume"), 10);
            int target = Math.max(0, Math.min(MAX_AUDIO_LEVEL, base + (up ? 1 : -1)));
            boolean ok = callVolumeSet(curStream, target);
            return new CommandReply(ok, ok ? ("Volume -> " + target) : "MicomAudioService unavailable");
        }
        if ("volume_mute_toggle".equals(cmd)) {
            Integer m = callIntNoArg(SVC_AUDIO, IFACE_AUDIO, "getUserMuteState", false);
            boolean muted = (m != null) ? (m != 0) : "1".equals(snap.get("audio.muted"));
            boolean ok = callVoidNoArg(SVC_AUDIO, IFACE_AUDIO, muted ? "unMuteAV" : "muteAV");
            return new CommandReply(ok, ok ? (muted ? "Audio Unmuted" : "Audio Muted") : "MicomAudioService unavailable");
        }
        if ("precondition_toggle".equals(cmd)) {
            boolean active = "1".equals(snap.get("hv.conditioning_active"))
                    || "active".equalsIgnoreCase(snap.get("conditioning.state"))
                    || "waiting".equalsIgnoreCase(snap.get("conditioning.state"));
            String hmgArg = active ? "stop" : "start";
            try {
                String reply = HmgApi.connect().timeout(1200).command("conditioning", hmgArg);
                return new CommandReply(true, reply);
            } catch (Throwable hmgErr) {
                String errMsg = hmgErr.getMessage();
                if (errMsg != null && errMsg.trim().toLowerCase(Locale.US).startsWith("refused:")) {
                    return new CommandReply(false, errMsg.trim());
                }
                if (tryLaunchpadService(active)) {
                    return new CommandReply(true, active ? "Preconditioning stop sent to Launchpad" : "Preconditioning start sent to Launchpad");
                }
                return new CommandReply(false, String.valueOf(errMsg));
            }
        }
        if ("charge_door_open".equals(cmd) || "charge_door_close".equals(cmd) || "charge_door_toggle".equals(cmd)) {
            boolean currentlyOpen = "1".equals(snap.get("charge_door.open")) || bcmCdmState == 1;
            boolean open = "charge_door_open".equals(cmd)
                    || ("charge_door_toggle".equals(cmd) && !currentlyOpen);
            if ("0".equals(snap.get("gear.park"))) {
                return new CommandReply(false, "Refused: Car must be in PARK");
            }
            try {
                String reply = HmgApi.connect().timeout(1200).command("charge_door", open ? "open" : "close");
                bcmCdmState = open ? 1 : 2;
                return new CommandReply(true, reply);
            } catch (Throwable hmgErr) {
                String errMsg = hmgErr.getMessage();
                if (errMsg != null && errMsg.trim().toLowerCase(Locale.US).startsWith("refused:")) {
                    return new CommandReply(false, errMsg.trim());
                }
                boolean ok = callBoolWrite1(SVC_BCM, IFACE_BCM, "requestChargeLockDoor", !open);
                if (ok) bcmCdmState = open ? 1 : 2;
                return new CommandReply(ok, ok ? ("Charge door " + (open ? "open requested" : "close requested")) : String.valueOf(errMsg));
            }
        }
        return new CommandReply(false, "Unknown car command: " + cmd);
    }

    private synchronized void ensureVrSignalsEnabled() {
        long now = System.currentTimeMillis();
        boolean stale = (now - lastVrSignalsEnableMs) > 4000L;
        boolean any = callIntWrite1(SVC_VR, IFACE_VR, "All_Window_Req", 1);
        any = callIntWrite1(SVC_VR, IFACE_VR, "All_Sunroof_Req", 1) || any;
        any = callIntWrite1(SVC_VR, IFACE_VR, "All_PTGCNTL_Req", 1) || any;
        any = callIntWrite1(SVC_VR, IFACE_VR, "All_TrunkTl_Req", 1) || any;
        any = callIntWrite1(SVC_VR, IFACE_VR, "set_TxSignal_Exist_Req", 1) || any;
        if (any) {
            lastVrSignalsEnableMs = System.currentTimeMillis();
            if (stale) {
                sleepQuietly(50L);
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private CommandReply setAllWindows(int vrCmd, int bcmWindowCmd, String okLabel) {
        ensureVrSignalsEnabled();
        boolean ok = callIntWrite1(SVC_VR, IFACE_VR, "set_Window_ALL_UPDOWN", vrCmd);
        if (!ok) {
            ok = callIntWrite5(SVC_BCM, IFACE_BCM, "requestWindowAndIMSCmd",
                    bcmWindowCmd, bcmWindowCmd, 7, bcmWindowCmd, bcmWindowCmd);
        }
        return new CommandReply(ok, ok ? okLabel : "Window control unavailable");
    }

    private CommandReply setSingleWindow(String vrMethod, int vrVal, int drv, int ast, int rl, int rr, String okLabel) {
        ensureVrSignalsEnabled();
        boolean ok = callIntWrite1(SVC_VR, IFACE_VR, vrMethod, vrVal);
        if (!ok) {
            ok = callIntWrite5(SVC_BCM, IFACE_BCM, "requestWindowAndIMSCmd", drv, ast, 7, rl, rr);
        }
        return new CommandReply(ok, ok ? okLabel : "Window control unavailable");
    }

    private boolean isCelsius(Map<String, String> snap) {
        String u = snap == null ? null : snap.get("climate.temp_unit");
        if ("C".equals(u)) return true;
        if ("F".equals(u)) return false;
        return lastKnownCelsius != null && lastKnownCelsius.booleanValue();
    }

    private static boolean resolveToggleArg(String arg, boolean currentActive) {
        if (arg != null && !arg.trim().isEmpty()) {
            String a = arg.trim().toLowerCase(Locale.US);
            if ("1".equals(a) || "on".equals(a) || "true".equals(a)) return true;
            if ("0".equals(a) || "off".equals(a) || "false".equals(a)) return false;
        }
        return !currentActive;
    }

    private CommandReply applyZoneTempValue(boolean driver, String rawArg, Map<String, String> snap) {
        String a = rawArg == null ? "" : rawArg.trim().toUpperCase(Locale.US);
        boolean celsius = isCelsius(snap);
        if ("LO".equals(a) || "LOW".equals(a)) {
            boolean ok = applyTempExact(driver, celsius, celsius ? 17.0f : 62f);
            return new CommandReply(ok, ok ? ((driver ? "Driver" : "Pass") + " Temp -> LO") : "DATC unavailable");
        }
        if ("HI".equals(a) || "HIGH".equals(a)) {
            boolean ok = applyTempExact(driver, celsius, celsius ? 32.0f : 90f);
            return new CommandReply(ok, ok ? ((driver ? "Driver" : "Pass") + " Temp -> HI") : "DATC unavailable");
        }
        float val = parseFloatSafe(a, celsius ? 22.0f : 72.0f);
        if (celsius && val > 40.0f) {
            val = Math.round(((val - 32.0f) * 5.0f / 9.0f) * 2.0f) / 2.0f;
        } else if (!celsius && val <= 40.0f) {
            val = Math.round(val * 1.8f + 32.0f);
        }
        boolean ok = applyTempExact(driver, celsius, val);
        String label = celsius ? (formatHalf(val) + "\u00B0C") : (Math.round(val) + "\u00B0F");
        return new CommandReply(ok, ok ? ((driver ? "Driver Temp -> " : "Pass Temp -> ") + label) : "DATC unavailable");
    }

    private boolean applyTempExact(boolean driver, boolean celsius, float targetVal) {
        if (celsius) {
            float clamped = Math.max(17.0f, Math.min(32.0f, Math.round(targetVal * 2.0f) / 2.0f));
            int rawC = Math.max(6, Math.min(36, Math.round(clamped * 2.0f) - 28));
            if (callDatcSetFront(driver ? 28 : 29, rawC)) {
                return true;
            }
            return driver && callDatcSetVr(2, Math.round(clamped), 1);
        }
        int clampedF = Math.max(62, Math.min(90, Math.round(targetVal)));
        int rawF = Math.max(6, Math.min(34, clampedF - 56));
        if (callDatcSetFront(driver ? 30 : 31, rawF)) {
            return true;
        }
        return driver && callDatcSetVr(2, clampedF, 2);
    }

    private CommandReply adjustZoneTemp(boolean driver, boolean up, Map<String, String> snap) {
        boolean celsius = isCelsius(snap);
        String curStr = snap.get(driver ? "climate.driver_temp" : "climate.passenger_temp");
        boolean okStep = callDatcSetFront(driver ? 50 : 49, up ? 1 : 2);
        if (celsius) {
            float curC = "LO".equalsIgnoreCase(curStr) ? 17.0f
                    : ("HI".equalsIgnoreCase(curStr) ? 32.0f : parseFloatSafe(curStr, 22.0f));
            float nextC = Math.max(17.0f, Math.min(32.0f, Math.round((curC + (up ? 0.5f : -0.5f)) * 2.0f) / 2.0f));
            boolean ok = okStep || applyTempExact(driver, true, nextC);
            return new CommandReply(ok, ok ? ((driver ? "Driver Temp -> " : "Pass Temp -> ") + formatHalf(nextC) + "\u00B0C") : "DATC unavailable");
        }
        int curF = "LO".equalsIgnoreCase(curStr) ? 62
                : ("HI".equalsIgnoreCase(curStr) ? 90 : Math.round(parseFloatSafe(curStr, 72.0f)));
        int nextF = Math.max(62, Math.min(90, curF + (up ? 1 : -1)));
        boolean ok = okStep || applyTempExact(driver, false, nextF);
        return new CommandReply(ok, ok ? ((driver ? "Driver Temp -> " : "Pass Temp -> ") + nextF + "\u00B0F") : "DATC unavailable");
    }

    private boolean isSeatTwoStep(String seat) {
        String optMethod = "driver".equals(seat) ? "getDrvSeatHeatOption"
                : ("passenger".equals(seat) ? "getAstSeatHeatOption"
                : ("rear_left".equals(seat) ? "getRLSeatHeatOption" : "getRRSeatHeatOption"));
        Integer opt = callIntNoArg(SVC_BCM, IFACE_BCM, optMethod, false);
        return opt != null && opt == 1;
    }

    private CommandReply setSeatClimate(String seatName, boolean heat, int level, boolean twoStep) {
        int clamped = Math.max(0, Math.min(twoStep && heat ? 2 : 3, level));
        String levelWord = clamped == 0 ? "off"
                : (twoStep && heat ? (clamped == 1 ? "low" : "high")
                : (clamped == 1 ? "low" : (clamped == 2 ? "medium" : "high")));
        String prettySeat = "driver".equals(seatName) ? "Driver "
                : ("passenger".equals(seatName) ? "Passenger "
                : ("rear_left".equals(seatName) ? "Rear Left " : "Rear Right "));

        CommandReply hmg = tryHmgCommandChecked(heat ? "seat_heat" : "seat_vent", seatName, levelWord);
        if (hmg != null) {
            return hmg.ok
                    ? new CommandReply(true, prettySeat + (heat ? "Heat -> " : "Vent -> ") + levelWord.toUpperCase(Locale.US))
                    : hmg;
        }
        int usmCode = clamped == 0 ? 2
                : (heat ? ((twoStep && clamped == 2) ? 8 : (clamped + 5)) : (clamped + 2));
        String usmMethod = "driver".equals(seatName) ? "setFrontSeatHeatDrvStep"
                : ("passenger".equals(seatName) ? "setFrontSeatHeatAstStep"
                : ("rear_left".equals(seatName) ? "setRearSeatHeatLeftStep" : "setRearSeatHeatRightStep"));
        boolean ok = callIntWrite1(SVC_USM, IFACE_USM, usmMethod, usmCode);
        return new CommandReply(ok, ok
                ? (prettySeat + (heat ? "Heat -> " : "Vent -> ") + levelWord.toUpperCase(Locale.US))
                : "USM seat command unavailable");
    }

    private static int parseVentArg(String arg) {
        if (arg == null) return 1;
        String low = arg.trim().toLowerCase(Locale.US);
        if ("face".equals(low) || "vent".equals(low) || "1".equals(low)) return 1;
        if ("bi".equals(low) || "bilevel".equals(low) || "bi-level".equals(low) || "2".equals(low)) return 2;
        if ("floor".equals(low) || "3".equals(low)) return 3;
        if ("mix".equals(low) || "floor_def".equals(low) || "floor+def".equals(low) || "4".equals(low)) return 4;
        if ("defrost".equals(low) || "def".equals(low) || "5".equals(low) || "10".equals(low)) return 5;
        int n = parseIntSafe(low, 1);
        return Math.max(1, Math.min(10, n));
    }

    private static int cycleSeatStep(int cur) {
        if (cur <= 0) return 3;
        return cur - 1;
    }

    /**
     * Returns CommandReply(true, reply) on HMG-API success, CommandReply(false, msg) if HMG-API
     * explicitly refused the command ("refused: ..."), or null if hmgapid is unreachable so the
     * caller may safely fall back to direct Binder calls.
     */
    private CommandReply tryHmgCommandChecked(String name, String... args) {
        try {
            String res = HmgApi.connect().timeout(1200).command(name, args);
            return new CommandReply(true, res);
        } catch (Throwable err) {
            String msg = err.getMessage();
            if (msg != null && msg.trim().toLowerCase(Locale.US).startsWith("refused:")) {
                return new CommandReply(false, msg.trim());
            }
            return null;
        }
    }

    private boolean tryLaunchpadService(boolean stop) {
        String action = stop
                ? "com.paytondev.launchpad.PRECONDITION_STOP"
                : "com.paytondev.launchpad.PRECONDITION_START";
        String[] pkgs = new String[] { "com.paytondev.precondition", "com.paytondev.launchpad" };
        for (String pkg : pkgs) {
            if (!new File("/data/data/" + pkg).isDirectory()) {
                continue;
            }
            try {
                PreferredHomeHelper.CommandResult res = PreferredHomeHelper.runCommandCapture(new String[] {
                        "/system/bin/am", "startservice",
                        "-a", action,
                        "-n", pkg + "/com.paytondev.launchpad.PreconditioningService"
                }, 1500L);
                boolean ok = res.exitCode == 0 && !res.output.contains("Error:");
                if (ok) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    // ---- IBCMListener & IDATCListener Registration ----

    private synchronized void ensureBcmListenerRegistered() {
        IBinder b = getBinder(SVC_BCM);
        if (b == null || !b.isBinderAlive()) {
            registeredBcmService = null;
            return;
        }
        if (registeredBcmService == b && bcmListenerBinder != null) {
            return;
        }
        try {
            txBcmDoorAll = resolveListenerTx(IFACE_BCM_LISTENER, "onDoorAllStateChanged", FB_BCM_ON_DOOR_ALL);
            txBcmDoorLock = resolveListenerTx(IFACE_BCM_LISTENER, "onDoorLockStateChanged", FB_BCM_ON_DOOR_LOCK);
            txBcmSeatHeat = resolveListenerTx(IFACE_BCM_LISTENER, "onSeatHeaterStatusChanged", FB_BCM_ON_SEAT_HEAT);
            txBcmSeatVent = resolveListenerTx(IFACE_BCM_LISTENER, "onSeatVentilationStatusChanged", FB_BCM_ON_SEAT_VENT);
            txBcmParkBrake = resolveListenerTx(IFACE_BCM_LISTENER, "onParkBrakeStatusChanged", FB_BCM_ON_PARK_BRAKE);
            txBcmGearInfo = resolveListenerTx(IFACE_BCM_LISTENER, "onGearStatusInfoChanged", FB_BCM_ON_GEAR_INFO);
            txBcmRemoteHeat = resolveListenerTx(IFACE_BCM_LISTENER, "onRemoteTeleHeatingStatusChanged", FB_BCM_ON_REMOTE_HEAT);
            txBcmWindowOpen = resolveListenerTx(IFACE_BCM_LISTENER, "onWindowOpenStateChanged", FB_BCM_ON_WINDOW_OPEN);
            txBcmDigitalKey = resolveListenerTx(IFACE_BCM_LISTENER, "onDigitalKeyDataChanged", FB_BCM_ON_DIGITAL_KEY);
            txBcmModemInfo = resolveListenerTx(IFACE_BCM_LISTENER, "onModemInfoChanged", FB_BCM_ON_MODEM_INFO);
            txBcmBattCond = resolveListenerTx(IFACE_BCM_LISTENER, "onBatteryPreconditioning", FB_BCM_ON_BATT_COND);
            txBcmSunroof = resolveListenerTx(IFACE_BCM_LISTENER, "onsunroofOpen", FB_BCM_ON_SUNROOF_OPEN);
            txBcmCdm = resolveListenerTx(IFACE_BCM_LISTENER, "onCDMChanged", FB_BCM_ON_CDM_CHANGED);

            if (bcmListenerToken == null) {
                bcmListenerToken = new Binder();
            }
            if (bcmListenerBinder == null) {
                bcmListenerBinder = new Binder() {
                    {
                        attachInterface(null, IFACE_BCM_LISTENER);
                    }

                    @Override
                    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                        if (code == INTERFACE_TRANSACTION) {
                            if (reply != null) reply.writeString(IFACE_BCM_LISTENER);
                            return true;
                        }
                        try {
                            if (code == txBcmDigitalKey || code == txBcmModemInfo) {
                                data.enforceInterface(IFACE_BCM_LISTENER);
                                int len = data.readInt();
                                if ((flags & FLAG_ONEWAY) == 0 && reply != null) {
                                    reply.writeNoException();
                                    reply.writeByteArray(len >= 0 && len <= MAX_OUT_ARRAY_BYTES ? new byte[len] : null);
                                }
                                return true;
                            }
                            if (isHandledBcmCode(code)) {
                                data.enforceInterface(IFACE_BCM_LISTENER);
                                handleBcmCallback(code, data);
                            }
                        } catch (Throwable ignored) {
                        }
                        if ((flags & FLAG_ONEWAY) == 0 && reply != null) {
                            answerSafeReply(data, reply);
                        }
                        return true;
                    }
                };
            }
            TxEntry regTx = resolveTxByName(IFACE_BCM, "registerBCMListener");
            int regCode = regTx != null ? regTx.code : 1;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(IFACE_BCM);
                data.writeStrongBinder(bcmListenerBinder);
                data.writeStrongBinder(bcmListenerToken);
                b.transact(regCode, data, reply, 0);
                reply.readException();
                registeredBcmService = b;
            } finally {
                data.recycle();
                reply.recycle();
            }
            callVoidNoArg(SVC_BCM, IFACE_BCM, "getDoorLockStatus");
            callVoidNoArg(SVC_BCM, IFACE_BCM, "getHoodStatusInfo");
        } catch (Throwable ignored) {
        }
    }

    private synchronized void ensureDatcListenerRegistered() {
        IBinder b = getBinder(SVC_DATC);
        if (b == null || !b.isBinderAlive()) {
            registeredDatcService = null;
            return;
        }
        if (registeredDatcService == b && datcListenerBinder != null) {
            return;
        }
        try {
            ClassLoader cl = getInterfaceLoader(false);
            if (datcStatusCreator == null) {
                Class<?> stCls = Class.forName(CLS_DATC_STATUS, false, cl);
                Field creatorField = stCls.getField("CREATOR");
                datcStatusCreator = creatorField.get(null);
                mAmbientF = stCls.getMethod("getAmbientTempF");
                mAmbientC = stCls.getMethod("getAmbientTempC");
                mAmbientStateC = stCls.getMethod("getAmbientTempStateC");
                try {
                    mAmbientStateF = stCls.getMethod("getAmbientTempStateF");
                } catch (Throwable ignored) {
                }
                try {
                    mDatcStatusTempUnit = stCls.getMethod("getTempUnit");
                } catch (Throwable ignored) {
                }
            }
            txDatcStatus = resolveListenerTx(IFACE_DATC_LISTENER, "onDatcStatus", 1);
            if (datcListenerToken == null) {
                datcListenerToken = new Binder();
            }
            if (datcListenerBinder == null) {
                datcListenerBinder = new Binder() {
                    {
                        attachInterface(null, IFACE_DATC_LISTENER);
                    }

                    @Override
                    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                        if (code == INTERFACE_TRANSACTION) {
                            if (reply != null) reply.writeString(IFACE_DATC_LISTENER);
                            return true;
                        }
                        try {
                            if (code == txDatcStatus && datcStatusCreator instanceof android.os.Parcelable.Creator) {
                                data.enforceInterface(IFACE_DATC_LISTENER);
                                if (data.readInt() != 0) {
                                    Object st = ((android.os.Parcelable.Creator<?>) datcStatusCreator).createFromParcel(data);
                                    float f = ((Float) mAmbientF.invoke(st)).floatValue();
                                    float cc = ((Float) mAmbientC.invoke(st)).floatValue();
                                    int stateC = ((Integer) mAmbientStateC.invoke(st)).intValue();
                                    int stateF = mAmbientStateF != null
                                            ? ((Integer) mAmbientStateF.invoke(st)).intValue()
                                            : 0;
                                    if (mDatcStatusTempUnit != null) {
                                        int tu = ((Integer) mDatcStatusTempUnit.invoke(st)).intValue();
                                        if (tu == 1 || tu == 2) {
                                            datcStatusTempUnit = tu;
                                        }
                                    }
                                    datcAmbientStateC = stateC;
                                    datcAmbientStateF = stateF;
                                    datcOutsideF10 = (stateF == 255 || stateF == -1 || Float.isNaN(f) || f < -60f || f > 160f)
                                            ? Integer.MIN_VALUE
                                            : Math.round(f * 10f);
                                    datcOutsideC10 = (stateC == 255 || stateC == -1 || Float.isNaN(cc) || cc < -50f || cc > 65f)
                                            ? Integer.MIN_VALUE
                                            : Math.round(cc * 10f);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        if ((flags & FLAG_ONEWAY) == 0 && reply != null) {
                            answerSafeReply(data, reply);
                        }
                        return true;
                    }
                };
            }
            TxEntry regTx = resolveTxByName(IFACE_DATC, "registerDATCListener");
            if (regTx == null) return;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(IFACE_DATC);
                data.writeStrongBinder(datcListenerBinder);
                data.writeStrongBinder(datcListenerToken);
                b.transact(regTx.code, data, reply, 0);
                reply.readException();
                registeredDatcService = b;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void answerSafeReply(Parcel data, Parcel reply) {
        reply.writeNoException();
        int end = data.dataSize();
        if (end < 4) return;
        data.setDataPosition(end - 4);
        int n = data.readInt();
        if (n >= 0 && n <= MAX_OUT_ARRAY_BYTES) {
            reply.writeByteArray(new byte[n]);
        }
    }

    private boolean isHandledBcmCode(int code) {
        return code == txBcmDoorAll || code == txBcmDoorLock || code == txBcmSeatHeat
                || code == txBcmSeatVent || code == txBcmParkBrake || code == txBcmRemoteHeat
                || code == txBcmWindowOpen || code == txBcmBattCond || code == txBcmSunroof
                || code == txBcmCdm;
    }

    private void handleBcmCallback(int code, Parcel data) {
        if (code == txBcmDoorAll) {
            bcmUnlockFl = data.readInt();
            bcmUnlockFr = data.readInt();
            bcmUnlockRl = data.readInt();
            bcmUnlockRr = data.readInt();
            bcmOpenFl = data.readInt();
            bcmOpenFr = data.readInt();
            bcmOpenRl = data.readInt();
            bcmOpenRr = data.readInt();
            bcmTrunkOpen = data.readInt();
            bcmHoodOpen = data.readInt();
        } else if (code == txBcmDoorLock) {
            bcmLockState = data.readInt();
        } else if (code == txBcmSeatHeat) {
            bcmSeatHeatDrv = data.readInt();
            bcmSeatHeatAst = data.readInt();
            bcmSeatHeatRl = data.readInt();
            bcmSeatHeatRr = data.readInt();
        } else if (code == txBcmSeatVent) {
            bcmSeatVentDrv = data.readInt();
            bcmSeatVentAst = data.readInt();
        } else if (code == txBcmParkBrake) {
            bcmParkBrake = data.readInt();
        } else if (code == txBcmRemoteHeat) {
            bcmWheelHeat = data.readInt();
            bcmDefogger = data.readInt();
        } else if (code == txBcmWindowOpen) {
            bcmWdwFl = data.readInt();
            bcmWdwFr = data.readInt();
            bcmWdwRl = data.readInt();
            bcmWdwRr = data.readInt();
        } else if (code == txBcmBattCond) {
            bcmBattCond = data.readInt();
        } else if (code == txBcmSunroof) {
            bcmSunroofOpen = data.readInt();
        } else if (code == txBcmCdm) {
            bcmCdmState = data.readInt();
        }
    }

    private Integer pollHevEvReady() {
        try {
            if (cachedHevEvRdyFlag == null) {
                Class<?> flagCls = Class.forName("com.hkmc.hev.HEVManager$HEVFlag", false, getInterfaceLoader(false));
                Field f = flagCls.getField("EV_RDY");
                cachedHevEvRdyFlag = Integer.valueOf(f.getInt(null));
            }
            return callIntArgInt(SVC_HEV, IFACE_HEV, "getHEVStatus", cachedHevEvRdyFlag.intValue(), false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int resolveListenerTx(String ifaceName, String methodName, int fallback) {
        try {
            Class<?> stubCls = Class.forName(ifaceName + "$Stub", false, getInterfaceLoader(false));
            Field f = stubCls.getDeclaredField("TRANSACTION_" + methodName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    // ---- Low-level AIDL reflection & Parcel helpers ----

    private synchronized IBinder getBinder(String svcName) {
        IBinder b = binderCache.get(svcName);
        if (b != null && b.isBinderAlive()) {
            return b;
        }
        try {
            if (mGetService == null) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                mGetService = sm.getMethod("getService", String.class);
            }
            Object raw = mGetService.invoke(null, svcName);
            if (raw instanceof IBinder) {
                IBinder fresh = (IBinder) raw;
                binderCache.put(svcName, fresh);
                return fresh;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private synchronized ClassLoader getInterfaceLoader(boolean useHcanJar) {
        if (!useHcanJar) {
            return DirectCarServiceBridge.class.getClassLoader();
        }
        if (hscanLoader != null) {
            return hscanLoader;
        }
        ClassLoader base = DirectCarServiceBridge.class.getClassLoader();
        try {
            Class.forName(IFACE_HSCAN, false, base);
            hscanLoader = base;
            return hscanLoader;
        } catch (Throwable ignored) {
        }
        try {
            File f = new File(JAR_HSCAN);
            if (f.isFile()) {
                hscanLoader = new dalvik.system.PathClassLoader(JAR_HSCAN, base);
                return hscanLoader;
            }
        } catch (Throwable ignored) {
        }
        return base;
    }

    private synchronized TxEntry resolveTxByName(String iface, String method) {
        String key = iface + "#ANY#" + method;
        TxEntry cached = txCache.get(key);
        if (cached != null) {
            return cached.code == MISSING_TX ? null : cached;
        }
        try {
            ClassLoader cl = getInterfaceLoader(false);
            Class<?> stubCls = Class.forName(iface + "$Stub", false, cl);
            Field txField = stubCls.getDeclaredField("TRANSACTION_" + method);
            txField.setAccessible(true);
            TxEntry entry = new TxEntry(txField.getInt(null), void.class);
            txCache.put(key, entry);
            return entry;
        } catch (Throwable t) {
            txCache.put(key, new TxEntry(MISSING_TX, void.class));
            return null;
        }
    }

    private synchronized TxEntry resolveTx(String iface, String method, boolean useHcanJar, Class<?>... expectedParams) {
        String key = iface + "#" + method + "#" + paramSig(expectedParams);
        TxEntry cached = txCache.get(key);
        if (cached != null) {
            return cached.code == MISSING_TX ? null : cached;
        }
        try {
            ClassLoader cl = getInterfaceLoader(useHcanJar);
            Class<?> ifaceCls = Class.forName(iface, false, cl);
            Method match = null;
            for (Method m : ifaceCls.getMethods()) {
                if (!m.getName().equals(method)) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != expectedParams.length) {
                    continue;
                }
                boolean same = true;
                for (int i = 0; i < pts.length; i++) {
                    if (pts[i] != expectedParams[i]) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    match = m;
                    break;
                }
            }
            if (match == null) {
                txCache.put(key, new TxEntry(MISSING_TX, void.class));
                return null;
            }
            Class<?> stubCls = Class.forName(iface + "$Stub", false, cl);
            Field txField = stubCls.getDeclaredField("TRANSACTION_" + method);
            txField.setAccessible(true);
            int code = txField.getInt(null);
            TxEntry entry = new TxEntry(code, match.getReturnType());
            txCache.put(key, entry);
            return entry;
        } catch (Throwable t) {
            txCache.put(key, new TxEntry(MISSING_TX, void.class));
            return null;
        }
    }

    private Integer callIntNoArg(String svc, String iface, String method, boolean useHcan) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, useHcan);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return Integer.valueOf(reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Integer callIntArgInt(String svc, String iface, String method, int arg, boolean useHcan) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, useHcan, int.class);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeInt(arg);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return Integer.valueOf(reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Float callNumericNoArgAsFloat(String svc, String iface, String method, boolean useHcan) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, useHcan);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            if (tx.returnType == float.class) {
                return Float.valueOf(reply.readFloat());
            }
            if (tx.returnType == double.class) {
                return Float.valueOf((float) reply.readDouble());
            }
            return Float.valueOf((float) reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Integer callIntArgByte(String svc, String iface, String method, byte arg, boolean useHcan) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, useHcan, byte.class);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeByte(arg);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return Integer.valueOf(reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Float callFloatArgByte(String svc, String iface, String method, byte arg, boolean useHcan) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, useHcan, byte.class);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeByte(arg);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            if (tx.returnType == float.class) {
                return Float.valueOf(reply.readFloat());
            }
            return Float.valueOf((float) reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Float callDatcStatus(int flag) {
        IBinder b = getBinder(SVC_DATC);
        TxEntry tx = resolveTx(IFACE_DATC, "getVRClimateStatus", false, int.class);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_DATC);
            data.writeInt(flag);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return Float.valueOf(reply.readFloat());
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_DATC, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callDatcSetVr(int a, int bArg, int c) {
        IBinder b = getBinder(SVC_DATC);
        TxEntry tx = resolveTx(IFACE_DATC, "setVRClimateStatus", false, int.class, int.class, int.class);
        if (b == null || tx == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_DATC);
            data.writeInt(a);
            data.writeInt(bArg);
            data.writeInt(c);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_DATC, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callDatcSetFront(int a, int bArg) {
        IBinder b = getBinder(SVC_DATC);
        TxEntry tx = resolveTx(IFACE_DATC, "setFrontClimateControl", false, int.class, int.class);
        if (b == null || tx == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_DATC);
            data.writeInt(a);
            data.writeInt(bArg);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_DATC, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Integer callVolumeGet(int streamType) {
        IBinder b = getBinder(SVC_AUDIO);
        TxEntry tx = resolveTx(IFACE_AUDIO, "getVolume", false, int.class, int.class);
        if (b == null || tx == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_AUDIO);
            data.writeInt(streamType);
            data.writeInt(0);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return Integer.valueOf(reply.readInt());
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_AUDIO, b);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callVolumeSet(int streamType, int level) {
        IBinder b = getBinder(SVC_AUDIO);
        TxEntry tx = resolveTx(IFACE_AUDIO, "setVolume", false, int.class, int.class, int.class);
        if (b == null || tx == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(IFACE_AUDIO);
            data.writeInt(streamType);
            data.writeInt(level);
            data.writeInt(0); // flag 0 updates stream without OEM popup banner
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(SVC_AUDIO, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callIntWrite1(String svc, String iface, String method, int val) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, false, int.class);
        if (b == null || tx == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeInt(val);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callByteWrite1(String svc, String iface, String method, byte val) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, false, byte.class);
        if (b == null || tx == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeByte(val);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callIntWrite5(String svc, String iface, String method, int a, int bArg, int c, int d, int e) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, false, int.class, int.class, int.class, int.class, int.class);
        if (b == null || tx == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeInt(a);
            data.writeInt(bArg);
            data.writeInt(c);
            data.writeInt(d);
            data.writeInt(e);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callBoolWrite1(String svc, String iface, String method, boolean val) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, false, boolean.class);
        if (b == null || tx == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            data.writeInt(val ? 1 : 0);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean callVoidNoArg(String svc, String iface, String method) {
        IBinder b = getBinder(svc);
        TxEntry tx = resolveTx(iface, method, false);
        if (b == null || tx == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            b.transact(tx.code, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            invalidateBinderIfDead(svc, b);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private synchronized void invalidateBinderIfDead(String svcName, IBinder b) {
        if (b == null || !b.isBinderAlive()) {
            binderCache.remove(svcName);
            if (SVC_BCM.equals(svcName)) {
                registeredBcmService = null;
            } else if (SVC_DATC.equals(svcName)) {
                registeredDatcService = null;
            } else if (SVC_VR.equals(svcName)) {
                lastVrSignalsEnableMs = 0L;
            }
        }
    }

    private static void putDatcBool(Map<String, String> out, String key, Float val, int trueVal) {
        if (val != null && !Float.isNaN(val) && val >= 0f) {
            out.put(key, Math.round(val) == trueVal ? "1" : "0");
        }
    }

    private static void putByteFlag(Map<String, String> out, String key, Integer val) {
        if (val != null && val >= 0 && val <= 255) {
            out.put(key, String.valueOf(val));
        }
    }

    private static void putWindowFlag(Map<String, String> out, String key, Integer val) {
        // BCMConstants$WindowOpenStatus: 0=CLOSE, 1=OPEN, 2=RESERVED, 3=INVALID
        if (val != null && (val == 0 || val == 1)) {
            out.put(key, val == 1 ? "1" : "0");
        }
    }

    private static void putOpenFlag(Map<String, String> out, String key, int raw) {
        if (raw == 0 || raw == 1) {
            out.put(key, raw == 1 ? "1" : "0");
        }
    }

    private static void putLockFlag(Map<String, String> out, String key, int unlockState) {
        if (unlockState == 0 || unlockState == 1) {
            out.put(key, unlockState == 0 ? "1" : "0");
        }
    }

    private static int normalizeVentValue(int raw) {
        // BCMConstants$SeatVentilationState: 0=OFF, 1=ON, 2=LEVEL_1, 3=LEVEL_2, 4=LEVEL_3
        if (raw == 0) return 0;
        if (raw >= 2 && raw <= 4) return raw - 1;
        if (raw == 1) return 1;
        return 0;
    }

    private static int normalizeLegacyHeaterCallback(int raw) {
        // onSeatHeaterStatusChanged(IIII): 0=OFF, 1=ON, 2=LEVEL_1, 3=LEVEL_2, 4=LEVEL_3
        if (raw == 0) return 0;
        if (raw >= 2 && raw <= 4) return raw - 1;
        if (raw == 1) return 1;
        return 0;
    }

    private static void decodeSeatState(Map<String, String> out, String seat, Integer state, boolean hasVent, boolean twoStep) {
        if (state == null || state <= 0 || state == 0xF) return;
        int heat = -1;
        int vent = -1;
        if (state == 2) {
            heat = 0;
            vent = 0;
        } else if (state >= 3 && state <= 5) {
            heat = 0;
            vent = state - 2;
        } else if (state >= 6 && state <= 8) {
            heat = twoStep ? (state == 6 ? 1 : 2) : (state - 5);
            vent = 0;
        }
        if (heat >= 0) {
            out.put("seat_heat." + seat, String.valueOf(heat));
        }
        if (hasVent && vent >= 0) {
            out.put("seat_vent." + seat, String.valueOf(vent));
        }
    }

    private static String ventModeName(int mode) {
        switch (mode) {
            case 0: return "AUTO";
            case 1: return "FACE";
            case 2: return "BI-LEVEL";
            case 3: return "FLOOR";
            case 4: return "FLOOR+DEF";
            case 5:
            case 10: return "DEFROST";
            default: return "MODE " + mode;
        }
    }

    private static String paramSig(Class<?>[] params) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c : params) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c.getName());
        }
        return sb.toString();
    }

    private static String formatCompactFloat(float v) {
        if (Math.abs(v - Math.round(v)) < 0.05f) {
            return String.valueOf(Math.round(v));
        }
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String formatHalf(float v) {
        float rounded = Math.round(v * 2.0f) / 2.0f;
        if (Math.abs(rounded - Math.round(rounded)) < 0.05f) {
            return String.valueOf(Math.round(rounded));
        }
        return String.format(Locale.US, "%.1f", rounded);
    }

    private static int parseIntSafe(String raw, int fallback) {
        try {
            if (raw == null || raw.isEmpty() || "--".equals(raw) || "OFF".equalsIgnoreCase(raw)) return fallback;
            return Math.round(Float.parseFloat(raw));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static float parseFloatSafe(String raw, float fallback) {
        try {
            if (raw == null || raw.isEmpty() || "--".equals(raw) || "OFF".equalsIgnoreCase(raw)) return fallback;
            return Float.parseFloat(raw);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
