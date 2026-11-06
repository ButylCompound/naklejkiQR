"""Build disposable debug update fixtures and test them on a Windows Android emulator."""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", default="emulator-5580")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("Use a disposable emulator; this check replaces the installed debug app.")

root = Path(__file__).resolve().parents[1]
out = root / "build/updater-review"
out.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
env["JAVA_HOME"] = "C:/Program Files/Android/Android Studio/jbr"
java = Path(env["JAVA_HOME"]) / "bin/java.exe"
sdk = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"
adb = sdk / "platform-tools/adb.exe"


def gradle(*tasks, init=None):
    subprocess.run([str(java), "-Dorg.gradle.appname=gradlew", "-classpath",
                    str(root / "gradle/wrapper/gradle-wrapper.jar"), "org.gradle.wrapper.GradleWrapperMain",
                    *tasks, *(["-I", str(init)] if init else []), "--offline", "--console=plain"],
                   cwd=root, env=env, check=True)


def device(*command, timeout=60):
    result = subprocess.run([str(adb), "-s", args.serial, *command], capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=timeout, check=True)
    return result.stdout + result.stderr


source = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
code = int(re.search(r"versionCode\s*=\s*(\d+)", source)[1]) + 1
name = re.search(r'versionName\s*=\s*"([^"]+)"', source)[1] + "." + str(code)
init = out / "version.gradle"
init.write_text("""gradle.beforeProject { p ->
    p.plugins.withId('com.android.application') {
        p.androidComponents.finalizeDsl { dsl ->
            dsl.defaultConfig.versionCode = %d
            dsl.defaultConfig.versionName = '%s'
        }
    }
}
""" % (code, name), encoding="utf-8")

gradle(":app:assembleDebug", init=init)
shutil.copy2(root / "app/build/outputs/apk/debug/app-debug.apk", out / "updater-new.apk")
key = out / "test-only.jks"
if not key.exists():
    subprocess.run([str(Path(env["JAVA_HOME"]) / "bin/keytool.exe"), "-genkeypair", "-keystore", str(key),
                    "-alias", "test", "-storepass", "updater-test-pass", "-keypass", "updater-test-pass",
                    "-dname", "CN=UpdaterTest", "-keyalg", "RSA", "-validity", "7"], check=True)
signer = sorted(sdk.glob("build-tools/*/lib/apksigner.jar"))[-1]
subprocess.run([str(java), "-jar", str(signer), "sign", "--ks", str(key), "--ks-pass", "pass:updater-test-pass",
                "--out", str(out / "updater-other-key.apk"), str(out / "updater-new.apk")], check=True)

gradle(":app:testDebugUnitTest", ":app:assembleDebug", ":app:assembleDebugAndroidTest", ":app:lintDebug")
print(device("wait-for-device"), flush=True)
print(device("install", "-r", "-d", str(root / "app/build/outputs/apk/debug/app-debug.apk")), flush=True)
tests = root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
print(device("install", "-r", "-t", str(tests)), flush=True)
folder = "/sdcard/Android/data/pl.almara.inwentaryzacja.debug/files/verification/"
device("shell", "mkdir", "-p", folder)
for path, name in [(out / "updater-new.apk", "updater-new.apk"),
                   (out / "updater-other-key.apk", "updater-other-key.apk"), (tests, "updater-other-package.apk")]:
    device("push", str(path), folder + name)
result = device("shell", "am", "instrument", "-w", "-r", "-e", "class",
                "pl.almara.inwentaryzacja.AppUpdateInstallTest,pl.almara.inwentaryzacja.PzStorageTest",
                "pl.almara.inwentaryzacja.debug.test/androidx.test.runner.AndroidJUnitRunner", timeout=600)
(out / "android-tests.txt").write_text(result, encoding="utf-8")
print(result)
if "OK (" not in result or "FAILURES!!!" in result:
    raise SystemExit("Updater instrumentation checks failed")
