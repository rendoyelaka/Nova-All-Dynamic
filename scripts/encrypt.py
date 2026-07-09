#!/usr/bin/env python3
"""
Nova Launcher — Build Time Encryption Script
CrystalSoft Technologies Ltd
Encrypts: classes.dex, res.zip, resources.arsc → .enc files
AES-256-CBC | PBKDF2 Key Derivation | Per-build unique salt
"""

import os
import sys
import secrets
import hashlib
import zipfile
import shutil
from pathlib import Path

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.backends import default_backend
except ImportError:
    print("[!] Installing cryptography...")
    os.system("pip3 install cryptography --break-system-packages -q")
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.backends import default_backend


# ── Paths ────────────────────────────────────────────────────────────────────
SCRIPT_DIR   = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
APK_EXTRACT  = Path("/tmp/nova_apk_extracted")
OUTPUT_DIR   = PROJECT_ROOT / "app/src/main/assets"
TEMP_DIR     = Path("/tmp/nova_encrypt_tmp")


# ── Key Derivation ───────────────────────────────────────────────────────────
def derive_key(build_id: str, salt: bytes) -> bytes:
    base = f"CrystalSoft-Nova-{build_id}".encode()
    return hashlib.pbkdf2_hmac('sha256', base, salt, 310000, dklen=32)


# ── Encryption ───────────────────────────────────────────────────────────────
def encrypt_file(input_path: Path, output_path: Path, key: bytes, salt: bytes):
    if not input_path.exists():
        print(f"[SKIP] Not found: {input_path}")
        return False

    iv = secrets.token_bytes(16)

    with open(input_path, 'rb') as f:
        data = f.read()

    # PKCS7 padding
    pad_len = 16 - (len(data) % 16)
    data += bytes([pad_len] * pad_len)

    cipher = Cipher(
        algorithms.AES(key),
        modes.CBC(iv),
        backend=default_backend()
    )
    encryptor = cipher.encryptor()
    encrypted = encryptor.update(data) + encryptor.finalize()

    # Format: MAGIC(4) + VERSION(1) + SALT(32) + IV(16) + DATA
    MAGIC   = b'NOVA'
    VERSION = b'\x01'

    with open(output_path, 'wb') as f:
        f.write(MAGIC + VERSION + salt + iv + encrypted)

    size_in  = input_path.stat().st_size
    size_out = output_path.stat().st_size
    print(f"[OK] {input_path.name} ({size_in:,}b) -> {output_path.name} ({size_out:,}b)")
    return True


# ── Extract APK ──────────────────────────────────────────────────────────────
def extract_apk(apk_path: Path) -> bool:
    if APK_EXTRACT.exists():
        shutil.rmtree(APK_EXTRACT)
    APK_EXTRACT.mkdir(parents=True)

    try:
        with zipfile.ZipFile(apk_path, 'r') as z:
            z.extractall(APK_EXTRACT)
        print(f"[OK] APK extracted to {APK_EXTRACT}")
        return True
    except Exception as e:
        print(f"[X] APK extraction failed: {e}")
        return False


# ── Zip res/ Folder ──────────────────────────────────────────────────────────
def zip_res_folder(res_path: Path, output_zip: Path) -> bool:
    if not res_path.exists():
        print(f"[SKIP] res/ folder not found: {res_path}")
        return False

    with zipfile.ZipFile(output_zip, 'w', zipfile.ZIP_DEFLATED) as z:
        for file in res_path.rglob('*'):
            if file.is_file():
                arcname = file.relative_to(res_path.parent)
                z.write(file, arcname)

    print(f"[OK] res/ zipped -> {output_zip.name} ({output_zip.stat().st_size:,}b)")
    return True


# ── Cleanup ──────────────────────────────────────────────────────────────────
def cleanup():
    for p in [APK_EXTRACT, TEMP_DIR]:
        if p.exists():
            shutil.rmtree(p)
    print("[OK] Temp files cleaned up")


# ── Main ─────────────────────────────────────────────────────────────────────
def main():
    print("=" * 55)
    print("  Nova Launcher - Encryption Script")
    print("  CrystalSoft Technologies Ltd")
    print("=" * 55)

    # Find APK
    apk_search = list((PROJECT_ROOT / "app/build/outputs/apk/release").glob("*.apk"))
    if not apk_search:
        print("[X] No APK found. Run ./gradlew assembleRelease first.")
        sys.exit(1)

    apk_path = apk_search[0]
    print(f"[OK] APK: {apk_path} ({apk_path.stat().st_size:,}b)")

    # Setup dirs
    TEMP_DIR.mkdir(parents=True, exist_ok=True)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    # Generate build identity
    build_id = secrets.token_hex(16)
    salt     = secrets.token_bytes(32)
    key      = derive_key(build_id, salt)
    key_fp   = hashlib.sha256(key).hexdigest()

    print(f"\n[OK] Build ID : {build_id}")
    print(f"[OK] Key FP   : {key_fp[:16]}...")
    print()

    # Extract APK
    if not extract_apk(apk_path):
        sys.exit(1)

    # Source files
    classes_dex   = APK_EXTRACT / "classes.dex"
    res_folder    = APK_EXTRACT / "res"
    resources_arc = APK_EXTRACT / "resources.arsc"
    res_zip       = TEMP_DIR / "res.zip"

    # Zip res/
    zip_res_folder(res_folder, res_zip)

    # Encrypt
    print("\n[*] Encrypting files...")
    results = {
        'classes'   : encrypt_file(classes_dex,  OUTPUT_DIR / "classes.enc",   key, salt),
        'res'       : encrypt_file(res_zip,       OUTPUT_DIR / "res.enc",       key, salt),
        'resources' : encrypt_file(resources_arc, OUTPUT_DIR / "resources.enc", key, salt),
    }

    # Write build.meta
    with open(OUTPUT_DIR / "build.meta", 'w') as f:
        f.write(f"BUILD_ID={build_id}\n")
        f.write(f"KEY_FP={key_fp}\n")
        f.write(f"SALT={salt.hex()}\n")
    print(f"[OK] build.meta written")

    # Summary
    print("\n" + "=" * 55)
    print("  Encryption Summary")
    print("=" * 55)
    for name, ok in results.items():
        print(f"  [{'OK' if ok else 'SKIP'}] {name}.enc")
    print(f"\n  Output : {OUTPUT_DIR}")
    print("=" * 55)

    cleanup()
    print("\n[DONE] Ready to rebuild APK")


if __name__ == "__main__":
    main()
