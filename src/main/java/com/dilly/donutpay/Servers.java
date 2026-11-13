package com.dilly.donutpay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-server payment stats (saved in servers.json) plus which board the leaderboard / stats screen is
 * showing. The pseudo-board {@link #ALL} is the old global stats.json, so nothing from earlier versions is lost.
 */
public final class Servers {
    private Servers() {}

    public static final String ALL = "*";
    public static final String LOCAL = "local";

    private static final class Data {
        Map<String, Stats> stats = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
    }

    private static Data data = new Data();
    private static String view = ALL;
    private static final Stats EMPTY = new Stats();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---------------------------------------------------------------- identity
    private static String norm(String address) {
        String a = address == null ? "" : address.toLowerCase(Locale.ROOT).trim();
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        return a;
    }

    private static String keyOf(ServerInfo e) { return e == null ? LOCAL : norm(e.address); }

    public static String currentKey() { return keyOf(MinecraftClient.getInstance().getCurrentServerEntry()); }

    // ---------------------------------------------------------------- recording
    /** Called for every counted payment, on the server the player is connected to right now. */
    public static synchronized void record(String from, long amount) {
        ServerInfo e = MinecraftClient.getInstance().getCurrentServerEntry();
        String key = keyOf(e);
        data.stats.computeIfAbsent(key, k -> new Stats()).apply(from, amount);
        if (e != null && e.name != null && !e.name.isBlank()) data.labels.put(key, e.name.trim());
        save();
    }

    // ---------------------------------------------------------------- lookup
    /** All boards: "All servers" first, then every server with payments (biggest first), the current server, and
     *  any /donutpay addserver entries that have no payments yet. */
    public static synchronized List<String> keys() {
        List<String> real = new ArrayList<>(data.stats.keySet());
        if (MinecraftClient.getInstance().getCurrentServerEntry() != null && !real.contains(currentKey())) real.add(currentKey());
        for (String ex : Config.get().extraServers) {
            String e = norm(ex);
            if (e.isEmpty()) continue;
            boolean covered = false;
            for (String k : real) if (k.contains(e) || e.contains(k)) covered = true;
            if (!covered) real.add(e);
        }
        real.sort((a, b) -> {
            int c = Long.compare(statsFor(b).lifetimeTotal, statsFor(a).lifetimeTotal);
            return c != 0 ? c : a.compareTo(b);
        });
        List<String> out = new ArrayList<>();
        out.add(ALL);
        out.addAll(real);
        return out;
    }

    public static synchronized Stats statsFor(String key) {
        if (ALL.equals(key)) return Stats.get();
        return data.stats.getOrDefault(key, EMPTY);
    }

    public static synchronized String label(String key) {
        if (ALL.equals(key)) return "All servers";
        if (LOCAL.equals(key)) return "Singleplayer / LAN";
        return data.labels.getOrDefault(key, key);
    }

    // ---------------------------------------------------------------- selected board
    public static Stats viewStats() { return statsFor(view); }
    public static String viewLabel() { return label(view); }
    public static int viewIndex() { return Math.max(0, keys().indexOf(view)) + 1; }
    public static int count() { return keys().size(); }

    public static void cycleView(int dir) {
        List<String> k = keys();
        int i = Math.max(0, k.indexOf(view));
        view = k.get(Math.floorMod(i + dir, k.size()));
    }

    /** Point the board at the server you are connected to (stays put in singleplayer). */
    public static void viewCurrent() {
        if (MinecraftClient.getInstance().getCurrentServerEntry() != null) view = currentKey();
    }

    // ---------------------------------------------------------------- housekeeping
    public static synchronized void resetSessions() {
        for (Stats s : data.stats.values()) s.resetSession();
    }

    public static synchronized void resetAll() {
        data = new Data();
        view = ALL;
        save();
    }

    /** Copies the old all-servers history onto the server you are on (only if that server has nothing yet). */
    public static synchronized String claimLegacy() {
        ServerInfo e = MinecraftClient.getInstance().getCurrentServerEntry();
        if (e == null) return "Join the multiplayer server you want to assign your history to first.";
        String key = keyOf(e);
        Stats existing = data.stats.get(key);
        if (existing != null && existing.lifetimeCount > 0) return label(key) + " already has its own history - nothing changed.";
        Stats copy = GSON.fromJson(GSON.toJson(Stats.get()), Stats.class);
        if (copy == null || copy.lifetimeCount == 0) return "There is no old history to assign.";
        data.stats.put(key, copy);
        if (e.name != null && !e.name.isBlank()) data.labels.put(key, e.name.trim());
        view = key;
        save();
        return "Assigned " + copy.lifetimeCount + " old payments (" + Stats.fmt(copy.lifetimeTotal) + ") to " + label(key) + ".";
    }

    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        String cur = currentKey();
        for (String k : keys()) {
            Stats s = statsFor(k);
            out.add("[DonutPay] " + label(k) + ": " + Stats.fmt(s.lifetimeTotal) + " from " + s.lifetimeCount + " payments"
                + (k.equals(cur) ? "  <- you are here" : ""));
        }
        return out;
    }

    // ---------------------------------------------------------------- persistence
    public static synchronized void load() {
        Path f = Config.dir().resolve("servers.json");
        try (Reader r = Files.newBufferedReader(f)) {
            Data d = GSON.fromJson(r, Data.class);
            if (d != null) {
                if (d.stats == null) d.stats = new LinkedHashMap<>();
                if (d.labels == null) d.labels = new HashMap<>();
                data = d;
            }
        } catch (Exception ignored) {
            data = new Data();
        }
    }

    public static synchronized void save() {
        try (Writer w = Files.newBufferedWriter(Config.dir().resolve("servers.json"))) {
            GSON.toJson(data, w);
        } catch (Exception e) {
            DonutPayClient.LOG.error("Could not save servers.json", e);
        }
    }
}
