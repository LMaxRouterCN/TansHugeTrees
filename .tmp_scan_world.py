import sys, os, struct, zlib
TARGETS = [
 b"minecraft:oak_log", b"minecraft:oak_wood", b"minecraft:oak_leaves",
 b"minecraft:birch_log", b"minecraft:birch_wood", b"minecraft:birch_leaves",
 b"minecraft:spruce_log", b"minecraft:spruce_wood", b"minecraft:spruce_leaves",
 b"minecraft:stripped_oak_wood", b"minecraft:stripped_birch_wood", b"minecraft:stripped_spruce_wood",
 b"minecraft:packed_ice", b"minecraft:snow_block",
]
def scan_region(path):
    with open(path, "rb") as f:
        data = f.read()
    counts = {}
    chunks = 0
    for i in range(1024):
        off = int.from_bytes(data[i*4:i*4+3], "big") * 4096
        if off == 0 or off + 5 > len(data):
            continue
        clen = struct.unpack(">I", data[off:off+4])[0]
        if clen < 2 or off + 5 + clen - 1 > len(data):
            continue
        ctype = data[off+4]
        raw = data[off+5:off+5+clen-1]
        try:
            if ctype == 2:
                nd = zlib.decompress(raw)
            elif ctype == 1:
                import gzip
                nd = gzip.decompress(raw)
            elif ctype == 3:
                nd = raw
            else:
                continue
        except Exception:
            continue
        chunks += 1
        for tg in TARGETS:
            c = nd.count(tg)
            if c:
                counts[tg.decode()] = counts.get(tg.decode(), 0) + c
    return chunks, counts
for region_dir in sys.argv[1:]:
    if not os.path.isdir(region_dir):
        print("MISSING", region_dir)
        continue
    total = {}
    nchunks = 0
    nfiles = 0
    for fn in sorted(os.listdir(region_dir)):
        if not fn.endswith(".mca"):
            continue
        nfiles += 1
        try:
            c, k = scan_region(os.path.join(region_dir, fn))
        except Exception as e:
            print("ERR", fn, repr(e))
            continue
        nchunks += c
        for kk, vv in k.items():
            total[kk] = total.get(kk, 0) + vv
    print("WORLD", region_dir, "files=", nfiles, "chunks=", nchunks)
    if total:
        for kk in sorted(total):
            print("   ", kk, "=", total[kk])
    else:
        print("    NO-TREE-BLOCKS")
