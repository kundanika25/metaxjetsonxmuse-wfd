from pathlib import Path


# The Android app must only talk to the Jetson gateway. Cloud AI (Muse) is an opt-in
# gateway backend; its endpoint and key must never be compiled into the APK.
REPO_ROOT = Path(__file__).resolve().parents[2]
SCAN_TARGETS = [
    REPO_ROOT / "app" / "build.gradle.kts",
    REPO_ROOT / "app" / "src" / "main" / "java",
]
FORBIDDEN = [
    "generativelanguage.googleapis.com",
    "api.groq.com",
    "api.x.ai",
    "api.meta.ai",
    "GEMINI_API_KEY",
    "GROQ_API_KEY",
    "XAI_API_KEY",
    "MUSE_API_KEY",
]


def test_android_runtime_contains_no_cloud_ai_transport():
    files = []
    for target in SCAN_TARGETS:
        files.extend(target.rglob("*.kt") if target.is_dir() else [target])
    combined = "\n".join(path.read_text(encoding="utf-8") for path in files)
    found = [value for value in FORBIDDEN if value in combined]
    assert not found, f"Cloud AI transport identifiers remain: {found}"
