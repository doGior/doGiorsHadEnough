# AnimeUnity integration checks

Run from the repository root on Linux/WSL with an Android SDK, Java, Python 3,
and a running emulator with CloudStream installed. Enable local plugin access
in CloudStream first.

    ANDROID_HOME=/path/to/Android/Sdk python3 tests/animeunity/run.py --adb /path/to/platform-tools/adb --serial emulator-5554

For a Windows-hosted emulator from WSL, pass the Windows SDK's adb.exe.
The runner converts the temporary artifact's path using wslpath.

The runner copies the repository into a temporary directory, injects the test
entry point into that copy, builds it, and loads it as AnimeUnitySmoke.cs3.
The production plugin contains no test entry point. Tests use a separate
AnimeUnityCxSmoke preference file, clear that file, and remove the temporary
plugin on completion (including failures). The app is restarted for loading
and cleanup. The default output is /tmp/animeunity-smoke.log.

The checks exercise the provider inside the actual CloudStream host: domain
migration, all home sections, section visibility/naming, pagination with
multiple configured page sizes, search/display settings, separate and unified
language variants, episode fallback, a series with more than 1,000 episodes,
legacy title/episode URLs, advanced filters and sort orders, and both player
source languages. The suite deliberately pauses between scenarios to respect
the public API's request limit; allow several minutes.

These are live integration checks, so catalog changes, unavailable upstream
services, or API throttling can cause failures. Calendar emptiness is allowed
when no anime is scheduled. Extracted player links are checked, but the suite
does not decode or render media: playback rendering needs a separate manual
check. Old title links are resolved by an exact slug match; old episode IDs
cannot be safely translated and produce an instruction to reopen the title.
