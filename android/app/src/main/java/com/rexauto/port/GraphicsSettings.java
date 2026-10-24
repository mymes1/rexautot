package com.rexauto.port;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Graphics / performance options exposed by the launcher. Each maps 1:1 to a
 * ReXGlue runtime cvar; the native side reads settings.txt and hands every
 * line over as --key=value before the runtime is constructed.
 */
public final class GraphicsSettings {
    private final SharedPreferences prefs;
    private final int ramMb;

    public GraphicsSettings(Context ctx) {
        prefs = ctx.getSharedPreferences("graphics", Context.MODE_PRIVATE);
        ramMb = queryTotalRamMb(ctx);
    }

    /** Total physical RAM; a 4 GB tablet must not be configured like a 12 GB one. */
    private static int queryTotalRamMb(Context ctx) {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return (int) (mi.totalMem >> 20);
            }
        } catch (Throwable ignored) {
        }
        return 4096;
    }

    private int totalRamMb() {
        return ramMb > 0 ? ramMb : 4096;
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    // --- knobs (defaults tuned for a mid-range Adreno/Mali phone) -----------
    /** Internal render scale: 1 = native 720p (fastest), 2 = 2x, 3 = 3x. */
    public int resolutionScale() { return prefs.getInt("resolution_scale", 1); }
    public void setResolutionScale(int v) { prefs.edit().putInt("resolution_scale", Math.max(1, Math.min(3, v))).apply(); }

    public boolean vsync() { return prefs.getBoolean("vsync", true); }
    public void setVsync(boolean v) { prefs.edit().putBoolean("vsync", v).apply(); }

    /** bilinear (cheap) | cas (sharpen) | fsr (upscale) */
    public String presentEffect() { return prefs.getString("present_effect", "bilinear"); }
    public void setPresentEffect(String v) { prefs.edit().putString("present_effect", v).apply(); }

    /** Guest video mode (what the game thinks the TV is). 720p is the safe pick. */
    public int videoWidth() { return prefs.getInt("video_mode_width", 1280); }
    public int videoHeight() { return prefs.getInt("video_mode_height", 720); }
    public void setVideoMode(int w, int h) { prefs.edit().putInt("video_mode_width", w).putInt("video_mode_height", h).apply(); }

    /** Keep 4:3/16:9 letterbox instead of stretching to the phone's aspect. */
    public boolean letterbox() { return prefs.getBoolean("present_letterbox", true); }
    public void setLetterbox(boolean v) { prefs.edit().putBoolean("present_letterbox", v).apply(); }

    /** Tolerant dispatcher: log-and-return on an unregistered indirect call
     *  instead of aborting (what the desktop `play <name>.cmd` does). */
    public boolean tolerant() { return prefs.getBoolean("tolerant", true); }
    public void setTolerant(boolean v) { prefs.edit().putBoolean("tolerant", v).apply(); }

    /**
     * Compile shaders / create pipelines on background threads instead of on
     * the render thread. On by default: the synchronous path freezes for
     * hundreds of milliseconds at every first-use effect on a Mali GPU, which
     * is the difference between "loads in a minute" and "loads in five".
     * Turning it off is the fallback if a title shows missing geometry while
     * the pipelines warm up.
     */
    public boolean asyncShaders() { return prefs.getBoolean("async_shaders", true); }
    public void setAsyncShaders(boolean v) { prefs.edit().putBoolean("async_shaders", v).apply(); }

    /**
     * Answer the game's own system prompts (message boxes, keyboard-input
     * dialogs) immediately with their default choice instead of showing them.
     * On Android the runtime's debug overlay is not composited over the game,
     * so a real prompt can stall the guest thread forever on a dialog nobody
     * can see -- the game looks frozen and ignores every input.
     */
    public boolean autoAnswerPrompts() { return prefs.getBoolean("auto_prompts", true); }
    public void setAutoAnswerPrompts(boolean v) { prefs.edit().putBoolean("auto_prompts", v).apply(); }

    /** Verbose (info-level) runtime log; off costs less, on explains more. */
    public boolean verboseLog() { return prefs.getBoolean("verbose_log", false); }
    public void setVerboseLog(boolean v) { prefs.edit().putBoolean("verbose_log", v).apply(); }

    /** Screen orientation while playing: landscape (default) | portrait | auto. */
    public String orientation() { return prefs.getString("orientation", "landscape"); }
    public void setOrientation(String v) { prefs.edit().putString("orientation", v).apply(); }

    /**
     * Performance preset. Each one is a bundle of runtime cvars on top of the
     * knobs above; "performance" is the default because a phone GPU is roughly a
     * tenth of the Xenos+CPU budget the game was tuned for.
     *
     *   performance  -- 720p guest mode, 1x scale, no anisotropic filtering, no
     *                   memexport readback (GPU->CPU sync stall every frame),
     *                   occlusion queries answered with a constant instead of a
     *                   GPU round-trip, vsync via FIFO (no tearing, no busy loop),
     *                   texture cache capped so it never evicts mid-level.
     *   balanced     -- like performance but 4x anisotropic and real occlusion
     *                   queries (some games use them for lens flares / LOD).
     *   accuracy     -- SDK defaults (what the desktop port runs).
     */
    public String preset() { return prefs.getString("preset", "performance"); }
    public void setPreset(String v) { prefs.edit().putString("preset", v).apply(); }

    /**
     * Frame-rate cap 0 = off (default). It works by slowing the GUEST vblank,
     * so a title that paces its logic on vblank (most 360 games) then runs in
     * slow motion rather than at a lower frame rate -- which is why this stays
     * off unless the user picks it deliberately in the Graphics dialog.
     */
    public int fpsCap() { return prefs.getInt("fps_cap", 0); }
    public void setFpsCap(int v) { prefs.edit().putInt("fps_cap", v).apply(); }

    /** Extra raw cvars (advanced): "key=value" per line. */
    public String extra() { return prefs.getString("extra", ""); }
    public void setExtra(String v) { prefs.edit().putString("extra", v == null ? "" : v).apply(); }

    public Map<String, String> asCvars() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("resolution_scale", Integer.toString(resolutionScale()));
        m.put("draw_resolution_scale_x", Integer.toString(resolutionScale()));
        m.put("draw_resolution_scale_y", Integer.toString(resolutionScale()));
        m.put("vsync", Boolean.toString(vsync()));
        m.put("present_effect", presentEffect());
        m.put("video_mode_width", Integer.toString(videoWidth()));
        m.put("video_mode_height", Integer.toString(videoHeight()));
        m.put("present_letterbox", Boolean.toString(letterbox()));

        // --- preset bundle -------------------------------------------------
        String p = preset();
        if (!p.equals("accuracy")) {
            // no per-frame GPU->CPU copy of memexport buffers; almost no title
            // reads them back on the CPU, and the sync costs a full frame stall
            m.put("readback_memexport", "false");
            m.put("vulkan_readback_memexport", "false");
            m.put("vulkan_readback_resolve", "false");
            // Texture cache sized to the device instead of a fixed guess: the
            // hard limit must leave room for the guest arena plus the game's
            // own heap, or Android kills the process. ~1/8 soft, ~1/6 hard.
            int total = totalRamMb();
            int soft = clampInt((int) (total * 0.125), 128, 512);
            int hard = clampInt((int) (total * 0.17), 256, 1024);
            m.put("texture_cache_memory_limit_soft", Integer.toString(soft));
            m.put("texture_cache_memory_limit_hard", Integer.toString(hard));
            m.put("texture_cache_memory_limit_render_to_texture", "32");
            // Shader/pipeline compilation MUST stay async: the alternative
            // compiles every first-use pipeline on the render thread, which on
            // a Mali-G57 means multi-second freezes at load and a hitch every
            // time a new effect appears. Frames are never skipped while a
            // pipeline is warming up (see vulkan_async_skip_incomplete_frames),
            // so this cannot black-screen like the old surface bug did.
            m.put("async_shader_compilation", "true");
            m.put("vulkan_async_skip_incomplete_frames", "false");
            // Default (-1) spawns (cores * 3 / 4) compilation workers: 6 threads
            // on a Helio G99, all burning the two big cores while the guest
            // threads try to run. Two keeps the big cores for the game.
            m.put("vulkan_pipeline_creation_threads", "2");
            // present: FIFO only (mailbox/immediate keep the GPU busy rendering
            // frames the panel never shows -- pure heat on a phone)
            m.put("vulkan_allow_present_mode_immediate", "false");
            m.put("vulkan_allow_present_mode_mailbox", "false");
            m.put("vulkan_allow_present_mode_fifo_relaxed", "true");
            // log I/O on the render/guest threads is not free
            m.put("log_level", verboseLog() ? "info" : "warning");
            m.put("log_high_frequency_kernel_calls", "false");
            m.put("vulkan_log_debug_messages", "false");
            m.put("gpu_debug_markers", "false");
            // dynamic rendering: no VkRenderPass objects, fewer pipeline variants
            m.put("vulkan_dynamic_rendering", "true");
        }
        if (p.equals("performance")) {
            m.put("anisotropic_override", "0");          // 0 = off
            m.put("occlusion_query_enable", "false");    // constant answer, no GPU round-trip
            m.put("native_2x_msaa", "false");            // resolve 2xMSAA as 1x
            m.put("gamma_render_target_as_unorm16", "false"); // 8-bit gamma RTs: half the bandwidth
        } else if (p.equals("balanced")) {
            m.put("anisotropic_override", "3");          // 4x
        }
        // Explicit override of the preset's value, so the checkbox always wins.
        m.put("async_shader_compilation", Boolean.toString(asyncShaders()));
        // headless = never block the guest on a dialog; see autoAnswerPrompts().
        m.put("headless", Boolean.toString(autoAnswerPrompts()));
        if (fpsCap() > 0) m.put("env.REX_FPS_CAP", Integer.toString(fpsCap()));
        // "env.NAME=value" lines are exported as environment variables by the
        // native side (the dispatcher reads REX_HEAL_DISCOVER via getenv).
        if (tolerant()) m.put("env.REX_HEAL_DISCOVER", "1");
        for (String line : extra().split("\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (line.startsWith("#") || eq <= 0) continue;
            m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return m;
    }

    /** Writes settings.txt for the native side. */
    public void write(Context ctx) throws IOException {
        File f = GameFiles.settingsFile(ctx);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (PrintWriter w = new PrintWriter(new FileWriter(f, false))) {
            w.println("# written by the rexauto Android launcher - one cvar per line");
            for (Map.Entry<String, String> e : asCvars().entrySet()) {
                w.println(e.getKey() + "=" + e.getValue());
            }
        }
    }
}
