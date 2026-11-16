#!/usr/bin/env python3
"""Build an isolated verification plugin and run its checks inside CloudStream.

Usage: ANDROID_HOME=/path/to/sdk python3 tests/animeunity/run.py --adb /path/to/adb
Requires a running emulator with CloudStream and local plugin access enabled.
The temporary plugin and isolated preferences are removed after the run.
"""
import argparse
import pathlib
import shutil
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--output', default='/tmp/animeunity-smoke.log')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]

def adb(*cmd, **kwargs):
    return subprocess.run([args.adb, '-s', args.serial, *cmd], check=True, **kwargs)

def restart():
    adb('shell', 'am', 'force-stop', 'com.lagradost.cloudstream3', stdout=subprocess.DEVNULL)
    adb('shell', 'monkey', '-p', 'com.lagradost.cloudstream3', '1', stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

with tempfile.TemporaryDirectory(prefix='animeunity-smoke-') as tmp:
    staged = pathlib.Path(tmp) / 'repo'
    shutil.copytree(root, staged, ignore=shutil.ignore_patterns('.git', '.gradle', 'build', '__pycache__'))
    source = staged / 'AnimeUnity/src/main/kotlin/it/dogior/hadEnough'
    shutil.copy2(root / 'tests/animeunity/AnimeUnitySmoke.kt', source)
    plugin = source / 'AnimeUnityPlugin.kt'
    plugin.write_text(plugin.read_text().replace('registerMainAPI(AnimeUnity(sharedPref))', 'registerMainAPI(AnimeUnity(sharedPref))\n        runAnimeUnitySmoke(context)'))
    provider = source / 'AnimeUnity.kt'
    provider.write_text(provider.read_text().replace('override var name = Companion.name', 'override var name = "AnimeUnity smoke"'))
    subprocess.run(['bash', './gradlew', ':AnimeUnity:make', '--configure-on-demand', '--console=plain'], cwd=staged, check=True)
    artifact = staged / 'AnimeUnity/build/AnimeUnity.cs3'
    local = str(artifact)
    if args.adb.endswith('.exe'):
        local = subprocess.check_output(['wslpath', '-w', local], text=True).strip()
    remote = '/sdcard/Cloudstream3/plugins/AnimeUnitySmoke.cs3'
    adb('shell', 'mkdir', '-p', '/sdcard/Cloudstream3/plugins')
    adb('push', local, remote)
    # Timestamped tag output avoids clearing unrelated device diagnostics.
    since = subprocess.check_output([args.adb, '-s', args.serial, 'shell', 'date', r'+%m-%d\ %H:%M:%S.000'], text=True).strip()
    try:
        restart()
        deadline = time.monotonic() + 600
        previous = ''
        while time.monotonic() < deadline:
            output = subprocess.check_output([args.adb, '-s', args.serial, 'logcat', '-d', '-T', since, '-s', 'AnimeUnitySmoke:I', '*:S'], text=True)
            pathlib.Path(args.output).write_text(output)
            if output != previous:
                print(output[len(previous):], end='', flush=True)
                previous = output
            if 'COMPLETE ' in output:
                if 'FAIL ' in output:
                    raise SystemExit('Verification failed; see ' + args.output)
                break
            time.sleep(3)
        else:
            raise SystemExit('Timed out; see ' + args.output)
    finally:
        adb('shell', 'rm', '-f', remote)
        restart()
