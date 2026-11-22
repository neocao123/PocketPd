#!/usr/bin/env python3
"""Verify the depth-aware block scan preserves structure on superosc~.pd's
depth-4 route block (the crash trigger). Port of the NEW injectBlockRelays
core: block attribution + pmsg ordinal assignment."""
import sys

def split_statements(text):
    out, sb, esc = [], [], False
    for ch in text:
        if esc:
            sb.append(ch); esc = False; continue
        if ch == '\\':
            sb.append(ch); esc = True; continue
        if ch == ';':
            out.append("".join(sb)); sb = []; continue
        sb.append(ch)
    return out


def block_restore(lines, open_, until=None):
    if until is None:
        until = len(lines)
    depth = 1
    for j in range(open_ + 1, until):
        t = lines[j].lstrip()
        if t.startswith("#N canvas"):
            depth += 1
        elif t.startswith("#X restore"):
            depth -= 1
            if depth == 0:
                return j
    return -1


def inject(lines, ord_counter):
    if not lines:
        return [], []
    start = 1 if lines[0].startswith("#N canvas") else 0
    end = len(lines)
    if end > start and lines[end - 1].startswith("#X restore"):
        end -= 1
    body, slots, idx, i = [], [], 0, start
    while i < end:
        t = lines[i]
        if t.startswith("#N canvas"):
            close = block_restore(lines, i, end)
            if close >= 0:
                b, s = inject(lines[i:close + 1], ord_counter)
                body.extend(b); slots.extend(s); idx += 1; i = close + 1
                continue
        body.append(t)
        if t.startswith("#X msg"):
            slots.append((idx, ord_counter[0])); ord_counter[0] += 1
        if t.startswith(("#X obj", "#X msg", "#X text", "#X floatatom",
                         "#X symbolatom", "#X listbox")):
            idx += 1
        i += 1
    out = lines[:start] + body + ["<<RELAY>>"] * len(slots) + lines[end:]
    return out, slots


def main():
    src = open("../app/src/main/assets/slibs/arm64-v8a/else/superosc~.pd",
               encoding="utf-8").read()
    stmts = [s.strip() for s in split_statements(src) if s.strip()]
    # stmts[0] is the doc's own root header, not a block — scan after it
    stmts = stmts[1:]
    blocks, i = [], 0
    while i < len(stmts):
        if stmts[i].startswith("#N canvas"):
            close = block_restore(stmts, i)
            if close >= 0:
                blocks.append(stmts[i:close + 1]); i = close + 1
            else:
                blocks.append(stmts[i:]); break
        else:
            i += 1
    route = next(b for b in blocks if " route" in b[0])
    opens = sum(1 for l in route if l.startswith("#N canvas"))
    restores = sum(1 for l in route if l.startswith("#X restore"))
    print("route block lines:", len(route), "opens:", opens, "restores:", restores)

    counter = [0]
    out, slots = inject(route, counter)
    o_opens = sum(1 for l in out if l.startswith("#N canvas"))
    o_restores = sum(1 for l in out if l.startswith("#X restore"))
    print("out opens:", o_opens, "out restores:", o_restores,
          "balanced:", o_opens - o_restores == 1)
    print("ordinals (slotIdx, ord):", slots)
    nonrelay_in = [l for l in route if l != "<<RELAY>>"]
    nonrelay_out = [l for l in out if l != "<<RELAY>>"]
    print("structure preserved:", nonrelay_in == nonrelay_out)
    return 0 if (o_opens - o_restores == 1 and nonrelay_in == nonrelay_out) else 1


if __name__ == "__main__":
    sys.exit(main())
