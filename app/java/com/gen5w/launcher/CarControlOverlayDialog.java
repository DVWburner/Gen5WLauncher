package com.gen5w.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gen5w.launcher.audio.SafeClickSoundPlayer;
import com.gen5w.launcher.ipc.SwitcherClient;
import com.gen5w.launcher.ipc.TelemetryModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen two-column Gen5W direct-Binder vehicle control console.
 * Left column: Set AC, temp presets, direct fan & vent modes, dual-zone steppers, cabin extras, rear HVAC.
 * Right column: Live door/lock/trunk/hood telemetry, locks/doors/trunk/hatch/charge port, windows/sunroof, seats & audio.
 */
final class CarControlOverlayDialog extends FrameLayout {
    static final String[] REQUIRED_HMG_KEYS = {
            "seat_heat.driver", "seat_heat.passenger", "seat_heat.rear_left", "seat_heat.rear_right",
            "seat_vent.driver", "seat_vent.passenger", "wheel_heat", "defogger", "hv.conditioning_active"
    };

    interface Listener {
        void onCloseRequested();
        void onCommandDispatched();
    }

    private static final class ToggleTile {
        final TextView view;
        final String baseLabel;
        boolean active;
        int onColor;

        ToggleTile(TextView view, String baseLabel, int onColor) {
            this.view = view;
            this.baseLabel = baseLabel;
            this.onColor = onColor;
        }
    }

    private static final int BG_CARD = 0xFF0E1729;
    private static final int BORDER_CARD = 0xFF1E3A5F;
    private static final int BTN_IDLE_BG = 0xFF162238;
    private static final int BTN_IDLE_BORDER = 0xFF334155;

    private final SafeClickSoundPlayer sound;
    private final Listener listener;
    private final TextView parkPill;
    private final TextView statusLine;

    // Live body status strip pills
    private final TextView lockStatusPill;
    private final TextView doorsStatusPill;
    private final TextView trunkStatusPill;
    private final TextView hoodStatusPill;

    // Stepper & readout labels
    private final TextView driverTempValue;
    private final TextView passTempValue;
    private final TextView fanSpeedValue;
    private final TextView volumeValue;

    // Active-state climate buttons
    private final ToggleTile powerBtn;
    private final ToggleTile autoBtn;
    private final ToggleTile acBtn;
    private final ToggleTile heatBtn;
    private final ToggleTile recircBtn;
    private final ToggleTile syncBtn;
    private final ToggleTile driverOnlyBtn;
    private final ToggleTile frontDefrostBtn;
    private final ToggleTile rearDefrostBtn;
    private final ToggleTile wheelHeatBtn;
    private final ToggleTile smartVentBtn;
    private final ToggleTile autoDefogBtn;
    private final ToggleTile afterBlowBtn;
    private final ToggleTile airCleanBtn;
    private final ToggleTile rearHvacBtn;
    private final ToggleTile rearLockBtn;

    // Direct vent & fan highlights
    private final List<ToggleTile> ventButtons = new ArrayList<ToggleTile>();
    private final List<ToggleTile> fanStepButtons = new ArrayList<ToggleTile>();
    private final List<ToggleTile> tempPresetButtons = new ArrayList<ToggleTile>();

    // Right column toggles
    private final ToggleTile lockAllBtn;
    private final ToggleTile trunkToggleBtn;
    private final ToggleTile chargeDoorBtn;
    private final ToggleTile drvSeatHeatBtn;
    private final ToggleTile drvSeatVentBtn;
    private final ToggleTile passSeatHeatBtn;
    private final ToggleTile passSeatVentBtn;
    private final ToggleTile rlSeatHeatBtn;
    private final ToggleTile rrSeatHeatBtn;
    private final ToggleTile muteBtn;
    private final ToggleTile precondBtn;

    private final String[] tempArgsF = { "LO", "66", "68", "70", "72", "74", "76", "HI" };
    private final String[] tempArgsC = { "LO", "18", "20", "21", "22", "23", "25", "HI" };

    CarControlOverlayDialog(Context context, SafeClickSoundPlayer sound, Listener listener) {
        super(context);
        this.sound = sound;
        this.listener = listener;
        setBackgroundColor(0xEE050B16);
        setClickable(true);

        LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(24, 14, 24, 14);
        addView(shell, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Header ---
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 10);

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("Live Car Controls");
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f);
        titleCol.addView(title);

        statusLine = new TextView(context);
        statusLine.setText("Direct Gen5W Binder Console — Climate, Doors, Locks, Trunk/Hatch, Windows & Seats");
        statusLine.setTextColor(0xFF94A3B8);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
        statusLine.setSingleLine(true);
        statusLine.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(statusLine);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parkPill = makeBadge(context, "SHIFT: --", 0xFF1E293B, 0xFF64748B);
        header.addView(parkPill, wrapLp(0, 0, 10, 0));

        TextView closeBtn = makeHeaderButton(context, "Close", 0xFF1E293B, 0xFF64748B);
        closeBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                CarControlOverlayDialog.this.sound.playClick(v);
                if (CarControlOverlayDialog.this.listener != null) {
                    CarControlOverlayDialog.this.listener.onCloseRequested();
                }
            }
        });
        header.addView(closeBtn);
        shell.addView(header);

        // --- Two-Column Body ---
        LinearLayout columns = new LinearLayout(context);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        shell.addView(columns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView leftScroll = new ScrollView(context);
        leftScroll.setVerticalScrollBarEnabled(false);
        LinearLayout leftCol = new LinearLayout(context);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftScroll.addView(leftCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.12f);
        leftLp.setMargins(0, 0, 12, 0);
        columns.addView(leftScroll, leftLp);

        ScrollView rightScroll = new ScrollView(context);
        rightScroll.setVerticalScrollBarEnabled(false);
        LinearLayout rightCol = new LinearLayout(context);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightScroll.addView(rightCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        columns.addView(rightScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));

        // ================= LEFT COLUMN: SET AC & CLIMATE =================
        LinearLayout hvacCard = makeSectionCard(context, "SET AC & CLIMATE CONTROL (IDATCService / IVRService)");
        leftCol.addView(hvacCard);

        LinearLayout sysRow = makeButtonRow(context);
        powerBtn = addCommandTile(sysRow, "Power", "climate_toggle", "", 0xFF0EA5E9);
        autoBtn = addCommandTile(sysRow, "AUTO", "climate_auto_toggle", "", 0xFF10B981);
        acBtn = addCommandTile(sysRow, "A/C", "climate_ac_toggle", "", 0xFF38BDF8);
        heatBtn = addCommandTile(sysRow, "HEAT", "climate_heat_toggle", "", 0xFFF97316);
        addStaticActionTile(sysRow, "MAX A/C", "climate_max_ac", "", 0xFF0369A1);
        addStaticActionTile(sysRow, "MAX Def", "climate_max_defrost", "", 0xFFD97706);
        hvacCard.addView(sysRow);

        LinearLayout modeRow = makeButtonRow(context);
        recircBtn = addCommandTile(modeRow, "Recirc", "climate_recirc_toggle", "", 0xFF0EA5E9);
        syncBtn = addCommandTile(modeRow, "SYNC", "climate_sync_toggle", "", 0xFF38BDF8);
        driverOnlyBtn = addCommandTile(modeRow, "Drv Only", "climate_driver_only_toggle", "", 0xFF0EA5E9);
        frontDefrostBtn = addCommandTile(modeRow, "Frnt Def", "climate_front_defrost_toggle", "", 0xFFF59E0B);
        rearDefrostBtn = addCommandTile(modeRow, "Rear Def", "rear_defrost_toggle", "", 0xFFF59E0B);
        wheelHeatBtn = addCommandTile(modeRow, "Wheel", "wheel_heat_toggle", "", 0xFFEF4444);
        hvacCard.addView(modeRow);

        hvacCard.addView(makeSubCaption(context, "Direct Air Vent Mode:"));
        LinearLayout ventRow = makeButtonRow(context);
        String[] ventLabels = { "Face", "Bi-Lvl", "Floor", "Flr+Def", "Defrost" };
        String[] ventArgs = { "1", "2", "3", "4", "5" };
        for (int i = 0; i < ventLabels.length; i++) {
            ToggleTile tile = addCommandTile(ventRow, ventLabels[i], "climate_vent_set", ventArgs[i], 0xFF0284C7);
            ventButtons.add(tile);
        }
        hvacCard.addView(ventRow);

        hvacCard.addView(makeSubCaption(context, "Dual-Zone Target Temp & Blower Steppers:"));
        LinearLayout steppersRow = makeButtonRow(context);
        driverTempValue = addStepperWidget(steppersRow, "Driver", "climate_driver_temp_down", "climate_driver_temp_up");
        passTempValue = addStepperWidget(steppersRow, "Passenger", "climate_passenger_temp_down", "climate_passenger_temp_up");
        fanSpeedValue = addStepperWidget(steppersRow, "Fan Speed", "climate_fan_down", "climate_fan_up");
        hvacCard.addView(steppersRow);

        hvacCard.addView(makeSubCaption(context, "Set AC Quick Temperature (Applies Cabin Target):"));
        LinearLayout quickTempRow = makeButtonRow(context);
        for (int i = 0; i < tempArgsF.length; i++) {
            final int idx = i;
            TextView tv = makeFlexButton(context, tempArgsF[i]);
            tv.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    CarControlOverlayDialog.this.sound.playClick(v);
                    Object tagArg = v.getTag();
                    String arg = tagArg instanceof String ? (String) tagArg : tempArgsF[idx];
                    dispatchCommand("climate_set_temp", arg);
                }
            });
            tv.setTag(tempArgsF[i]);
            addButtonToRow(quickTempRow, tv, i < tempArgsF.length - 1);
            tempPresetButtons.add(new ToggleTile(tv, tempArgsF[i], 0xFF0284C7));
        }
        hvacCard.addView(quickTempRow);

        hvacCard.addView(makeSubCaption(context, "Set AC Direct Blower Fan Level:"));
        LinearLayout quickFanRow = makeButtonRow(context);
        for (int s = 0; s <= 8; s++) {
            String lbl = s == 0 ? "OFF" : String.valueOf(s);
            ToggleTile tile = addCommandTile(quickFanRow, lbl, "climate_fan_set", String.valueOf(s), 0xFF0EA5E9);
            fanStepButtons.add(tile);
        }
        hvacCard.addView(quickFanRow);

        LinearLayout extrasCard = makeSectionCard(context, "CABIN CLIMATE EXTRAS & REAR HVAC");
        leftCol.addView(extrasCard);
        LinearLayout extrasRow = makeButtonRow(context);
        smartVentBtn = addCommandTile(extrasRow, "Smart Vent", "climate_smart_vent_toggle", "", 0xFF14B8A6);
        autoDefogBtn = addCommandTile(extrasRow, "Auto Defog", "climate_auto_defog_toggle", "", 0xFF14B8A6);
        afterBlowBtn = addCommandTile(extrasRow, "After-Blow", "climate_after_blow_toggle", "", 0xFF14B8A6);
        airCleanBtn = addCommandTile(extrasRow, "Air Clean", "climate_air_clean_toggle", "", 0xFF0E7490);
        rearHvacBtn = addCommandTile(extrasRow, "Rear HVAC", "rear_climate_toggle", "", 0xFF0EA5E9);
        rearLockBtn = addCommandTile(extrasRow, "Rear Lock", "rear_climate_lock_toggle", "", 0xFFF59E0B);
        addStaticActionTile(extrasRow, "Rear -", "rear_climate_temp_down", "", 0xFF1D4ED8);
        addStaticActionTile(extrasRow, "Rear +", "rear_climate_temp_up", "", 0xFF1D4ED8);
        extrasCard.addView(extrasRow);

        // ================= RIGHT COLUMN: DOORS, TRUNK, WINDOWS & SEATS =================
        LinearLayout bodyCard = makeSectionCard(context, "LIVE BODY STATUS & DOORS / LOCKS / TRUNK (IBCM / IVR)");
        rightCol.addView(bodyCard);

        LinearLayout statusRow = makeButtonRow(context);
        lockStatusPill = makeBadge(context, "LOCKS: --", 0xFF132238, 0xFF334155);
        doorsStatusPill = makeBadge(context, "DOORS: --", 0xFF132238, 0xFF334155);
        trunkStatusPill = makeBadge(context, "TRUNK: --", 0xFF132238, 0xFF334155);
        hoodStatusPill = makeBadge(context, "HOOD: --", 0xFF132238, 0xFF334155);
        addButtonToRow(statusRow, lockStatusPill, true);
        addButtonToRow(statusRow, doorsStatusPill, true);
        addButtonToRow(statusRow, trunkStatusPill, true);
        addButtonToRow(statusRow, hoodStatusPill, false);
        bodyCard.addView(statusRow);

        LinearLayout lockDoorRow = makeButtonRow(context);
        lockAllBtn = addCommandTile(lockDoorRow, "Lock Doors", "door_lock", "", 0xFF10B981);
        addStaticActionTile(lockDoorRow, "Unlock Doors", "door_unlock", "", 0xFFF59E0B);
        addStaticActionTile(lockDoorRow, "Open All Doors", "door_open_all", "", 0xFF0284C7);
        addStaticActionTile(lockDoorRow, "Close All Doors", "door_close_all", "", 0xFF1E3A5F);
        bodyCard.addView(lockDoorRow);

        LinearLayout trunkRow = makeButtonRow(context);
        addStaticActionTile(trunkRow, "Open Trunk", "trunk_open", "", 0xFF0284C7);
        addStaticActionTile(trunkRow, "Close Trunk", "trunk_close", "", 0xFF1E3A5F);
        trunkToggleBtn = addCommandTile(trunkRow, "Trunk/Hatch", "trunk_toggle", "", 0xFFF59E0B);
        chargeDoorBtn = addCommandTile(trunkRow, "Open Charge", "charge_door_open", "", 0xFF0EA5E9);
        addStaticActionTile(trunkRow, "Close Charge", "charge_door_close", "", 0xFF1E3A5F);
        bodyCard.addView(trunkRow);

        LinearLayout trunkHeightRow = makeButtonRow(context);
        addStaticActionTile(trunkHeightRow, "Hatch Lv1", "trunk_height_set", "1", 0xFF0369A1);
        addStaticActionTile(trunkHeightRow, "Hatch Lv2", "trunk_height_set", "2", 0xFF0369A1);
        addStaticActionTile(trunkHeightRow, "Hatch Lv3", "trunk_height_set", "3", 0xFF0369A1);
        addStaticActionTile(trunkHeightRow, "Hatch Full", "trunk_height_set", "4", 0xFF0369A1);
        addStaticActionTile(trunkHeightRow, "Speed Fast", "trunk_speed_set", "2", 0xFF0E7490);
        bodyCard.addView(trunkHeightRow);

        LinearLayout winCard = makeSectionCard(context, "POWER WINDOWS, SUNROOF & SUNSHADE");
        rightCol.addView(winCard);

        LinearLayout winMasterRow = makeButtonRow(context);
        addStaticActionTile(winMasterRow, "All Down", "window_all_open", "", 0xFF0284C7);
        addStaticActionTile(winMasterRow, "Half 50%", "window_all_half", "", 0xFF0284C7);
        addStaticActionTile(winMasterRow, "Vent 25%", "window_all_vent", "", 0xFF0EA5E9);
        addStaticActionTile(winMasterRow, "All Up", "window_all_close", "", 0xFF1E3A5F);
        addStaticActionTile(winMasterRow, "Roof Open", "sunroof_open", "", 0xFF0369A1);
        addStaticActionTile(winMasterRow, "Roof Close", "sunroof_close", "", 0xFF1E3A5F);
        winCard.addView(winMasterRow);

        LinearLayout winFrontRow = makeButtonRow(context);
        addStaticActionTile(winFrontRow, "FL Down", "window_fl_open", "", 0xFF0369A1);
        addStaticActionTile(winFrontRow, "FL Up", "window_fl_close", "", 0xFF1E3A5F);
        addStaticActionTile(winFrontRow, "FR Down", "window_fr_open", "", 0xFF0369A1);
        addStaticActionTile(winFrontRow, "FR Up", "window_fr_close", "", 0xFF1E3A5F);
        addStaticActionTile(winFrontRow, "Shade Open", "sunshade_open", "", 0xFF0E7490);
        winCard.addView(winFrontRow);

        LinearLayout winRearRow = makeButtonRow(context);
        addStaticActionTile(winRearRow, "RL Down", "window_rl_open", "", 0xFF0369A1);
        addStaticActionTile(winRearRow, "RL Up", "window_rl_close", "", 0xFF1E3A5F);
        addStaticActionTile(winRearRow, "RR Down", "window_rr_open", "", 0xFF0369A1);
        addStaticActionTile(winRearRow, "RR Up", "window_rr_close", "", 0xFF1E3A5F);
        addStaticActionTile(winRearRow, "Shade Close", "sunshade_close", "", 0xFF1E3A5F);
        winCard.addView(winRearRow);

        LinearLayout seatAudioCard = makeSectionCard(context, "SEATS (HEAT / VENT / REAR) · AUDIO & EV BATTERY");
        rightCol.addView(seatAudioCard);

        LinearLayout seatRow = makeButtonRow(context);
        drvSeatHeatBtn = addCommandTile(seatRow, "Drv Heat", "seat_heat_driver_cycle", "", 0xFFEF4444);
        drvSeatVentBtn = addCommandTile(seatRow, "Drv Vent", "seat_vent_driver_cycle", "", 0xFF38BDF8);
        passSeatHeatBtn = addCommandTile(seatRow, "Pass Heat", "seat_heat_passenger_cycle", "", 0xFFEF4444);
        passSeatVentBtn = addCommandTile(seatRow, "Pass Vent", "seat_vent_passenger_cycle", "", 0xFF38BDF8);
        rlSeatHeatBtn = addCommandTile(seatRow, "RL Heat", "seat_heat_rl_cycle", "", 0xFFEF4444);
        rrSeatHeatBtn = addCommandTile(seatRow, "RR Heat", "seat_heat_rr_cycle", "", 0xFFEF4444);
        seatAudioCard.addView(seatRow);

        LinearLayout audioRow = makeButtonRow(context);
        addStaticActionTile(audioRow, "Vol -", "volume_down", "", 0xFF1E3A5F);
        volumeValue = makeBadge(context, "VOL --", 0xFF132238, 0xFF334155);
        addButtonToRow(audioRow, volumeValue, true);
        addStaticActionTile(audioRow, "Vol +", "volume_up", "", 0xFF0284C7);
        muteBtn = addCommandTile(audioRow, "Mute", "volume_mute_toggle", "", 0xFFEF4444);
        precondBtn = addCommandTile(audioRow, "Precond", "precondition_toggle", "", 0xFFF59E0B);
        seatAudioCard.addView(audioRow);
    }

    void update(TelemetryModel.Snapshot s) {
        if (s == null) {
            return;
        }
        String parkRaw = s.getRaw("gear.park");
        if ("--".equals(parkRaw)) {
            setTextIfChanged(parkPill, "SHIFT: --");
            stylePill(parkPill, 0xFF1E293B, 0xFF64748B);
        } else {
            boolean inPark = "1".equals(parkRaw) || "true".equalsIgnoreCase(parkRaw);
            setTextIfChanged(parkPill, inPark ? "PARK" : "DRIVE / GEAR");
            stylePill(parkPill, inPark ? 0xFF064E3B : 0xFF7C2D12, inPark ? 0xFF10B981 : 0xFFF59E0B);
        }

        // Live doors / locks / trunk / hood strip
        boolean locked = "1".equals(s.getRaw("door.lock_state"));
        String lockRaw = s.getRaw("door.lock_state");
        setTextIfChanged(lockStatusPill, "--".equals(lockRaw) ? "LOCKS: --" : (locked ? "LOCKED" : "UNLOCKED"));
        stylePill(lockStatusPill,
                "--".equals(lockRaw) ? 0xFF132238 : (locked ? 0xFF064E3B : 0xFF7C2D12),
                "--".equals(lockRaw) ? 0xFF334155 : (locked ? 0xFF10B981 : 0xFFF59E0B));

        String doorsText = buildOpenDoorsSummary(s);
        boolean anyDoorOpen = "1".equals(s.getRaw("door.any_open"));
        setTextIfChanged(doorsStatusPill, doorsText);
        stylePill(doorsStatusPill,
                anyDoorOpen ? 0xFF7F1D1D : 0xFF132238,
                anyDoorOpen ? 0xFFEF4444 : 0xFF334155);

        String trunkRaw = s.getRaw("trunk.open");
        boolean trunkOpen = "1".equals(trunkRaw);
        setTextIfChanged(trunkStatusPill, "--".equals(trunkRaw) ? "TRUNK: --" : (trunkOpen ? "TRUNK OPEN" : "TRUNK CLOSED"));
        stylePill(trunkStatusPill,
                trunkOpen ? 0xFF78350F : 0xFF132238,
                trunkOpen ? 0xFFF59E0B : 0xFF334155);

        String hoodRaw = s.getRaw("hood.open");
        boolean hoodOpen = "1".equals(hoodRaw);
        setTextIfChanged(hoodStatusPill, "--".equals(hoodRaw) ? "HOOD: --" : (hoodOpen ? "HOOD OPEN" : "HOOD CLOSED"));
        stylePill(hoodStatusPill,
                hoodOpen ? 0xFF78350F : 0xFF132238,
                hoodOpen ? 0xFFF59E0B : 0xFF334155);

        // Climate toggles
        setTileState(powerBtn, "1".equals(s.getRaw("climate.on")), "ON", "OFF");
        setTileState(autoBtn, "1".equals(s.getRaw("climate.auto")), "ON", "");
        setTileState(acBtn, "1".equals(s.getRaw("climate.ac")), "ON", "");
        setTileState(heatBtn, "1".equals(s.getRaw("climate.heat")), "ON", "");
        setTileState(recircBtn, "1".equals(s.getRaw("climate.recirc")), "ON", "Fresh");
        setTileState(syncBtn, "1".equals(s.getRaw("climate.sync")), "ON", "");
        setTileState(driverOnlyBtn, "1".equals(s.getRaw("climate.driver_only")), "ON", "");
        setTileState(frontDefrostBtn, "1".equals(s.getRaw("climate.front_defrost")), "ON", "");
        setTileState(rearDefrostBtn, "1".equals(s.getRaw("defogger")), "ON", "");
        setTileState(wheelHeatBtn, "1".equals(s.getRaw("wheel_heat")), "ON", "");
        setTileState(smartVentBtn, "1".equals(s.getRaw("climate.smart_vent")), "ON", "");
        setTileState(autoDefogBtn, "1".equals(s.getRaw("climate.auto_defog")), "ON", "");
        setTileState(afterBlowBtn, "1".equals(s.getRaw("climate.after_blow")), "ON", "");
        setTileState(airCleanBtn, "1".equals(s.getRaw("climate.air_clean")), "ON", "");
        setTileState(rearHvacBtn, "1".equals(s.getRaw("climate.rear_on")), "ON", "");
        setTileState(rearLockBtn, "1".equals(s.getRaw("climate.rear_lock")), "ON", "");

        boolean celsius = "C".equals(s.getRaw("climate.temp_unit"));
        String unit = celsius ? "\u00B0C" : "\u00B0F";
        String drvTemp = formatClimateTemp(s.getRaw("climate.driver_temp"), unit);
        String passTemp = formatClimateTemp(s.getRaw("climate.passenger_temp"), unit);
        setTextIfChanged(driverTempValue, drvTemp);
        setTextIfChanged(passTempValue, passTemp);

        // Sync quick temp preset labels with car F/C unit
        String[] activeArgs = celsius ? tempArgsC : tempArgsF;
        for (int i = 0; i < tempPresetButtons.size() && i < activeArgs.length; i++) {
            ToggleTile tile = tempPresetButtons.get(i);
            String rawArg = activeArgs[i];
            String disp = ("LO".equals(rawArg) || "HI".equals(rawArg)) ? rawArg : (rawArg + "\u00B0");
            if (!rawArg.equals(tile.view.getTag())) {
                tile.view.setTag(rawArg);
            }
            boolean match = disp.equalsIgnoreCase(drvTemp.replace(".0", "").replace(unit, "\u00B0"))
                    || ("LO".equals(rawArg) && "LO".equalsIgnoreCase(drvTemp))
                    || ("HI".equals(rawArg) && "HI".equalsIgnoreCase(drvTemp));
            setTextIfChanged(tile.view, disp);
            styleButton(tile.view, match, tile.onColor);
        }

        // Direct vent highlight
        String ventRaw = stripZero(s.getRaw("climate.vent_mode"));
        int ventIdx = "1".equals(ventRaw) ? 0
                : ("2".equals(ventRaw) ? 1
                : ("3".equals(ventRaw) ? 2
                : ("4".equals(ventRaw) ? 3
                : (("5".equals(ventRaw) || "10".equals(ventRaw)) ? 4 : -1))));
        for (int i = 0; i < ventButtons.size(); i++) {
            ToggleTile t = ventButtons.get(i);
            styleButton(t.view, i == ventIdx, t.onColor);
        }

        // Fan stepper + direct fan highlights
        String fanRaw = stripZero(s.getRaw("climate.fan_speed"));
        setTextIfChanged(fanSpeedValue, "--".equals(fanRaw) ? "--" : ("0".equals(fanRaw) ? "OFF" : ("LVL " + fanRaw)));
        int fanLevel = parseIntSafe(fanRaw, -1);
        for (int i = 0; i < fanStepButtons.size(); i++) {
            ToggleTile t = fanStepButtons.get(i);
            styleButton(t.view, i == fanLevel, t.onColor);
        }

        // Right column toggles & seats
        setTileState(lockAllBtn, locked, "ON", "");
        setTileState(trunkToggleBtn, trunkOpen, "OPEN", "CLOSED");
        setTileState(chargeDoorBtn, "1".equals(s.getRaw("charge_door.open")), "OPEN", "");
        setLevelState(drvSeatHeatBtn, s.getRaw("seat_heat.driver"), "Heat");
        setLevelState(drvSeatVentBtn, s.getRaw("seat_vent.driver"), "Cool");
        setLevelState(passSeatHeatBtn, s.getRaw("seat_heat.passenger"), "Heat");
        setLevelState(passSeatVentBtn, s.getRaw("seat_vent.passenger"), "Cool");
        setLevelState(rlSeatHeatBtn, s.getRaw("seat_heat.rear_left"), "Heat");
        setLevelState(rrSeatHeatBtn, s.getRaw("seat_heat.rear_right"), "Heat");

        String volRaw = stripZero(s.getRaw("audio.volume"));
        boolean muted = "1".equals(s.getRaw("audio.muted"));
        setTextIfChanged(volumeValue, muted ? "MUTED" : ("--".equals(volRaw) ? "VOL --" : ("VOL " + volRaw)));
        setTileState(muteBtn, muted, "ON", "");
        setTileState(precondBtn, "1".equals(s.getRaw("hv.conditioning_active")), "ACTIVE", "");
    }

    private static String buildOpenDoorsSummary(TelemetryModel.Snapshot s) {
        String any = s.getRaw("door.any_open");
        if ("--".equals(any)) {
            return "DOORS: --";
        }
        if (!"1".equals(any)) {
            return "ALL DOORS CLOSED";
        }
        StringBuilder sb = new StringBuilder("OPEN:");
        if ("1".equals(s.getRaw("door.fl"))) sb.append(" FL");
        if ("1".equals(s.getRaw("door.fr"))) sb.append(" FR");
        if ("1".equals(s.getRaw("door.rl"))) sb.append(" RL");
        if ("1".equals(s.getRaw("door.rr"))) sb.append(" RR");
        return sb.length() == 5 ? "DOOR OPEN" : sb.toString();
    }

    private void dispatchCommand(final String cmd, final String arg) {
        final String cleanArg = arg == null ? "" : arg;
        statusLine.setText("Sending: " + cmd + (cleanArg.isEmpty() ? "" : (" (" + cleanArg + ")")) + "...");
        SwitcherClient.sendCarCommandAsync(cmd, cleanArg, new SwitcherClient.SimpleCallback() {
            @Override
            public void onResult(boolean ok, String message) {
                if (message != null && !message.isEmpty()) {
                    statusLine.setText((ok ? "OK: " : "Refused: ") + message);
                    if (!ok) {
                        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    statusLine.setText(ok ? ("Executed " + cmd) : ("Failed: " + cmd));
                }
                if (listener != null) {
                    listener.onCommandDispatched();
                }
            }
        });
    }

    private LinearLayout makeSectionCard(Context c, String titleText) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(12, 10, 12, 10);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_CARD);
        bg.setCornerRadius(12f);
        bg.setStroke(2, BORDER_CARD);
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 10);
        card.setLayoutParams(lp);

        TextView label = new TextView(c);
        label.setText(titleText);
        label.setTextColor(0xFF7DD3FC);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f);
        label.setPadding(2, 0, 0, 6);
        card.addView(label);
        return card;
    }

    private static TextView makeSubCaption(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(0xFF94A3B8);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f);
        tv.setPadding(2, 2, 0, 4);
        return tv;
    }

    private static LinearLayout makeButtonRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 7);
        row.setLayoutParams(lp);
        return row;
    }

    private ToggleTile addCommandTile(LinearLayout row, String label, final String cmd, final String arg, int onColor) {
        TextView btn = makeFlexButton(row.getContext(), label);
        btn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                CarControlOverlayDialog.this.sound.playClick(v);
                dispatchCommand(cmd, arg);
            }
        });
        addButtonToRow(row, btn, true);
        normalizeRowMargins(row);
        return new ToggleTile(btn, label, onColor);
    }

    private void addStaticActionTile(LinearLayout row, String label, final String cmd, final String arg, final int accentBorder) {
        TextView btn = makeFlexButton(row.getContext(), label);
        stylePill(btn, BTN_IDLE_BG, accentBorder);
        btn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                CarControlOverlayDialog.this.sound.playClick(v);
                dispatchCommand(cmd, arg);
            }
        });
        addButtonToRow(row, btn, true);
        normalizeRowMargins(row);
    }

    private TextView addStepperWidget(LinearLayout row, String caption, final String downCmd, final String upCmd) {
        Context c = row.getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(6, 5, 6, 5);
        stylePill(box, 0xFF101C33, 0xFF28436B);

        TextView cap = new TextView(c);
        cap.setText(caption);
        cap.setTextColor(0xFF94A3B8);
        cap.setTextSize(TypedValue.COMPLEX_UNIT_PX, 10f);
        cap.setGravity(Gravity.CENTER);
        box.addView(cap);

        LinearLayout inner = new LinearLayout(c);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);

        TextView minus = makeMiniStepBtn(c, "-", downCmd);
        TextView val = new TextView(c);
        val.setText("--");
        val.setTextColor(0xFFFFFFFF);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        val.setGravity(Gravity.CENTER);
        val.setSingleLine(true);
        TextView plus = makeMiniStepBtn(c, "+", upCmd);

        inner.addView(minus, new LinearLayout.LayoutParams(40, 34));
        inner.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        inner.addView(plus, new LinearLayout.LayoutParams(40, 34));
        box.addView(inner);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, 6, 0);
        row.addView(box, lp);
        normalizeRowMargins(row);
        return val;
    }

    private TextView makeMiniStepBtn(Context c, String label, final String cmd) {
        TextView btn = new TextView(c);
        btn.setText(label);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f);
        btn.setGravity(Gravity.CENTER);
        stylePill(btn, 0xFF1E3A5F, 0xFF38BDF8);
        btn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                CarControlOverlayDialog.this.sound.playClick(v);
                dispatchCommand(cmd, "");
            }
        });
        return btn;
    }

    private static TextView makeFlexButton(Context c, String text) {
        TextView btn = new TextView(c);
        btn.setText(text);
        btn.setTextColor(0xFFF1F5F9);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setPadding(6, 10, 6, 10);
        styleButton(btn, false, 0xFF0EA5E9);
        return btn;
    }

    private static TextView makeHeaderButton(Context c, String text, int fillColor, int strokeColor) {
        TextView btn = new TextView(c);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f);
        btn.setPadding(18, 9, 18, 9);
        stylePill(btn, fillColor, strokeColor);
        return btn;
    }

    private static TextView makeBadge(Context c, String text, int fillColor, int strokeColor) {
        TextView badge = new TextView(c);
        badge.setText(text);
        badge.setTextColor(0xFFE2E8F0);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setEllipsize(TextUtils.TruncateAt.END);
        badge.setPadding(8, 9, 8, 9);
        stylePill(badge, fillColor, strokeColor);
        return badge;
    }

    private static void addButtonToRow(LinearLayout row, View child, boolean rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (rightMargin) {
            lp.setMargins(0, 0, 6, 0);
        }
        row.addView(child, lp);
    }

    private static void normalizeRowMargins(LinearLayout row) {
        int count = row.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = row.getChildAt(i);
            if (child.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
                lp.setMargins(0, 0, (i < count - 1) ? 6 : 0, 0);
                child.setLayoutParams(lp);
            }
        }
    }

    private static LinearLayout.LayoutParams wrapLp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private static void setTileState(ToggleTile tile, boolean active, String onWord, String offWord) {
        tile.active = active;
        if (active && !onWord.isEmpty()) {
            setTextIfChanged(tile.view, tile.baseLabel + " · " + onWord);
        } else if (!active && !offWord.isEmpty()) {
            setTextIfChanged(tile.view, tile.baseLabel + " · " + offWord);
        } else {
            setTextIfChanged(tile.view, tile.baseLabel);
        }
        styleButton(tile.view, active, tile.onColor);
    }

    private static void setLevelState(ToggleTile tile, String raw, String verb) {
        String clean = stripZero(raw);
        int lvl = parseIntSafe(clean, 0);
        tile.active = lvl > 0;
        if (lvl > 0) {
            setTextIfChanged(tile.view, tile.baseLabel + " · " + lvl);
        } else {
            setTextIfChanged(tile.view, tile.baseLabel);
        }
        styleButton(tile.view, lvl > 0, tile.onColor);
    }

    private static void setTextIfChanged(TextView tv, String text) {
        if (tv == null) {
            return;
        }
        String next = text == null ? "" : text;
        CharSequence cur = tv.getText();
        if (cur != null && next.contentEquals(cur)) {
            return;
        }
        tv.setText(next);
    }

    private static void styleButton(TextView view, boolean active, int accentColor) {
        if (active) {
            int darkFill = (0x55 << 24) | (accentColor & 0x00FFFFFF);
            stylePill(view, darkFill, accentColor);
            if (view.getCurrentTextColor() != 0xFFFFFFFF) {
                view.setTextColor(0xFFFFFFFF);
            }
        } else {
            stylePill(view, BTN_IDLE_BG, BTN_IDLE_BORDER);
            if (view.getCurrentTextColor() != 0xFFCBD5E1) {
                view.setTextColor(0xFFCBD5E1);
            }
        }
    }

    private static final class PillDrawable extends GradientDrawable {
        int fillColor;
        int strokeColor;
    }

    private static void stylePill(View v, int fillColor, int strokeColor) {
        if (v.getBackground() instanceof PillDrawable) {
            PillDrawable existing = (PillDrawable) v.getBackground();
            if (existing.fillColor == fillColor && existing.strokeColor == strokeColor) {
                return;
            }
            existing.fillColor = fillColor;
            existing.strokeColor = strokeColor;
            existing.setColor(fillColor);
            existing.setStroke(2, strokeColor);
            return;
        }
        PillDrawable gd = new PillDrawable();
        gd.fillColor = fillColor;
        gd.strokeColor = strokeColor;
        gd.setColor(fillColor);
        gd.setCornerRadius(8f);
        gd.setStroke(2, strokeColor);
        v.setBackground(gd);
    }

    private static String formatClimateTemp(String raw, String unit) {
        if (raw == null || raw.isEmpty() || "--".equals(raw)) {
            return "--";
        }
        if ("LO".equalsIgnoreCase(raw) || "HI".equalsIgnoreCase(raw) || "OFF".equalsIgnoreCase(raw)) {
            return raw.toUpperCase();
        }
        try {
            float v = Float.parseFloat(raw);
            if ("\u00B0F".equals(unit)) {
                if (v < 62.0f) return "LO";
                if (v > 82.0f) return "HI";
                return Math.round(v) + unit;
            }
            if (v < 17.0f) return "LO";
            if (v > 27.0f) return "HI";
            String clean = Math.abs(v - Math.round(v)) < 0.05f ? String.valueOf(Math.round(v)) : String.valueOf(v);
            return clean + unit;
        } catch (NumberFormatException ignored) {
            return raw + unit;
        }
    }

    private static String stripZero(String r) {
        if (r == null) return "--";
        return r.endsWith(".0") ? r.substring(0, r.length() - 2) : r;
    }

    private static int parseIntSafe(String raw, int fallback) {
        try {
            return Integer.parseInt(stripZero(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
