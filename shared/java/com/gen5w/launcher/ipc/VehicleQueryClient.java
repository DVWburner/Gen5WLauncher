package com.gen5w.launcher.ipc;

import hmgapi.client.HmgApi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Queries HMG-API vehicle readings while respecting hmgapid's MAX_ARGS=16 protocol cap
 * ("vehicle" + at most 14 reading keys per IPC request) and isolating single unsupported keys.
 */
public final class VehicleQueryClient {
    public static final int MAX_KEYS_PER_CALL = 14;
    public static final int CALL_TIMEOUT_MS = 5000;
    public static final long REJECT_QUARANTINE_MS = 60_000L;
    private static final int MAX_PARALLEL_CHUNKS = 4;

    public static final String[] CORE_KEYS = new String[] {
            "hv.soc", "battery12v.soc", "charging", "hv.ready",
            "gear.park", "speed", "outside_temp_f", "outside_temp",
            "driving.restricted", "odometer"
    };

    public static final class BatchResult {
        public final Map<String, String> values;
        public final boolean anySuccess;
        public final String errorNote;

        BatchResult(Map<String, String> values, boolean anySuccess, String errorNote) {
            this.values = values;
            this.anySuccess = anySuccess;
            this.errorNote = errorNote;
        }
    }

    private final Map<String, Long> quarantinedUntilMs = new ConcurrentHashMap<String, Long>();

    /** Validates hmgapid's reading-name grammar ([a-z0-9._]{1,40}). */
    public static boolean isValidKey(String key) {
        if (key == null) return false;
        int len = key.length();
        if (len == 0 || len > 40) return false;
        for (int i = 0; i < len; i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    public void clearQuarantine() {
        quarantinedUntilMs.clear();
    }

    public boolean hasActiveQuarantinedKeys() {
        if (quarantinedUntilMs.isEmpty()) return false;
        long now = System.currentTimeMillis();
        for (Long until : quarantinedUntilMs.values()) {
            if (until != null && now < until.longValue()) {
                return true;
            }
        }
        return false;
    }

    public BatchResult query(Collection<String> requestedKeys) {
        long now = System.currentTimeMillis();
        List<String> active = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        if (requestedKeys != null) {
            for (String raw : requestedKeys) {
                if (raw == null) continue;
                String k = raw.trim();
                if (!isValidKey(k) || !seen.add(k)) continue;
                Long until = quarantinedUntilMs.get(k);
                if (until != null) {
                    if (now < until.longValue()) continue;
                    quarantinedUntilMs.remove(k);
                }
                active.add(k);
            }
        }

        if (active.isEmpty()) {
            return new BatchResult(new LinkedHashMap<String, String>(), false, "");
        }

        final List<List<String>> chunks = chunkKeys(active, MAX_KEYS_PER_CALL);
        final Map<String, String> merged = new LinkedHashMap<String, String>();
        final StringBuilder errNote = new StringBuilder();

        if (chunks.size() == 1) {
            boolean ok = queryChunkWithIsolation(chunks.get(0), merged, errNote);
            return new BatchResult(merged, ok && !merged.isEmpty(), errNote.toString());
        }

        final boolean[] anyOk = new boolean[] { false };
        final Object lock = new Object();
        for (int base = 0; base < chunks.size(); base += MAX_PARALLEL_CHUNKS) {
            int end = Math.min(chunks.size(), base + MAX_PARALLEL_CHUNKS);
            List<Thread> workers = new ArrayList<Thread>(end - base);
            for (int i = base; i < end; i++) {
                final List<String> chunk = chunks.get(i);
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Map<String, String> localVals = new LinkedHashMap<String, String>();
                        StringBuilder localErr = new StringBuilder();
                        boolean ok = queryChunkWithIsolation(chunk, localVals, localErr);
                        synchronized (lock) {
                            merged.putAll(localVals);
                            if (ok && !localVals.isEmpty()) {
                                anyOk[0] = true;
                            }
                            if (localErr.length() > 0 && errNote.length() < 160) {
                                if (errNote.length() > 0) errNote.append("; ");
                                errNote.append(localErr);
                            }
                        }
                    }
                }, "hmgapi-veh-" + i);
                t.setDaemon(true);
                t.start();
                workers.add(t);
            }
            for (Thread t : workers) {
                try {
                    t.join(CALL_TIMEOUT_MS + 1500L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new BatchResult(merged, anyOk[0] && !merged.isEmpty(), errNote.toString());
    }

    private boolean queryChunkWithIsolation(List<String> chunk, Map<String, String> out, StringBuilder errNote) {
        HmgApi.Result r = invokeVehicle(chunk, errNote);
        if (r == null) {
            return false;
        }
        Map<String, String> parsed = parseVehicleLines(r.text);
        if ((r.code == 0 || r.code == 1) && !parsed.isEmpty()) {
            out.putAll(parsed);
            return true;
        }
        if (chunk.size() == 1) {
            if (r.code == 13 || r.code == 2) {
                quarantineKey(chunk.get(0));
            }
            noteResultError(r, errNote);
            return false;
        }
        if (r.code == 13 || r.code == 2) {
            // One undeclared/unsupported reading fails the entire hmgapid batch; probe individually.
            boolean gotAny = false;
            for (String key : chunk) {
                List<String> single = new ArrayList<String>(1);
                single.add(key);
                HmgApi.Result kr = invokeVehicle(single, errNote);
                if (kr == null) continue;
                Map<String, String> singleParsed = parseVehicleLines(kr.text);
                if ((kr.code == 0 || kr.code == 1) && !singleParsed.isEmpty()) {
                    out.putAll(singleParsed);
                    gotAny = true;
                } else if (kr.code == 13 || kr.code == 2) {
                    quarantineKey(key);
                }
            }
            if (!gotAny) {
                noteResultError(r, errNote);
            }
            return gotAny;
        }
        noteResultError(r, errNote);
        return false;
    }

    private static HmgApi.Result invokeVehicle(List<String> keys, StringBuilder errNote) {
        try {
            HmgApi api = HmgApi.connect().timeout(CALL_TIMEOUT_MS);
            String[] args = new String[keys.size() + 1];
            args[0] = "vehicle";
            for (int i = 0; i < keys.size(); i++) {
                args[i + 1] = keys.get(i);
            }
            return api.call(args);
        } catch (Throwable t) {
            if (errNote.length() < 120) {
                if (errNote.length() > 0) errNote.append("; ");
                errNote.append("err=").append(t.getMessage());
            }
            return null;
        }
    }

    private void quarantineKey(String key) {
        quarantinedUntilMs.put(key, System.currentTimeMillis() + REJECT_QUARANTINE_MS);
    }

    private static void noteResultError(HmgApi.Result r, StringBuilder errNote) {
        if (errNote.length() >= 120) return;
        if (errNote.length() > 0) errNote.append("; ");
        errNote.append("code=").append(r.code);
        if (r.text != null && !r.text.trim().isEmpty()) {
            errNote.append(" (").append(firstLine(r.text)).append(')');
        }
    }

    private static List<List<String>> chunkKeys(List<String> keys, int maxChunkSize) {
        List<List<String>> out = new ArrayList<List<String>>();
        for (int i = 0; i < keys.size(); i += maxChunkSize) {
            int end = Math.min(keys.size(), i + maxChunkSize);
            out.add(new ArrayList<String>(keys.subList(i, end)));
        }
        return out;
    }

    public static Map<String, String> parseVehicleLines(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        parseVehicleLines(text, out);
        return out;
    }

    public static void parseVehicleLines(String text, Map<String, String> out) {
        if (text == null || text.isEmpty()) return;
        String[] lines = text.split("\n");
        for (String line : lines) {
            int sp = line.indexOf(' ');
            if (sp > 0) {
                String k = line.substring(0, sp).trim();
                String v = line.substring(sp + 1).trim();
                if (!k.isEmpty()) {
                    out.put(k, v);
                }
            }
        }
    }

    private static String firstLine(String s) {
        String t = s.trim();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        return t.length() > 48 ? t.substring(0, 48) : t;
    }
}
