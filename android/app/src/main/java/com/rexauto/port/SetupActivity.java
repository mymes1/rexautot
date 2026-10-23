package com.rexauto.port;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The launcher. Knows exactly which game this APK was recompiled for
 * (title + title ID baked in at build time via BuildConfig), so it can tell
 * the user what to drop in and refuse the wrong disc. Takes an ISO, a ZIP,
 * a bare default.xex, or an already-extracted folder (zero-copy direct link
 * or SAF copy into app storage), then offers graphics settings and Play.
 */
public class SetupActivity extends Activity {
    private static final int REQ_PICK_FILE = 1;
    private static final int REQ_PICK_TREE = 2;
    private static final int REQ_PICK_DRIVER = 3;
    private static final int REQ_ALL_FILES_ACCESS = 4;

    /** Survives Activity recreation during multi-GB folder/ISO imports on low-RAM devices. */
    private static final class State {
        static final Handler UI = new Handler(Looper.getMainLooper());
        static volatile boolean busy;
        static volatile boolean indeterminate = true;
        static volatile int progressPermille;
        static volatile String progressText;
        static volatile String lastError;
        static volatile boolean pendingPickAfterAllFilesPerm;
        static WeakReference<SetupActivity> active = new WeakReference<>(null);

        static SetupActivity current() {
            return active.get();
        }
    }

    private static final class DocEntry {
        final String docId;
        final String relPath;
        final String name;
        final long size;

        DocEntry(String docId, String relPath, String name, long size) {
            this.docId = docId;
            this.relPath = relPath;
            this.name = name;
            this.size = size;
        }
    }

    private TextView status;
    private ProgressBar progress;
    private Button playBtn, linkDirBtn, pickDirBtn, pickBtn, gfxBtn, driverBtn, logsBtn, resetBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        State.active = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.BLACK);

        // --- header: cover + "<Title>" / "Android Edition" -----------------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Bitmap cover = loadCover();
        if (cover != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(cover);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (72 * dp), (int) (72 * dp));
            lp.rightMargin = pad / 2;
            iv.setLayoutParams(lp);
            header.addView(iv);
        }
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(BuildConfig.GAME_TITLE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        titles.addView(title);
        TextView edition = new TextView(this);
        edition.setText(getString(R.string.edition, BuildConfig.TITLE_ID));
        edition.setTextSize(14);
        edition.setTextColor(0xFFB0B0B0);
        titles.addView(edition);
        header.addView(titles);
        root.addView(header);

        TextView help = new TextView(this);
        help.setText(getString(R.string.setup_help, BuildConfig.GAME_TITLE, BuildConfig.TITLE_ID));
        help.setTextSize(14);
        help.setTextColor(0xFFDDDDDD);
        help.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(help);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(0xFFFFD27F);
        status.setPadding(0, 0, 0, pad / 3);
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        progress.setMax(1000);
        root.addView(progress);

        playBtn = button(root, R.string.play, v -> launchGame());
        linkDirBtn = button(root, R.string.pick_link_folder, v -> linkFolderDirectly());
        pickDirBtn = button(root, R.string.pick_folder, v -> pickFolder());
        pickBtn = button(root, R.string.pick_iso, v -> pickFile());
        gfxBtn = button(root, R.string.graphics, v -> showGraphicsDialog());
        driverBtn = button(root, R.string.gpu_driver, v -> showDriverDialog());
        logsBtn = button(root, R.string.logs, v -> showLogsDialog());
        resetBtn = button(root, R.string.reset, v -> reset());

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        sv.addView(root);
        setContentView(sv);
        refresh();
        showCrashReportIfAny();
    }

    @Override
    protected void onResume() {
        super.onResume();
        State.active = new WeakReference<>(this);
        refresh();
        if (State.pendingPickAfterAllFilesPerm) {
            State.pendingPickAfterAllFilesPerm = false;
            if (hasAllFilesAccess()) {
                pickFolder();
            } else {
                Toast.makeText(this, "All files access was not enabled — selecting folder will copy files into app storage instead.", Toast.LENGTH_LONG).show();
                pickFolder();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (State.active.get() == this) {
            State.active = new WeakReference<>(null);
        }
        super.onDestroy();
    }

    private static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    // --- crash report / logs ------------------------------------------------
    private File logsDir() {
        File ext = getExternalFilesDir(null);
        return new File(ext, "logs");
    }

    private File crashFile() { return new File(logsDir(), "crash.txt"); }

    private File gameLog() { return new File(logsDir(), BuildConfig.PROJECT + ".log"); }

    private static String tail(File f, int maxChars) {
        if (f == null || !f.isFile()) return "";
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            long from = Math.max(0, len - maxChars);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** libmain.so writes logs/crash.txt from its signal handler; show it once. */
    private void showCrashReportIfAny() {
        File c = crashFile();
        if (!c.isFile()) return;
        String report = tail(c, 6000);
        String log = tail(gameLog(), 3000);
        String text = report + "\n--- last log lines ---\n" + log;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setView(sv)
                .setPositiveButton(R.string.share, (d, w) -> shareText(getString(R.string.crash_title), text))
                .setNeutralButton(R.string.copy, (d, w) -> copyText(text))
                .setNegativeButton(android.R.string.ok, null)
                .setOnDismissListener(d -> { c.renameTo(new File(logsDir(), "crash.prev.txt")); })
                .show();
    }

    private void showLogsDialog() {
        String prev = tail(new File(logsDir(), "crash.prev.txt"), 4000);
        String cur = tail(crashFile(), 4000);
        String prevLog = tail(new File(logsDir(), BuildConfig.PROJECT + ".prev.log"), 3000);
        String log = tail(gameLog(), 6000);
        String prof = tail(new File(logsDir(), "profile.txt"), 5000);
        String text = (cur.isEmpty() ? prev : cur);
        if (!text.isEmpty()) text = "--- last crash ---\n" + text + "\n";
        if (State.lastError != null && !State.lastError.isEmpty()) {
            text = "--- last import error ---\n" + State.lastError + "\n\n" + text;
        }
        if (!prof.isEmpty()) text += "--- profile (where CPU time goes) ---\n" + prof + "\n";
        text += "--- " + BuildConfig.PROJECT + ".log (tail) ---\n" + (log.isEmpty() ? "(no log yet)" : log);
        if (!prevLog.isEmpty()) {
            text += "\n--- " + BuildConfig.PROJECT + ".prev.log (tail) ---\n" + prevLog;
        }
        final String all = text;
        TextView tv = new TextView(this);
        tv.setText(all);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(R.string.logs)
                .setView(sv)
                .setPositiveButton(R.string.share, (d, w) -> shareText(BuildConfig.PROJECT + " log", all))
                .setNeutralButton(R.string.copy, (d, w) -> copyText(all))
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void shareText(String subject, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        i.putExtra(Intent.EXTRA_TEXT, text);
        try { startActivity(Intent.createChooser(i, subject)); } catch (Exception e) { copyText(text); }
    }

    private void copyText(String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("log", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private Button button(LinearLayout parent, int text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }

    private Bitmap loadCover() {
        try (InputStream in = getAssets().open("cover.png", AssetManager.ACCESS_STREAMING)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static void countTree(File f, long[] stats) {
        if (f == null || !f.exists()) return;
        if (f.isFile()) {
            if (!f.getName().endsWith(".part") && !f.getName().equals(".rex_write_probe")) {
                stats[0]++;
                stats[1] += f.length();
            }
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) countTree(k, stats);
    }

    private void applyUiState() {
        boolean b = State.busy;
        pickBtn.setEnabled(!b);
        pickDirBtn.setEnabled(!b);
        linkDirBtn.setEnabled(!b);
        driverBtn.setEnabled(!b);
        resetBtn.setEnabled(!b);
        playBtn.setEnabled(!b);
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        if (b) {
            progress.setIndeterminate(State.indeterminate);
            if (!State.indeterminate) {
                progress.setProgress(State.progressPermille);
            }
            status.setTextColor(0xFFFFD27F);
            status.setText(State.progressText != null ? State.progressText : getString(R.string.status_copying));
            return;
        }

        boolean ready = GameFiles.hasValidGameRoot(this);
        playBtn.setVisibility(ready ? View.VISIBLE : View.GONE);
        gfxBtn.setVisibility(View.VISIBLE);

        if (State.lastError != null && !State.lastError.isEmpty()) {
            status.setTextColor(0xFFFF6B6B);
            status.setText("⚠ " + State.lastError);
        } else if (!ready) {
            status.setTextColor(0xFFFFD27F);
            status.setText(getString(R.string.status_idle));
        } else {
            String root = GameFiles.configuredGameRoot(this);
            long[] stats = {0, 0};
            countTree(root != null ? new File(root) : null, stats);
            if (stats[0] <= 1 && stats[1] < (64L << 20)) {
                status.setTextColor(0xFFFF6B6B);
                status.setText(String.format(Locale.US,
                        "%s\n⚠ Only default.xex found (%d MB — missing data/.cpk archives!). Tap 'Link extracted folder directly' or 'Choose folder' and select the folder holding BOTH default.xex and data.cpk!",
                        getString(R.string.status_ready, root), stats[1] >> 20));
            } else {
                status.setTextColor(0xFF8CFF9E);
                status.setText(String.format(Locale.US, "%s\n(%d files, %d MB total)",
                        getString(R.string.status_ready, root), stats[0], stats[1] >> 20));
            }
        }
    }

    private void refresh() {
        applyUiState();
    }

    // --- input pickers ------------------------------------------------------
    private void linkFolderDirectly() {
        if (!hasAllFilesAccess()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.perm_all_files_title)
                    .setMessage(R.string.perm_all_files_msg)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        State.pendingPickAfterAllFilesPerm = true;
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivityForResult(i, REQ_ALL_FILES_ACCESS);
                        } catch (Exception e) {
                            Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivityForResult(i, REQ_ALL_FILES_ACCESS);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        pickFolder();
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_FILE);
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_TREE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ALL_FILES_ACCESS) {
            return; // handled in onResume()
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (takeFlags == 0) takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignored) {
        }
        if (req == REQ_PICK_FILE) importFile(uri);
        else if (req == REQ_PICK_TREE) importTree(uri);
        else if (req == REQ_PICK_DRIVER) importDriver(uri);
    }

    private static void beginBusy(String initialText) {
        State.lastError = null;
        State.busy = true;
        State.indeterminate = true;
        State.progressPermille = 0;
        State.progressText = initialText;
        State.UI.post(() -> {
            SetupActivity a = State.current();
            if (a != null) a.applyUiState();
        });
    }

    private static void post(String s) {
        State.indeterminate = true;
        State.progressText = s;
        State.UI.post(() -> {
            SetupActivity a = State.current();
            if (a != null) a.applyUiState();
        });
    }

    private static void postProgress(String s, long done, long total) {
        State.indeterminate = total <= 0;
        State.progressPermille = total > 0 ? (int) Math.min(1000L, (done * 1000L) / total) : 0;
        State.progressText = total > 0
                ? String.format(Locale.US, "%s  (%d / %d MB)", s, done >> 20, total >> 20)
                : s;
        State.UI.post(() -> {
            SetupActivity a = State.current();
            if (a != null) a.applyUiState();
        });
    }

    // --- SAF path resolution & zero-copy check ------------------------------
    private static File resolveDocIdToFile(Context ctx, String docId) {
        if (docId == null || docId.isEmpty()) return null;
        if (docId.startsWith("raw:")) {
            return new File(docId.substring("raw:".length()));
        }
        int colon = docId.indexOf(':');
        if (colon < 0) return null;
        String vol = docId.substring(0, colon);
        String rel = docId.substring(colon + 1);
        if ("primary".equalsIgnoreCase(vol)) {
            return rel.isEmpty()
                    ? Environment.getExternalStorageDirectory()
                    : new File(Environment.getExternalStorageDirectory(), rel);
        }
        if ("home".equalsIgnoreCase(vol)) {
            File docs = new File(Environment.getExternalStorageDirectory(), "Documents");
            return rel.isEmpty() ? docs : new File(docs, rel);
        }
        // Removable SD / USB volume (e.g. "XXXX-XXXX:Games/DBX-XEX")
        File storageVol = new File("/storage", vol);
        if (storageVol.isDirectory()) {
            return rel.isEmpty() ? storageVol : new File(storageVol, rel);
        }
        File[] extDirs = ctx.getExternalFilesDirs(null);
        if (extDirs != null) {
            for (File d : extDirs) {
                if (d == null) continue;
                String p = d.getAbsolutePath();
                int idx = p.indexOf("/Android/");
                if (idx > 0 && p.contains(vol)) {
                    File root = new File(p.substring(0, idx));
                    return rel.isEmpty() ? root : new File(root, rel);
                }
            }
        }
        return null;
    }

    private static File safTreeToFile(Context ctx, Uri tree) {
        try {
            return resolveDocIdToFile(ctx, DocumentsContract.getTreeDocumentId(tree));
        } catch (Exception e) {
            return null;
        }
    }

    private static File safDocToFile(Context ctx, Uri doc) {
        try {
            return resolveDocIdToFile(ctx, DocumentsContract.getDocumentId(doc));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the direct default.xex inside dir if native POSIX read + write +
     * directory listing all work directly on dir without copying into Android/data.
     */
    private static File directUsableXex(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File xex = findXex(dir, 0);
        if (xex == null || !xex.canRead()) return null;
        File gameRoot = xex.getParentFile();
        if (gameRoot == null) return null;
        File[] kids = gameRoot.listFiles();
        if (kids == null || kids.length == 0) return null;
        for (File k : kids) {
            if (k.isFile() && !k.canRead()) return null;
        }
        File probe = new File(gameRoot, ".rex_write_probe");
        try {
            try (FileOutputStream fos = new FileOutputStream(probe)) {
                fos.write(0x4F);
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return xex;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return null;
        }
    }

    private static String archiveHint(String name) {
        if (name == null) return null;
        String low = name.toLowerCase(Locale.US);
        if (low.endsWith(".rar") || low.endsWith(".7z") || low.endsWith(".001")
                || low.endsWith(".tar") || low.endsWith(".tar.gz") || low.endsWith(".xz")) {
            return String.format(Locale.US,
                    "Found unextracted archive '%s'. Extract '%s' first using ZArchiver or RAR (password: LegionGamesGod if encrypted), then tap 'Link extracted folder directly' or 'Choose folder' and pick the extracted folder containing default.xex + .cpk files.",
                    name, name);
        }
        return null;
    }

    private static File extractZipStream(InputStream rawIn, File dest) throws Exception {
        byte[] buf = new byte[1 << 20];
        long done = 0;
        try (ZipInputStream zis = new ZipInputStream(rawIn)) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                String name = ze.getName().replace('\\', '/');
                if (name.isEmpty() || name.contains("..")) continue;
                File out = new File(dest, name);
                if (!out.getCanonicalPath().startsWith(dest.getCanonicalPath())) continue;
                if (ze.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                    }
                }
                post(String.format(Locale.US, "Extracting %s (%d MB)…", out.getName(), done >> 20));
            }
        }
        return findXex(dest, 0);
    }

    /** ISO, ZIP, STFS/GoD package, or default.xex picked with the document picker. */
    private void importFile(Uri uri) {
        final Context appCtx = getApplicationContext();
        String name = displayName(appCtx, uri);
        beginBusy(appCtx.getString(R.string.status_reading, name));
        new Thread(() -> {
            File dest = GameFiles.gameDir(appCtx);
            try {
                String rarHint = archiveHint(name);
                if (rarHint != null) throw new Exception(rarHint);

                IsoExtractor iso = IsoExtractor.open(appCtx.getContentResolver(), uri);
                File xex;
                if (iso != null) {
                    dest.mkdirs();
                    try {
                        xex = iso.extractAll(dest, SetupActivity::postProgress);
                    } finally {
                        iso.close();
                    }
                    if (xex == null) throw new Exception(appCtx.getString(R.string.err_no_xex_in_iso));
                } else if (name != null && name.toLowerCase(Locale.US).endsWith(".zip")) {
                    dest.mkdirs();
                    try (InputStream zin = appCtx.getContentResolver().openInputStream(uri)) {
                        xex = extractZipStream(zin, dest);
                    }
                    if (xex == null) throw new Exception("No default.xex found inside " + name);
                } else if ((xex = importPackage(appCtx, uri, name, dest)) != null) {
                    // CON/LIVE/PIRS handled
                } else {
                    try (InputStream probe = appCtx.getContentResolver().openInputStream(uri)) {
                        if (GameFiles.xexTitleId(probe) == null) {
                            throw new Exception(appCtx.getString(R.string.err_not_iso, name));
                        }
                    }
                    // If user picked default.xex directly on storage and its folder is readable,
                    // link the whole parent game folder automatically instead of stranding default.xex!
                    File directFile = safDocToFile(appCtx, uri);
                    File directParent = directFile != null ? directFile.getParentFile() : null;
                    File directXex = directUsableXex(directParent);
                    if (directXex != null) {
                        finish(appCtx, directXex);
                        return;
                    }
                    dest.mkdirs();
                    xex = new File(dest, "default.xex");
                    try (InputStream in = appCtx.getContentResolver().openInputStream(uri)) {
                        IsoExtractor.copyStream(in, xex);
                    }
                }
                finish(appCtx, xex);
            } catch (Throwable t) {
                fail(appCtx, t);
            }
        }, "import").start();
    }

    /**
     * CON / LIVE / PIRS picked as a single file: STFS (XBLA, arcade titles) is
     * unpacked entry by entry; a single-file Games-on-Demand image goes through
     * the GDFX path. A multi-part GoD needs its ".data" folder, so the user is
     * pointed at the folder picker. Returns null when the file is not a package.
     */
    private static File importPackage(Context appCtx, Uri uri, String name, File dest) throws Exception {
        java.nio.ByteBuffer hdr;
        android.os.ParcelFileDescriptor pfd = appCtx.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) return null;
        java.nio.channels.FileChannel ch = new java.io.FileInputStream(pfd.getFileDescriptor()).getChannel();
        hdr = StfsExtractor.readHeader(ch);
        if (hdr == null) { ch.close(); pfd.close(); return null; }
        dest.mkdirs();
        if (StfsExtractor.volumeType(hdr) == StfsExtractor.VOLUME_SVOD) {
            ch.close(); pfd.close();
            if (hdr.getInt(0x39D) > 1)
                throw new Exception(appCtx.getString(R.string.err_god_needs_folder, name));
            StfsExtractor.Found f = new StfsExtractor.Found(uri, name, hdr, null);
            post(appCtx.getString(R.string.status_reading, name + " (Games on Demand)"));
            IsoExtractor god = StfsExtractor.openSvod(appCtx.getContentResolver(), f, null);
            try {
                File xex = god.extractAll(dest, SetupActivity::postProgress);
                if (xex == null) throw new Exception(appCtx.getString(R.string.err_no_xex_in_iso));
                return xex;
            } finally { god.close(); }
        }
        post(appCtx.getString(R.string.status_reading, name + " (STFS)"));
        try {
            File xex = new StfsExtractor.Stfs(ch, hdr).extractAll(dest, SetupActivity::postProgress);
            if (xex == null) throw new Exception(appCtx.getString(R.string.err_no_xex_in_stfs, name));
            return xex;
        } finally { ch.close(); pfd.close(); }
    }

    /** Find the document ID of the folder inside `tree` that contains default.xex (depth <= 6). */
    private static String findTreeXexDir(ContentResolver cr, Uri tree, String docId, int depth, List<String> topItems) {
        if (depth > 6) return null;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        List<String[]> dirs = new ArrayList<>();
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String childId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (depth == 0 && name != null && topItems.size() < 8) {
                    topItems.add(name);
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (name != null && (name.equalsIgnoreCase("$SystemUpdate") || name.endsWith(".data"))) {
                        continue;
                    }
                    dirs.add(new String[]{childId, name});
                } else if (name != null && name.equalsIgnoreCase("default.xex")) {
                    return docId;
                }
            }
        } catch (Exception ignored) {
        }
        for (String[] d : dirs) {
            String hit = findTreeXexDir(cr, tree, d[0], depth + 1, topItems);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Collect every file inside `docId` (skipping $SystemUpdate and temp files), placing default.xex last. */
    private static void collectTreeFiles(ContentResolver cr, Uri tree, String docId, String relPrefix,
                                         List<DocEntry> files, int depth) throws Exception {
        if (depth > 12) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        List<String[]> rows = new ArrayList<>();
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                rows.add(new String[]{c.getString(0), c.getString(1), c.getString(2), c.getString(3)});
            }
        }
        for (String[] r : rows) {
            String rawName = r[1] == null ? "file" : r[1].replace("/", "_");
            if (rawName.equalsIgnoreCase("$SystemUpdate")
                    || rawName.endsWith(".part")
                    || rawName.equals(".rex_write_probe")
                    || rawName.startsWith(".trashed-")) {
                continue;
            }
            String rel = relPrefix.isEmpty() ? rawName : (relPrefix + "/" + rawName);
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(r[2])) {
                collectTreeFiles(cr, tree, r[0], rel, files, depth + 1);
            } else {
                long sz = 0;
                try { sz = r[3] == null ? 0 : Long.parseLong(r[3]); } catch (NumberFormatException ignored) { }
                files.add(new DocEntry(r[0], rel, rawName, sz));
            }
        }
    }

    /** Scan a folder tree (when no default.xex exists) for an ISO, ZIP, or RAR/7z archive. */
    private static String[] findTreeSpecialFile(ContentResolver cr, Uri tree, String docId, int depth) {
        if (depth > 4) return null;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        List<String[]> dirs = new ArrayList<>();
        String[] archiveHit = null;
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(new String[]{id, name});
                } else if (name != null) {
                    String low = name.toLowerCase(Locale.US);
                    if (low.endsWith(".iso")) return new String[]{"iso", id, name};
                    if (low.endsWith(".zip")) return new String[]{"zip", id, name};
                    if (archiveHint(name) != null && archiveHit == null) {
                        archiveHit = new String[]{"archive", id, name};
                    }
                }
            }
        } catch (Exception ignored) {
        }
        for (String[] d : dirs) {
            String[] sub = findTreeSpecialFile(cr, tree, d[0], depth + 1);
            if (sub != null && !"archive".equals(sub[0])) return sub;
            if (sub != null && archiveHit == null) archiveHit = sub;
        }
        return archiveHit;
    }

    /** Already-extracted folder or Games-on-Demand folder picked with the tree picker. */
    private void importTree(Uri tree) {
        final Context appCtx = getApplicationContext();
        beginBusy(appCtx.getString(R.string.status_copying));
        new Thread(() -> {
            ContentResolver cr = appCtx.getContentResolver();
            File dest = GameFiles.gameDir(appCtx);
            try {
                // Fast path #1: direct zero-copy link if storage folder is POSIX readable + writable!
                File directDir = safTreeToFile(appCtx, tree);
                File directXex = directUsableXex(directDir);
                if (directXex != null) {
                    finish(appCtx, directXex);
                    return;
                }

                // Path #2: check for extracted default.xex FIRST (before STFS scan so $SystemUpdate
                // inside extracted Xbox 360 rips is never mistaken for the game package!).
                String rootDocId = DocumentsContract.getTreeDocumentId(tree);
                List<String> topItems = new ArrayList<>();
                String xexDirDocId = findTreeXexDir(cr, tree, rootDocId, 0, topItems);
                File xex = null;

                if (xexDirDocId != null) {
                    List<DocEntry> files = new ArrayList<>();
                    collectTreeFiles(cr, tree, xexDirDocId, "", files, 0);
                    // Copy default.xex LAST so gameDir never looks "ready" before all .cpk files exist!
                    Collections.sort(files, (a, b) -> {
                        boolean ax = a.relPath.equalsIgnoreCase("default.xex");
                        boolean bx = b.relPath.equalsIgnoreCase("default.xex");
                        if (ax != bx) return ax ? 1 : -1;
                        return a.relPath.compareToIgnoreCase(b.relPath);
                    });

                    long totalBytes = 0;
                    for (DocEntry e : files) totalBytes += Math.max(0L, e.size);

                    dest.mkdirs();
                    // Hide any stale standalone default.xex from a prior incomplete import until copy completes
                    File staleXex = new File(dest, "default.xex");
                    if (staleXex.isFile() && files.size() > 1) {
                        //noinspection ResultOfMethodCallIgnored
                        staleXex.delete();
                    }

                    long doneBytes = 0;
                    int totalFiles = files.size();
                    for (int idx = 0; idx < totalFiles; idx++) {
                        DocEntry e = files.get(idx);
                        File out = new File(dest, e.relPath);
                        String label = String.format(Locale.US, "Copying %d/%d: %s", idx + 1, totalFiles, e.name);
                        if (e.size > 0 && out.isFile() && out.length() == e.size) {
                            doneBytes += e.size;
                            postProgress(label, doneBytes, totalBytes);
                        } else {
                            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId);
                            try (InputStream in = cr.openInputStream(doc)) {
                                doneBytes += IsoExtractor.copyStream(in, out, SetupActivity::postProgress, label, doneBytes, totalBytes);
                            }
                        }
                        if (out.getName().equalsIgnoreCase("default.xex")) {
                            xex = out;
                        }
                    }
                    if (xex == null) xex = findXex(dest, 0);
                } else {
                    // Path #3: Games on Demand (SVOD) or XBLA STFS package
                    StfsExtractor.Found pkg = StfsExtractor.findInTree(cr, tree, rootDocId, 0);
                    if (pkg != null && pkg.volumeType() == StfsExtractor.VOLUME_SVOD) {
                        dest.mkdirs();
                        post(appCtx.getString(R.string.status_reading, pkg.name + " (Games on Demand)"));
                        IsoExtractor god = StfsExtractor.openSvod(cr, pkg, tree);
                        try { xex = god.extractAll(dest, SetupActivity::postProgress); } finally { god.close(); }
                        if (xex == null) throw new Exception(appCtx.getString(R.string.err_no_xex_in_iso));
                    } else if (pkg != null) {
                        dest.mkdirs();
                        post(appCtx.getString(R.string.status_reading, pkg.name + " (STFS)"));
                        android.os.ParcelFileDescriptor pfd = cr.openFileDescriptor(pkg.headerDoc, "r");
                        java.nio.channels.FileChannel ch = new java.io.FileInputStream(pfd.getFileDescriptor()).getChannel();
                        try { xex = new StfsExtractor.Stfs(ch, pkg.hdr).extractAll(dest, SetupActivity::postProgress); }
                        finally { ch.close(); pfd.close(); }
                        if (xex == null) throw new Exception(appCtx.getString(R.string.err_no_xex_in_stfs, pkg.name));
                    } else {
                        // Path #4: ISO or ZIP inside picked folder, or helpful RAR/7z hint
                        String[] special = findTreeSpecialFile(cr, tree, rootDocId, 0);
                        if (special != null) {
                            Uri specialUri = DocumentsContract.buildDocumentUriUsingTree(tree, special[1]);
                            if ("iso".equals(special[0])) {
                                IsoExtractor iso = IsoExtractor.open(cr, specialUri);
                                if (iso != null) {
                                    dest.mkdirs();
                                    try { xex = iso.extractAll(dest, SetupActivity::postProgress); } finally { iso.close(); }
                                }
                            } else if ("zip".equals(special[0])) {
                                dest.mkdirs();
                                try (InputStream zin = cr.openInputStream(specialUri)) {
                                    xex = extractZipStream(zin, dest);
                                }
                            } else if ("archive".equals(special[0])) {
                                throw new Exception(archiveHint(special[2]));
                            }
                        }
                    }
                }

                if (xex == null) {
                    String contents = topItems.isEmpty() ? "(folder is empty or unreadable)" : topItems.toString();
                    throw new Exception(appCtx.getString(R.string.err_no_xex_in_folder)
                            + "\nItems seen in picked folder: " + contents);
                }
                finish(appCtx, xex);
            } catch (Throwable t) {
                fail(appCtx, t);
            }
        }, "import-tree").start();
    }

    private static File findXex(File dir, int depth) {
        if (dir == null || depth > 5) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File f : kids) {
            if (f.isFile() && f.getName().equalsIgnoreCase("default.xex")) return f;
        }
        for (File f : kids) {
            if (f.isDirectory() && !f.getName().equalsIgnoreCase("$SystemUpdate")) {
                File x = findXex(f, depth + 1);
                if (x != null) return x;
            }
        }
        return null;
    }

    /** Title-ID gate + write game_root.txt. */
    private static void finish(Context appCtx, File xex) throws Exception {
        String got = GameFiles.xexTitleId(xex);
        String want = BuildConfig.TITLE_ID;
        if (want != null && !want.isEmpty() && !want.equals("00000000") && got != null && !got.equalsIgnoreCase(want)) {
            String other = TitleDb.lookup(appCtx, got);
            throw new Exception(appCtx.getString(R.string.err_wrong_game,
                    other != null ? other : ("Title ID " + got), BuildConfig.GAME_TITLE, want));
        }
        File root = xex.getParentFile();
        try (PrintWriter w = new PrintWriter(new FileWriter(GameFiles.configFile(appCtx), false))) {
            w.println(root.getAbsolutePath());
        }
        new GraphicsSettings(appCtx).write(appCtx);
        State.lastError = null;
        State.busy = false;
        State.progressText = null;
        State.UI.post(() -> {
            SetupActivity a = State.current();
            if (a != null) {
                a.applyUiState();
                Toast.makeText(a, R.string.status_done, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void fail(Context appCtx, Throwable t) {
        String raw = t == null ? null : t.getMessage();
        final String msg = (raw == null || raw.trim().isEmpty())
                ? (t == null ? appCtx.getString(R.string.err_generic) : t.getClass().getSimpleName())
                : raw.trim();
        State.lastError = msg;
        State.busy = false;
        State.progressText = null;
        State.UI.post(() -> {
            SetupActivity a = State.current();
            if (a != null) {
                a.applyUiState();
                if (!a.isFinishing()) {
                    new AlertDialog.Builder(a)
                            .setTitle("Could not import game files")
                            .setMessage(msg)
                            .setNeutralButton(R.string.copy, (d, w) -> a.copyText(msg))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            }
        });
    }

    private static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return uri.getLastPathSegment();
    }

    private void reset() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.reset_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    State.lastError = null;
                    GameFiles.configFile(this).delete();
                    deleteRecursively(GameFiles.gameDir(this));
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // --- custom GPU driver (Turnip) ----------------------------------------
    private void showDriverDialog() {
        GpuDriver gd = new GpuDriver(this);
        List<GpuDriver.Info> list = gd.installed();
        String[] names = new String[list.size() + 1];
        names[0] = getString(R.string.gpu_driver_system);
        int checked = 0;
        for (int i = 0; i < list.size(); i++) {
            GpuDriver.Info d = list.get(i);
            names[i + 1] = d.name + (d.version.isEmpty() ? "" : "  (" + d.version + ")");
            if (d.slug.equals(gd.selected())) checked = i + 1;
        }
        final int[] pick = {checked};
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.gpu_driver)
                .setSingleChoiceItems(names, checked, (d, w) -> pick[0] = w)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        gd.select(pick[0] == 0 ? null : list.get(pick[0] - 1).slug);
                        Toast.makeText(this, pick[0] == 0 ? getString(R.string.gpu_driver_system) : list.get(pick[0] - 1).name, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); }
                })
                .setNeutralButton(R.string.gpu_driver_import, (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                    startActivityForResult(i, REQ_PICK_DRIVER);
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (!GpuDriver.isAdreno()) {
            b.setMessage(R.string.gpu_driver_not_adreno);
        }
        AlertDialog dlg = b.create();
        dlg.getListView().setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos == 0) return true;
            GpuDriver.Info d = list.get(pos - 1);
            new AlertDialog.Builder(this).setMessage(getString(R.string.gpu_driver_remove, d.name))
                    .setPositiveButton(android.R.string.ok, (dd, ww) -> {
                        try { gd.remove(d.slug); } catch (Exception e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); }
                        dlg.dismiss();
                        showDriverDialog();
                    }).setNegativeButton(android.R.string.cancel, null).show();
            return true;
        });
        dlg.show();
    }

    private void importDriver(Uri uri) {
        final Context appCtx = getApplicationContext();
        String name = displayName(appCtx, uri);
        beginBusy(appCtx.getString(R.string.status_reading, name));
        new Thread(() -> {
            try {
                GpuDriver gd = new GpuDriver(appCtx);
                GpuDriver.Info info = gd.importZip(appCtx.getContentResolver(), uri, name);
                gd.select(info.slug);
                State.lastError = null;
                State.busy = false;
                State.progressText = null;
                State.UI.post(() -> {
                    SetupActivity a = State.current();
                    if (a != null) {
                        a.applyUiState();
                        Toast.makeText(a, a.getString(R.string.gpu_driver_installed, info.name), Toast.LENGTH_LONG).show();
                        a.showDriverDialog();
                    }
                });
            } catch (Throwable t) {
                fail(appCtx, t);
            }
        }, "import-driver").start();
    }

    // --- graphics settings --------------------------------------------------
    private void showGraphicsDialog() {
        GraphicsSettings gs = new GraphicsSettings(this);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        label(box, R.string.gfx_preset);
        Spinner preset = new Spinner(this);
        String[] presets = {"performance", "balanced", "accuracy"};
        preset.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.preset_performance), getString(R.string.preset_balanced), getString(R.string.preset_accuracy)}));
        for (int i = 0; i < presets.length; i++) if (presets[i].equals(gs.preset())) preset.setSelection(i);
        box.addView(preset);

        label(box, R.string.gfx_fps_cap);
        Spinner fps = new Spinner(this);
        int[] caps = {0, 30, 60};
        fps.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.fps_off), "30 FPS (cooler, steadier)", "60 FPS"}));
        for (int i = 0; i < caps.length; i++) if (caps[i] == gs.fpsCap()) fps.setSelection(i);
        box.addView(fps);

        label(box, R.string.gfx_scale);
        Spinner scale = new Spinner(this);
        scale.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"1x (720p, fastest)", "2x (1440p)", "3x (2160p)"}));
        scale.setSelection(gs.resolutionScale() - 1);
        box.addView(scale);

        label(box, R.string.gfx_effect);
        Spinner effect = new Spinner(this);
        String[] effects = {"bilinear", "cas", "fsr"};
        effect.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Bilinear (cheapest)", "AMD CAS (sharpen)", "AMD FSR (upscale)"}));
        for (int i = 0; i < effects.length; i++) if (effects[i].equals(gs.presentEffect())) effect.setSelection(i);
        box.addView(effect);

        label(box, R.string.gfx_video_mode);
        Spinner mode = new Spinner(this);
        int[][] modes = {{1280, 720}, {1920, 1080}, {1024, 576}, {960, 540}};
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"1280x720 (default)", "1920x1080 (heavy)", "1024x576 (lighter)", "960x540 (lightest)"}));
        for (int i = 0; i < modes.length; i++) if (modes[i][0] == gs.videoWidth()) mode.setSelection(i);
        box.addView(mode);

        label(box, R.string.gfx_orientation);
        Spinner orient = new Spinner(this);
        String[] orients = {"landscape", "portrait", "auto"};
        orient.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.orient_landscape), getString(R.string.orient_portrait), getString(R.string.orient_auto)}));
        for (int i = 0; i < orients.length; i++) if (orients[i].equals(gs.orientation())) orient.setSelection(i);
        box.addView(orient);

        CheckBox vsync = new CheckBox(this);
        vsync.setText(R.string.gfx_vsync);
        vsync.setChecked(gs.vsync());
        box.addView(vsync);

        CheckBox letterbox = new CheckBox(this);
        letterbox.setText(R.string.gfx_letterbox);
        letterbox.setChecked(gs.letterbox());
        box.addView(letterbox);

        CheckBox tolerant = new CheckBox(this);
        tolerant.setText(R.string.gfx_tolerant);
        tolerant.setChecked(gs.tolerant());
        box.addView(tolerant);

        label(box, R.string.gfx_extra);
        EditText extra = new EditText(this);
        extra.setHint("key=value");
        extra.setText(gs.extra());
        extra.setMinLines(2);
        box.addView(extra);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(R.string.graphics)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    gs.setPreset(presets[preset.getSelectedItemPosition()]);
                    gs.setFpsCap(caps[fps.getSelectedItemPosition()]);
                    gs.setResolutionScale(scale.getSelectedItemPosition() + 1);
                    gs.setPresentEffect(effects[effect.getSelectedItemPosition()]);
                    int[] m = modes[mode.getSelectedItemPosition()];
                    gs.setVideoMode(m[0], m[1]);
                    gs.setOrientation(orients[orient.getSelectedItemPosition()]);
                    gs.setVsync(vsync.isChecked());
                    gs.setLetterbox(letterbox.isChecked());
                    gs.setTolerant(tolerant.isChecked());
                    gs.setExtra(extra.getText().toString());
                    try { gs.write(this); } catch (Exception e) { Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show(); }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void label(LinearLayout parent, int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, 12, 0, 4);
        parent.addView(t);
    }

    private void launchGame() {
        try { new GraphicsSettings(this).write(this); } catch (Exception ignored) { }
        startActivity(new Intent(this, MainActivity.class));
    }
}
