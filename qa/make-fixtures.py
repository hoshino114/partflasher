#!/usr/bin/env python3
"""Generate deterministic IMG fixtures for the PartFlasher QA lab.

All files are reproducible: fixed timestamps / fixed pseudo-random data, so the
recorded SHA-256 sums are stable across runs. Run from anywhere:

    python3 qa/make-fixtures.py [--out qa/fixtures] [--force]

Fixtures:
    boot_v4.img          4 MiB Android boot image (ANDROID! header v4)
    boot_v3.img          4 MiB Android boot image (header v3)
    vendor_boot_v4.img   8 MiB vendor_boot image (VNDRBOOT header v4)
    dtbo.img             8 KiB DTBO image (0xd7b7ab1e magic)
    vbmeta.img           64 KiB AVB vbmeta image (AVB0 magic)
    recovery.img         8 MiB boot image variant
    ext4.img             8 MiB ext4-looking image (0xEF53 superblock magic)
    erofs.img            8 MiB EROFS-looking image (0xE0F5E1E2 @0x400)
    sparse.img           4 MiB Android sparse image (0xED26FF3A)
    boot_v4.img.gz       gzip-compressed boot image
    boot_v4.img.lz4      lz4 magic header
    boot_v4.img.zst      zstd magic header
    payload.bin          OTA payload magic ("CrAU")
    invalid.bin          256 B random garbage (unknown format)
    empty.img            0 B (empty image)
    oversized.img        132 MiB boot-like image (> 128 MiB lab partition)
    SHA256SUMS           sha256sum-compatible checksum file
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import struct
import sys
from pathlib import Path

MIB = 1024 * 1024
CURRENT = Path(__file__).resolve().parent
DEFAULT_OUT = CURRENT / "fixtures"


def fill(size: int, seed: int, offset: int = 0) -> bytes:
    """Deterministic pseudo-random bytes at a given absolute offset."""
    out = bytearray()
    counter = seed
    pos = offset
    while len(out) < size:
        counter = (counter * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        out += struct.pack("<Q", counter)
        pos += 8
    return bytes(out[:size])


def android_boot(header_version: int, size: int, seed: int) -> bytes:
    """Build an Android boot image header (magic ANDROID!)."""
    buf = bytearray(size)
    kernel_size = 2 * MIB
    ramdisk_size = 1 * MIB
    struct.pack_into("<8s", buf, 0, b"ANDROID!")
    struct.pack_into("<I", buf, 8, kernel_size)       # kernel_size
    struct.pack_into("<I", buf, 12, ramdisk_size)      # ramdisk_size
    struct.pack_into("<I", buf, 16, 0)                 # os_version
    struct.pack_into("<I", buf, 20, 1580)              # header_size (v0-v2) / v3+ unused
    struct.pack_into("<I", buf, 24, 0)                 # reserved[0] (v0-v2) / signature_size (v4)
    struct.pack_into("<I", buf, 28, 0)                 # reserved[1]
    struct.pack_into("<I", buf, 32, 0)                 # reserved[2]
    struct.pack_into("<I", buf, 36, 0)                 # reserved[3]
    struct.pack_into("<I", buf, 40, header_version)    # header_version
    cmdline = b"console=ttyMSM0,115200n8 androidboot.hardware=partflasher"
    buf[44:44 + len(cmdline)] = cmdline
    # Payload body: deterministic pattern (kernel + ramdisk area).
    buf[1580:1580 + kernel_size + ramdisk_size] = fill(kernel_size + ramdisk_size, seed)
    return bytes(buf)


def vendor_boot(size: int, seed: int) -> bytes:
    buf = bytearray(size)
    # struct vendor_boot_img_hdr_v4:
    # magic[8], header_version u32, page_size u32, kernel_addr u32,
    # ramdisk_addr u32, vendor_ramdisk_size u32, cmdline[2048],
    # tags_addr u32, name[16], header_size u32, dtb_size u32, dtb_addr u64,
    # vendor_ramdisk_table_size u32, vendor_ramdisk_table_entry_num u32,
    # vendor_ramdisk_table_entry_size u32, bootconfig_size u32
    struct.pack_into("<8s", buf, 0, b"VNDRBOOT")
    struct.pack_into("<I", buf, 8, 4)          # header_version
    struct.pack_into("<I", buf, 12, 4096)      # page_size
    struct.pack_into("<I", buf, 16, 0x10000000)  # kernel_addr
    struct.pack_into("<I", buf, 20, 0x11000000)  # ramdisk_addr
    struct.pack_into("<I", buf, 24, MIB)       # vendor_ramdisk_size
    cmdline = b"androidboot.hardware=partflasher"
    buf[28:28 + len(cmdline)] = cmdline
    struct.pack_into("<I", buf, 28 + 2048, 0x10000000)   # tags_addr
    buf[28 + 2048 + 4:28 + 2048 + 20] = b"partflasher".ljust(16, b"\0")
    struct.pack_into("<I", buf, 28 + 2048 + 20, 2128)    # header_size
    struct.pack_into("<Q", buf, 28 + 2048 + 28, 0)       # dtb_addr
    buf[4096:] = fill(size - 4096, seed)
    return bytes(buf)


def dtbo(size: int, seed: int) -> bytes:
    buf = bytearray(size)
    # struct dt_table_header: magic u32 (0xd7b7ab1e LE), total_size, header_size,
    # dt_entry_count, dt_entries_offset, page_size, version.
    struct.pack_into("<I", buf, 0, 0xD7B7AB1E)
    struct.pack_into("<I", buf, 4, size)       # total_size
    struct.pack_into("<I", buf, 8, 32)         # header_size
    struct.pack_into("<I", buf, 12, 1)         # dt_entry_count
    struct.pack_into("<I", buf, 16, 32)        # dt_entries_offset
    struct.pack_into("<I", buf, 20, 2048)      # page_size
    struct.pack_into("<I", buf, 24, 0)         # version
    buf[32:64] = fill(32, seed)
    return bytes(buf)


def vbmeta(size: int) -> bytes:
    buf = bytearray(size)
    # AVB vbmeta header: magic "AVB0", required_libavb_version_major/minor,
    # authentication_data_block_size, auxiliary_data_block_size, algorithm_type, ...
    struct.pack_into("<4s", buf, 0, b"AVB0")
    struct.pack_into("<I", buf, 4, 1)      # required_libavb_version_major
    struct.pack_into("<I", buf, 8, 0)      # required_libavb_version_minor
    struct.pack_into("<Q", buf, 12, 0)     # auth block size
    struct.pack_into("<Q", buf, 20, 0)     # aux block size
    struct.pack_into("<I", buf, 28, 1)     # algorithm type: SHA256_RSA2048
    struct.pack_into("<I", buf, 32, 0)     # hash offset
    return bytes(buf)


def ext4(size: int, seed: int) -> bytes:
    buf = bytearray(fill(size, seed))
    # ext4 superblock starts at 1024; magic 0xEF53 at offset 56 within it.
    base = 1024
    struct.pack_into("<H", buf, base + 56, 0xEF53)      # s_magic
    struct.pack_into("<I", buf, base + 0, 4096)          # s_inodes_count
    struct.pack_into("<I", buf, base + 4, size // 4096)  # s_blocks_count_lo
    struct.pack_into("<I", buf, base + 24, 2)            # s_log_block_size (4096)
    return bytes(buf)


def erofs(size: int, seed: int) -> bytes:
    buf = bytearray(fill(size, seed))
    struct.pack_into("<I", buf, 1024, 0xE0F5E1E2)  # EROFS_SUPER_MAGIC_V1
    return bytes(buf)


def sparse(size: int) -> bytes:
    """Valid raw-chunk Android sparse image expanding to 4 MiB."""
    data = android_boot(4, size, 0x5EED)
    blk = 4096
    total_blks = len(data) // blk
    chunk_data = len(data)
    hdr = struct.pack(
        "<IHHHHIIII", 0xED26FF3A, 1, 0, 28, 12, blk, total_blks, 1, 0
    )
    chunk = struct.pack("<HHII", 0xCAC1, 0, total_blks, 12 + chunk_data) + data
    return hdr + chunk


def payload() -> bytes:
    # Android OTA payload header: magic "CrAU", version u64, manifest size u64.
    return b"CrAU" + struct.pack("<Q", 2) + struct.pack("<Q", 0) + b"\0" * 1024


def write(path: Path, data: bytes) -> None:
    path.write_bytes(data)
    print(f"  {path.name:20s} {len(data):>10,d} bytes")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--force", action="store_true", help="regenerate if present")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    boot = android_boot(4, 4 * MIB, 0xB007)
    fixtures: dict[str, bytes] = {
        "boot_v4.img": boot,
        "boot_v3.img": android_boot(3, 4 * MIB, 0xB007),
        "recovery.img": android_boot(2, 8 * MIB, 0xB002),
        "vendor_boot_v4.img": vendor_boot(8 * MIB, 0x8E00),
        "dtbo.img": dtbo(8 * 1024, 0xD7B0),
        "vbmeta.img": vbmeta(64 * 1024),
        "ext4.img": ext4(8 * MIB, 0xE474),
        "erofs.img": erofs(8 * MIB, 0xE20F),
        "sparse.img": sparse(4 * MIB),
        "boot_v4.img.gz": gzip.compress(boot, compresslevel=6, mtime=0),
        "boot_v4.img.lz4": b"\x04\x22\x4d\x18" + boot[: 4 * MIB - 4],
        "boot_v4.img.zst": b"\x28\xb5\x2f\xfd" + boot[: 4 * MIB - 4],
        "payload.bin": payload(),
        "invalid.bin": fill(256, 0xBAD),
        "empty.img": b"",
        "oversized.img": android_boot(4, 132 * MIB, 0x0B05),
    }

    print(f"Generating fixtures in {out} ...")
    for name, data in fixtures.items():
        path = out / name
        if path.exists() and not args.force:
            print(f"  {name:20s} exists, skipped (--force to rewrite)")
            continue
        write(path, data)

    # Hash every fixture that should be stable.
    sums_path = out / "SHA256SUMS"
    lines = []
    for name in sorted(fixtures):
        path = out / name
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  {name}")
    sums_path.write_text("\n".join(lines) + "\n")
    print(f"  {sums_path.name:20s} {len(lines)} entries")
    print("Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
