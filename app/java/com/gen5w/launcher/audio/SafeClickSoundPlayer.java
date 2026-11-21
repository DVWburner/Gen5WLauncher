package com.gen5w.launcher.audio;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;
import android.view.SoundEffectConstants;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Gen5W-safe UI click feedback.
 * Never requests AudioFocus (preventing Radio/SXM/CarPlay interruption) and automatically
 * mutes during Bluetooth calls, ringtones, or voice recognition (VR) communication modes.
 */
public final class SafeClickSoundPlayer {
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_SOFT = "soft";
    public static final String MODE_OFF = "off";

    private final Context appContext;
    private final AudioManager audioManager;
    private SoundPool soundPool;
    private int softClickSoundId = 0;
    private boolean softReady = false;
    private volatile String mode = MODE_SYSTEM;

    public SafeClickSoundPlayer(Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) this.appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String nextMode) {
        if (MODE_SOFT.equals(nextMode) || MODE_OFF.equals(nextMode)) {
            this.mode = nextMode;
        } else {
            this.mode = MODE_SYSTEM;
        }
        if (MODE_SOFT.equals(this.mode) && soundPool == null) {
            initSoftSynthSound(appContext);
        }
    }

    public String cycleMode() {
        if (MODE_SYSTEM.equals(mode)) {
            mode = MODE_SOFT;
            if (soundPool == null) {
                initSoftSynthSound(appContext);
            }
        } else if (MODE_SOFT.equals(mode)) {
            mode = MODE_OFF;
        } else {
            mode = MODE_SYSTEM;
        }
        return mode;
    }

    public String getModeDisplayLabel() {
        if (MODE_SOFT.equals(mode)) {
            return "Soft Synth";
        }
        if (MODE_OFF.equals(mode)) {
            return "Muted";
        }
        return "System";
    }

    public void playClick(View anchorView) {
        if (MODE_OFF.equals(mode) || isCallOrVrActive()) {
            return;
        }
        if (MODE_SOFT.equals(mode) && soundPool != null && softReady && softClickSoundId != 0) {
            soundPool.play(softClickSoundId, 0.35f, 0.35f, 1, 0, 1.0f);
            return;
        }
        if (anchorView != null) {
            anchorView.playSoundEffect(SoundEffectConstants.CLICK);
        } else if (audioManager != null) {
            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
        }
    }

    public void release() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        softReady = false;
    }

    private boolean isCallOrVrActive() {
        if (audioManager == null) {
            return false;
        }
        try {
            int m = audioManager.getMode();
            return m == AudioManager.MODE_IN_CALL
                    || m == AudioManager.MODE_IN_COMMUNICATION
                    || m == AudioManager.MODE_RINGTONE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void initSoftSynthSound(Context context) {
        try {
            File wav = new File(context.getCacheDir(), "gen5w_soft_click.wav");
            if (!wav.isFile()) {
                writeSoftClickWav(wav);
            }
            soundPool = new SoundPool(2, AudioManager.STREAM_MUSIC, 0);
            soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override
                public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                    softReady = (status == 0);
                }
            });
            softClickSoundId = soundPool.load(wav.getAbsolutePath(), 1);
        } catch (Throwable ignored) {
            softReady = false;
        }
    }

    /** Generates a clean 22ms exponential-decay 880Hz/1320Hz WAV blip in cache dir. */
    private static void writeSoftClickWav(File outFile) throws IOException {
        final int sampleRate = 22050;
        final int samples = (sampleRate * 22) / 1000;
        final int dataBytes = samples * 2;
        byte[] buf = new byte[44 + dataBytes];

        // RIFF / WAVE PCM header
        buf[0] = 'R'; buf[1] = 'I'; buf[2] = 'F'; buf[3] = 'F';
        putIntLE(buf, 4, 36 + dataBytes);
        buf[8] = 'W'; buf[9] = 'A'; buf[10] = 'V'; buf[11] = 'E';
        buf[12] = 'f'; buf[13] = 'm'; buf[14] = 't'; buf[15] = ' ';
        putIntLE(buf, 16, 16);
        putShortLE(buf, 20, 1); // PCM
        putShortLE(buf, 22, 1); // mono
        putIntLE(buf, 24, sampleRate);
        putIntLE(buf, 28, sampleRate * 2);
        putShortLE(buf, 32, 2);
        putShortLE(buf, 34, 16);
        buf[36] = 'd'; buf[37] = 'a'; buf[38] = 't'; buf[39] = 'a';
        putIntLE(buf, 40, dataBytes);

        for (int i = 0; i < samples; i++) {
            double t = (double) i / (double) sampleRate;
            double env = Math.exp(-t * 160.0) * (1.0 - Math.exp(-t * 1400.0));
            double wave = Math.sin(2.0 * Math.PI * 860.0 * t) * 0.65
                    + Math.sin(2.0 * Math.PI * 1380.0 * t) * 0.35;
            short s = (short) Math.max(-32767, Math.min(32767, (int) (wave * env * 14000.0)));
            putShortLE(buf, 44 + i * 2, s);
        }

        File tmp = new File(outFile.getParentFile(), outFile.getName() + "." + System.nanoTime() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(buf);
            fos.flush();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(outFile)) {
            FileOutputStream direct = new FileOutputStream(outFile);
            try {
                direct.write(buf);
                direct.flush();
            } finally {
                direct.close();
            }
            tmp.delete();
        }
    }

    private static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static void putShortLE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
    }
}
