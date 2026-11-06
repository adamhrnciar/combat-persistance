#!/usr/bin/env python3
"""Extract packet registration order from an official, unobfuscated server JAR.

This validation helper reads class files without starting another JVM. It does
not alter the game or the release artifact. Its output feeds raw_probe.mjs.
"""

import json
import struct
import sys
import zipfile


def instructions(code):
    """Walk JVM instruction boundaries, including switch padding and wide."""
    widths = {0x10: 2, 0x11: 3, 0x12: 2, 0x13: 3, 0x14: 3, 0x84: 3,
              0xA9: 2, 0xB9: 5, 0xBA: 5, 0xBB: 3, 0xBC: 2, 0xBD: 3,
              0xC0: 3, 0xC1: 3, 0xC5: 4, 0xC6: 3, 0xC7: 3, 0xC8: 5, 0xC9: 5}
    widths.update({opcode: 2 for opcode in (*range(0x15, 0x1A), *range(0x36, 0x3B))})
    widths.update({opcode: 3 for opcode in (*range(0x99, 0xA9), *range(0xB2, 0xB9))})
    offset = 0
    while offset < len(code):
        opcode = code[offset]
        yield offset, opcode
        if opcode in (0xAA, 0xAB):
            aligned = (offset + 4) & ~3
            if opcode == 0xAA:
                _, low, high = struct.unpack_from(">iii", code, aligned)
                following = aligned + 12 + (high - low + 1) * 4
            else:
                _, pairs = struct.unpack_from(">ii", code, aligned)
                following = aligned + 8 + pairs * 8
        elif opcode == 0xC4:
            following = offset + (6 if code[offset + 1] == 0x84 else 4)
        else:
            following = offset + widths.get(opcode, 1)
        if following <= offset or following > len(code):
            raise ValueError("Malformed JVM instruction")
        offset = following


def registrations(jar, name):
    data = jar.read(name.replace(".", "/") + ".class")
    position = 8

    def u2(offset):
        return struct.unpack_from(">H", data, offset)[0]

    count = u2(position)
    position += 2
    pool = [None] * count
    index = 1
    while index < count:
        tag = data[position]
        position += 1
        if tag == 1:
            length = u2(position)
            position += 2
            pool[index] = (tag, data[position:position + length].decode())
            position += length
        elif tag in (3, 4):
            position += 4
        elif tag in (5, 6):
            position += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, u2(position))
            position += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, u2(position), u2(position + 2))
            position += 4
        elif tag == 15:
            position += 3
        else:
            raise ValueError(f"Unsupported constant pool tag {tag}")
        index += 1

    def utf(index):
        return pool[index][1]

    result = {}
    position += 6
    position += 2 + u2(position) * 2
    for group in ("fields", "methods"):
        count = u2(position)
        position += 2
        for _ in range(count):
            _, name_index, _, attributes = struct.unpack_from(">HHHH", data, position)
            position += 8
            for _ in range(attributes):
                attribute, length = struct.unpack_from(">HI", data, position)
                position += 6
                if group == "methods" and utf(attribute) == "Code":
                    size = struct.unpack_from(">I", data, position + 4)[0]
                    code = data[position + 8:position + 8 + size]
                    fields = []
                    # Registration lambdas contain getstatic references in wire
                    # order. Reject ambiguity rather than infer packet IDs.
                    for offset, opcode in instructions(code):
                        if opcode != 178:  # getstatic
                            continue
                        index = struct.unpack_from(">H", code, offset + 1)[0]
                        if not 0 < index < len(pool) or not pool[index] or pool[index][0] != 9:
                            continue
                        _, owner, name_and_type = pool[index]
                        if "PacketTypes" not in utf(pool[owner][1]):
                            continue
                        fields.append(utf(pool[name_and_type][1]))
                    if fields:
                        directions = {field.split("_", 1)[0] for field in fields}
                        if len(directions) != 1 or len(set(fields)) != len(fields):
                            raise ValueError(f"Ambiguous registrations in {utf(name_index)}")
                        direction = next(iter(directions)).lower()
                        if direction in result:
                            raise ValueError(f"Multiple {direction} registration methods")
                        result[direction] = {field: index for index, field in enumerate(fields)}
                position += length
    return result


if __name__ == "__main__":
    with zipfile.ZipFile(sys.argv[1]) as jar:
        output = {state: registrations(jar, f"net.minecraft.network.protocol.{state}.{name}Protocols")
                  for state, name in (("login", "Login"), ("configuration", "Configuration"), ("game", "Game"))}
    print(json.dumps(output, indent=2))
