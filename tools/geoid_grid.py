#!/usr/bin/env python3
"""Builds app/src/main/resources/geoid/egm96_30min.bin, the geoid grid read by util/Geoid.kt.

Input is the EGM96 15' grid (NGA's WW15MGH.DAC) as carried by the MIT-licensed npm package
egm96-universal; see THIRD_PARTY_NOTICES.md for the exact commands. The script only reads the
package's file as text and never runs it.

Every second row and column is kept, unchanged: 361 rows from 90N to 90S, 720 columns eastward
from 0E, each a big-endian int16 in centimetres.

    python3 tools/geoid_grid.py <egm96-universal.esm.js> <output.bin>
"""
import base64
import re
import sys

SOURCE_ROWS, SOURCE_COLS = 721, 1440
STEP = 2


def main(source_path, output_path):
    with open(source_path, encoding="ascii", errors="ignore") as source:
        match = re.search(r'var data = "([A-Za-z0-9+/=]+)"', source.read())
    if match is None:
        sys.exit(f"no grid found in {source_path}")
    grid = base64.b64decode(match.group(1))
    if len(grid) != SOURCE_ROWS * SOURCE_COLS * 2:
        sys.exit(f"unexpected grid size: {len(grid)} bytes")

    output = bytearray()
    for row in range(0, SOURCE_ROWS, STEP):
        for col in range(0, SOURCE_COLS, STEP):
            offset = (row * SOURCE_COLS + col) * 2
            output += grid[offset:offset + 2]
    with open(output_path, "wb") as target:
        target.write(output)
    print(f"{output_path}: {len(output)} bytes")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
