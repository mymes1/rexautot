#!/usr/bin/env python3
"""Unified GitHub Actions workflow runner for Windows codegen + Android APK build.

 Keeps .github/workflows/main.yml compact, readable, and free of inline shell blocks:
   python tools/ci_main.py prepare-windows
   python tools/ci_main.py setup-sdk
   python tools/ci_main.py fetch
   python tools/ci_main.py recompile
   python tools/ci_main.py pack-port
   python3 tools/ci_main.py build-apk
   python3 tools/ci_main.py collect-sources
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_PASSWORD = "LegionGamesGod"


def log(msg):
    print("[ci_main] " + msg, flush=True)


def write_gh(env_var, key, val):
    path = os.environ.get(env_var)
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write("%s=%s\n" % (key, val))


def run_cmd(cmd, cwd=None, env=None, log_path=None):
    log("exec: " + " ".join(str(c) for c in cmd))
    if not log_path:
        res = subprocess.run(cmd, cwd=cwd or ROOT, env=env)
        if res.returncode != 0:
            raise SystemExit("Command failed (exit %d): %s" % (res.returncode, " ".join(str(c) for c in cmd)))
        return
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
    with open(log_path, "a", encoding="utf-8", errors="replace") as lf:
        lf.write("$ %s\n" % " ".join(str(c) for c in cmd))
        lf.flush()
        proc = subprocess.Popen(
            cmd,
            cwd=cwd or ROOT,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            errors="replace",
            bufsize=1,
        )
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            lf.write(line)
            lf.flush()
        rc = proc.wait()
    if rc != 0:
        raise SystemExit("Command failed (exit %d): %s" % (rc, " ".join(str(c) for c in cmd)))


def normalize_project_name(raw):
    clean = re.sub(r"[^a-z0-9_]+", "_", (raw or "").strip().lower()).strip("_")
    if not clean:
        clean = "game"
    if clean[0].isdigit():
        clean = "g_" + clean
    if len(clean) > 40:
        clean = clean[:40].rstrip("_") or "game"
    return clean


def cmd_prepare_windows():
    raw_name = os.environ.get("RAW_NAME", "db_xenoverse_recomp")
    game_url = (os.environ.get("GAME_URL") or "").strip()
    if not re.match(r"^https?://", game_url, re.I):
        raise SystemExit("The game URL must start with http:// or https://.")

    clean = normalize_project_name(raw_name)
    log("Normalized project name: %s" % clean)
    write_gh("GITHUB_ENV", "PROJECT_NAME", clean)
    write_gh("GITHUB_OUTPUT", "project_name", clean)

    run_cmd([
        sys.executable, "-m", "pip", "install", "--upgrade",
        "pip", "pillow", "py7zr", "pyzipper", "curl_cffi", "playwright",
    ])

    pf86 = os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")
    vswhere = os.path.join(pf86, "Microsoft Visual Studio", "Installer", "vswhere.exe")
    if not os.path.isfile(vswhere):
        raise SystemExit("vswhere.exe not found at %s" % vswhere)
    vs_install = subprocess.check_output(
        [vswhere, "-latest", "-products", "*", "-property", "installationPath"],
        text=True,
    ).strip()
    if not vs_install:
        raise SystemExit("Visual Studio installation not found.")
    vcvars = os.path.join(vs_install, "VC", "Auxiliary", "Build", "vcvars64.bat")
    if not os.path.isfile(vcvars):
        raise SystemExit("vcvars64.bat not found at %s" % vcvars)
    write_gh("GITHUB_ENV", "VCVARS", vcvars)

    if not shutil.which("clang"):
        pf = os.environ.get("ProgramFiles", r"C:\Program Files")
        write_gh("GITHUB_ENV", "CLANG", os.path.join(pf, "LLVM", "bin", "clang.exe"))
        write_gh("GITHUB_ENV", "CLANGXX", os.path.join(pf, "LLVM", "bin", "clang++.exe"))

    sdk_url = subprocess.check_output(
        [sys.executable, os.path.join(ROOT, "tools", "ci_sdk.py"), "url"],
        text=True,
    ).strip()
    if not sdk_url:
        raise SystemExit("Could not resolve ReXGlue SDK URL.")
    log("Resolved SDK URL: %s" % sdk_url)
    write_gh("GITHUB_OUTPUT", "sdk_url", sdk_url)


def cmd_setup_sdk():
    if os.environ.get("CACHE_HIT") != "true":
        run_cmd([sys.executable, os.path.join(ROOT, "tools", "ci_sdk.py"), "install"])
    run_cmd([sys.executable, os.path.join(ROOT, "tools", "ci_sdk.py"), "verify"])


def cmd_fetch():
    game_url = (os.environ.get("GAME_URL") or "").strip()
    runner_temp = os.environ.get("RUNNER_TEMP") or os.path.join(ROOT, "_tmp")
    pw = (
        os.environ.get("EXTRACT_PASSWORD")
        or os.environ.get("ARCHIVE_PASSWORD")
        or DEFAULT_PASSWORD
    )
    run_cmd([
        sys.executable,
        os.path.join(ROOT, "tools", "ci_fetch.py"),
        game_url,
        os.path.join(runner_temp, "input"),
        "--password",
        pw,
    ])


def cmd_recompile():
    container = (os.environ.get("CONTAINER") or "").strip()
    if not container:
        raise SystemExit("The download step did not provide a container path.")
    project = normalize_project_name(os.environ.get("PROJECT_NAME", "game"))
    work = os.environ.get("REXAUTO_WORK") or os.path.join(ROOT, "autoports")
    pw = (
        os.environ.get("EXTRACT_PASSWORD")
        or os.environ.get("ARCHIVE_PASSWORD")
        or DEFAULT_PASSWORD
    )
    heal_iters = (os.environ.get("HEAL_ITERS") or "4").strip() or "4"
    runheal = (os.environ.get("RUNHEAL") or "").strip().lower() == "true"

    args = [
        sys.executable, "-u", os.path.join(ROOT, "rexauto.py"),
        container,
        "--name", project,
        "--password", pw,
        "--no-jumptables",
        "--heal-iters", heal_iters,
        "--publish-gabarito",
    ]
    if not runheal:
        args += ["--until", "build"]

    log_dir = os.path.join(work, project)
    os.makedirs(log_dir, exist_ok=True)
    log_path = os.path.join(log_dir, "_pipeline.log")
    log("exec: " + " ".join(args))
    with open(log_path, "w", encoding="utf-8", errors="replace") as lf:
        proc = subprocess.Popen(
            args,
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            lf.write(line)
            lf.flush()
        rc = proc.wait()
    if rc != 0:
        raise SystemExit("Rexauto recompilation failed with exit code %d." % rc)


def _zip_add_tree(zf, src_path, arc_prefix):
    for r, _, files in os.walk(src_path):
        for fn in sorted(files):
            full = os.path.join(r, fn)
            rel = os.path.relpath(full, src_path).replace("\\", "/")
            zf.write(full, "%s/%s" % (arc_prefix, rel))


def cmd_pack_port():
    project = normalize_project_name(os.environ.get("PROJECT_NAME", "game"))
    work = os.environ.get("REXAUTO_WORK") or os.path.join(ROOT, "autoports")
    runner_temp = os.environ.get("RUNNER_TEMP") or os.path.join(ROOT, "_tmp")
    container = (os.environ.get("CONTAINER") or "").strip()

    game_dir = os.path.join(work, project, "game")
    xex_target = None
    if os.path.isdir(game_dir):
        for r, _, files in os.walk(game_dir):
            for fn in files:
                if fn.lower() == "default.xex":
                    xex_target = os.path.join(r, fn)
                    break
            if xex_target:
                break
    target = xex_target or container
    meta_dir = os.path.join(runner_temp, "meta")
    if target and os.path.exists(target):
        run_cmd([sys.executable, os.path.join(ROOT, "tools", "title_meta.py"), target, meta_dir])
    else:
        log("Metadata fallback: using project name %s" % project)
        write_gh("GITHUB_OUTPUT", "title", project)
        write_gh("GITHUB_OUTPUT", "title_id", "00000000")

    port_dir = os.path.join(work, project, "port")
    sources_cmake = os.path.join(port_dir, "generated", "default", "sources.cmake")
    if not os.path.isfile(sources_cmake):
        raise SystemExit("Generated sources missing at %s" % sources_cmake)

    zip_path = os.path.join(runner_temp, "port-src.zip")
    os.makedirs(runner_temp, exist_ok=True)
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        _zip_add_tree(zf, os.path.join(port_dir, "generated"), "generated")
        _zip_add_tree(zf, os.path.join(port_dir, "src"), "src")
        for pat in ("*.toml", "*.cmake"):
            for fp in sorted(glob.glob(os.path.join(port_dir, pat))):
                if os.path.isfile(fp):
                    zf.write(fp, os.path.basename(fp))
    log("Packed port archive: %s (%.1f MB)" % (zip_path, os.path.getsize(zip_path) / 1e6))

    cover_src = os.path.join(meta_dir, "cover.png")
    if os.path.isfile(cover_src):
        shutil.copy2(cover_src, os.path.join(runner_temp, "cover.png"))


def cmd_build_apk():
    project = normalize_project_name(os.environ.get("PROJECT_NAME", "game"))
    runner_temp = os.environ.get("RUNNER_TEMP") or "/tmp"
    port_dir = os.path.join(ROOT, "autoports", project, "port")
    os.makedirs(port_dir, exist_ok=True)

    in_zip = os.path.join(runner_temp, "in", "port-src.zip")
    with zipfile.ZipFile(in_zip, "r") as zf:
        zf.extractall(port_dir)
    for req in (
        os.path.join(port_dir, "src", "main.cpp"),
        os.path.join(port_dir, "generated", "default", "sources.cmake"),
    ):
        if not os.path.isfile(req):
            raise SystemExit("Missing required unpacked port file: %s" % req)

    run_cmd(["sudo", "apt-get", "update"])
    run_cmd([
        "sudo", "apt-get", "install", "-y",
        "ninja-build", "libx11-xcb-dev", "libwayland-dev", "libwayland-client0",
        "unzip", "zip", "rsync",
    ])
    if subprocess.call([sys.executable, "-m", "pip", "install", "--break-system-packages", "-q", "pillow"]) != 0:
        run_cmd([sys.executable, "-m", "pip", "install", "-q", "pillow"])

    sdk_sh = os.path.join(ROOT, "tools", "android_sdk.sh")
    os.chmod(sdk_sh, 0o755)
    run_cmd([sdk_sh])

    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    sdkmanager = os.path.join(android_home, "cmdline-tools", "latest", "bin", "sdkmanager")
    subprocess.run(
        ["bash", "-lc", 'yes | "%s" --licenses >/dev/null 2>&1 || true' % sdkmanager],
        cwd=ROOT,
    )
    run_cmd([sdkmanager, "cmake;3.31.1", "ndk;27.2.12479018"])

    assets_dir = os.path.join(ROOT, "android", "app", "src", "main", "assets")
    res_dir = os.path.join(ROOT, "android", "app", "src", "main", "res")
    os.makedirs(assets_dir, exist_ok=True)
    shutil.copy2(os.path.join(ROOT, "tools", "xbox360_titles.json"), os.path.join(assets_dir, "xbox360_titles.json"))

    in_cover = os.path.join(runner_temp, "in", "cover.png")
    if os.path.isfile(in_cover):
        shutil.copy2(in_cover, os.path.join(assets_dir, "cover.png"))
        run_cmd([sys.executable, os.path.join(ROOT, "tools", "android_icon.py"), in_cover, res_dir])
    else:
        run_cmd([
            sys.executable,
            os.path.join(ROOT, "tools", "android_icon.py"),
            os.path.join(ROOT, "gui", "rexauto_icon.png"),
            res_dir,
        ])

    keystore = os.path.join(ROOT, "android", "ci-release.jks")
    if not os.path.isfile(keystore):
        run_cmd([
            "keytool", "-genkeypair",
            "-keystore", keystore,
            "-storepass", "rexauto",
            "-keypass", "rexauto",
            "-alias", "rexauto",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=rexauto, O=rexauto",
        ])

    env = dict(os.environ)
    if (env.get("BASELINE_ARM") or "").strip().lower() == "true":
        env["REX_ANDROID_BASELINE_ARM"] = "1"
    run_no = env.get("GITHUB_RUN_NUMBER") or "1"
    title = (env.get("REX_TITLE") or "").strip() or project
    title_id = (env.get("REX_TITLE_ID") or "").strip() or "00000000"

    gradlew = os.path.join(ROOT, "android", "gradlew")
    os.chmod(gradlew, 0o755)
    gradle_props = [
        "-PrexName=" + project,
        "-PrexTitle=" + title,
        "-PrexTitleId=" + title_id,
        "-PrexPortDir=" + port_dir,
        "-PrexSdkDir=" + os.path.join(ROOT, "android", "sdk", "rexglue-sdk"),
        "-PrexVersionCode=" + run_no,
        "-PrexVersionName=1." + run_no,
    ]
    gradle_log = os.path.join(ROOT, "android", "app", ".cxx", "gradle_build.log")
    run_cmd(
        [gradlew, ":app:compileReleaseJavaWithJavac", "--no-daemon", "--stacktrace"] + gradle_props,
        cwd=os.path.join(ROOT, "android"),
        env=env,
        log_path=gradle_log,
    )
    run_cmd(
        [gradlew, "assembleRelease", "--no-daemon", "--stacktrace"] + gradle_props,
        cwd=os.path.join(ROOT, "android"),
        env=env,
        log_path=gradle_log,
    )

    apk_path = os.path.join(ROOT, "android", "app", "build", "outputs", "apk", "release", "app-release.apk")
    if not os.path.isfile(apk_path):
        raise SystemExit("Built APK not found at %s" % apk_path)
    with zipfile.ZipFile(apk_path, "r") as zf:
        entries = set(zf.namelist())
    if "lib/arm64-v8a/libmain.so" not in entries:
        raise SystemExit("APK missing lib/arm64-v8a/libmain.so")
    if not any(n.startswith("lib/arm64-v8a/librexgpu-xenos") and n.endswith(".so") for n in entries):
        raise SystemExit("APK missing lib/arm64-v8a/librexgpu-xenos*.so")
    if "assets/xbox360_titles.json" not in entries:
        raise SystemExit("APK missing assets/xbox360_titles.json")

    out_apk = os.path.join(runner_temp, "%s-android.apk" % project)
    shutil.copy2(apk_path, out_apk)
    size_mb = os.path.getsize(apk_path) // (1024 * 1024)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as sf:
            sf.write("APK size: %d MB\nArchitecture: arm64-v8a\n" % size_mb)
    log("Verified Android APK: %s (%d MB)" % (out_apk, size_mb))


def cmd_collect_sources():
    project = normalize_project_name(os.environ.get("PROJECT_NAME", "game"))
    runner_temp = os.environ.get("RUNNER_TEMP") or "/tmp"
    out = os.path.join(runner_temp, "source-" + project)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out, exist_ok=True)
    run_cmd([
        "rsync", "-a",
        "--exclude", "sdk",
        "--exclude", "build",
        "--exclude", ".gradle",
        "--exclude", "*.jks",
        os.path.join(ROOT, "android") + "/",
        os.path.join(out, "android") + "/",
    ])
    port_dir = os.path.join(ROOT, "autoports", project, "port")
    if os.path.isdir(port_dir):
        run_cmd(["rsync", "-a", "--exclude", "build", port_dir + "/", os.path.join(out, "port") + "/"])
    shutil.copy2(os.path.join(ROOT, "tools", "android_sdk.sh"), os.path.join(out, "android_sdk.sh"))
    with open(os.path.join(out, "README.txt"), "w", encoding="utf-8") as rf:
        rf.write("%s Android project sources\n" % project)


COMMANDS = {
    "prepare-windows": cmd_prepare_windows,
    "setup-sdk": cmd_setup_sdk,
    "fetch": cmd_fetch,
    "recompile": cmd_recompile,
    "pack-port": cmd_pack_port,
    "build-apk": cmd_build_apk,
    "collect-sources": cmd_collect_sources,
}


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        raise SystemExit("Usage: ci_main.py {%s}" % "|".join(COMMANDS))
    COMMANDS[sys.argv[1]]()


if __name__ == "__main__":
    main()
