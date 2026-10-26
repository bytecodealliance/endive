#!/usr/bin/env python3
"""Turns the two benchmark CSVs into a Markdown table and an SVG chart.

Standard library only, so the benchmark has no dependencies beyond a JDK,
Maven and Python 3.
"""

import argparse
import csv
import statistics


def read_series(path):
    rows = []
    with open(path, newline="") as fh:
        for row in csv.DictReader(fh):
            rows.append((int(row["window"]), float(row["ms_per_iter"]), int(row["checksum"])))
    rows.sort()
    return [r[1] for r in rows], rows[0][2]


def steady(vals):
    """Mean of the last third: past warm-up, before any tail effects."""
    tail = max(1, len(vals) // 3)
    return statistics.fmean(vals[-tail:])


def fmt(x):
    return f"{x:,.1f}"


def svg(before, after, path):
    w, h = 900, 420
    pad_l, pad_r, pad_t, pad_b = 70, 150, 54, 56
    pw, ph = w - pad_l - pad_r, h - pad_t - pad_b
    n = max(len(before), len(after))
    ymax = max(max(before), max(after)) * 1.12

    def X(i):
        return pad_l + (pw * i / max(1, n - 1))

    def Y(v):
        return pad_t + ph - (ph * v / ymax)

    def poly(vals):
        return " ".join(f"{X(i):.1f},{Y(v):.1f}" for i, v in enumerate(vals))

    # y gridlines at rounded intervals
    step = 10 ** (len(str(int(ymax // 4))) - 1) or 1
    tick = max(step, round(ymax / 4 / step) * step)
    ticks, t = [], 0.0
    while t <= ymax:
        ticks.append(t)
        t += tick

    C_BEFORE, C_AFTER, FG, MUTED, GRID = "#b4472f", "#2f6fb4", "#1b1b1b", "#6b6b6b", "#e3e3e3"
    p = []
    p.append(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
        f'viewBox="0 0 {w} {h}" font-family="-apple-system,Segoe UI,Helvetica,Arial,sans-serif">'
    )
    p.append(f'<rect width="{w}" height="{h}" fill="#ffffff"/>')
    p.append(
        f'<text x="{pad_l}" y="26" font-size="17" font-weight="600" fill="{FG}">'
        f"QuickJS throughput under quickjs4j</text>"
    )
    p.append(
        f'<text x="{pad_l}" y="44" font-size="12" fill="{MUTED}">'
        f"lower is faster &#183; each window is the mean of several full "
        f"compile-and-execute iterations</text>"
    )
    for tv in ticks:
        y = Y(tv)
        p.append(
            f'<line x1="{pad_l}" y1="{y:.1f}" x2="{pad_l+pw}" y2="{y:.1f}" stroke="{GRID}"/>'
        )
        p.append(
            f'<text x="{pad_l-10}" y="{y+4:.1f}" font-size="11" fill="{MUTED}" '
            f'text-anchor="end">{int(tv)}</text>'
        )
    p.append(
        f'<text x="{pad_l-52}" y="{pad_t+ph/2:.1f}" font-size="11" fill="{MUTED}" '
        f'transform="rotate(-90 {pad_l-52} {pad_t+ph/2:.1f})" text-anchor="middle">'
        f"ms per iteration</text>"
    )
    p.append(
        f'<line x1="{pad_l}" y1="{pad_t+ph}" x2="{pad_l+pw}" y2="{pad_t+ph}" stroke="#bdbdbd"/>'
    )
    p.append(
        f'<text x="{pad_l+pw/2:.1f}" y="{h-18}" font-size="11" fill="{MUTED}" '
        f'text-anchor="middle">measurement window</text>'
    )
    for i in range(n):
        p.append(
            f'<text x="{X(i):.1f}" y="{pad_t+ph+16:.1f}" font-size="10" fill="{MUTED}" '
            f'text-anchor="middle">{i}</text>'
        )

    for vals, colour in ((before, C_BEFORE), (after, C_AFTER)):
        p.append(
            f'<polyline points="{poly(vals)}" fill="none" stroke="{colour}" '
            f'stroke-width="2.5" stroke-linejoin="round"/>'
        )
        for i, v in enumerate(vals):
            p.append(f'<circle cx="{X(i):.1f}" cy="{Y(v):.1f}" r="3" fill="{colour}"/>')

    lx = pad_l + pw + 18
    for idx, (name, vals, colour) in enumerate(
        (("before", before, C_BEFORE), ("after", after, C_AFTER))
    ):
        ly = pad_t + 10 + idx * 46
        p.append(
            f'<line x1="{lx}" y1="{ly}" x2="{lx+22}" y2="{ly}" stroke="{colour}" '
            f'stroke-width="2.5"/>'
        )
        p.append(
            f'<text x="{lx+28}" y="{ly+4}" font-size="12" font-weight="600" '
            f'fill="{FG}">{name}</text>'
        )
        p.append(
            f'<text x="{lx}" y="{ly+20}" font-size="11" fill="{MUTED}">'
            f"steady {fmt(steady(vals))} ms</text>"
        )
    ratio = steady(before) / steady(after)
    p.append(
        f'<text x="{lx}" y="{pad_t+ph-6}" font-size="15" font-weight="700" '
        f'fill="{C_AFTER}">{ratio:.1f}x faster</text>'
    )
    p.append("</svg>")
    with open(path, "w") as fh:
        fh.write("\n".join(p))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", required=True)
    ap.add_argument("--after", required=True)
    ap.add_argument("--before-split", default="?")
    ap.add_argument("--after-split", default="?")
    ap.add_argument("--before-interp", default="?")
    ap.add_argument("--after-interp", default="?")
    ap.add_argument("--base-rev", default="")
    ap.add_argument("--head-rev", default="")
    ap.add_argument("--out-md", required=True)
    ap.add_argument("--out-svg", required=True)
    a = ap.parse_args()

    before, cb = read_series(a.before)
    after, ca = read_series(a.after)

    sb, sa = steady(before), steady(after)
    md = []
    md.append("# QuickJS throughput: splitting oversized functions for the JIT\n")
    md.append(
        "Identical quickjs4j source and JVM in both rows. The only difference is "
        "which build of the endive compiler precompiled the Javy plugin bytecode "
        "inside the quickjs4j jar.\n"
    )
    # The base is an upstream commit, so naming it is stable. The "after" side is
    # this branch's tip, whose hash does not survive a rebase or a squash-merge --
    # so describe it rather than pinning a hash that goes stale on its own.
    md.append(f"- **before** &mdash; endive at `{a.base_rev}`")
    md.append(f"- **after** &nbsp;&mdash; endive at this branch ({a.head_rev})\n")

    md.append("| | first window | steady state | best | functions split | interpreted |")
    md.append("|---|---|---|---|---|---|")
    md.append(
        f"| before | {fmt(before[0])} ms | **{fmt(sb)} ms** | {fmt(min(before))} ms "
        f"| {a.before_split} | {a.before_interp} |"
    )
    md.append(
        f"| after | {fmt(after[0])} ms | **{fmt(sa)} ms** | {fmt(min(after))} ms "
        f"| {a.after_split} | {a.after_interp} |"
    )
    md.append(f"| | | **{sb/sa:.1f}x faster** | {min(before)/min(after):.1f}x | | |\n")

    md.append(
        f"Both builds computed the same checksum "
        f"(`{cb}`{'' if cb == ca else f' vs `{ca}` &mdash; MISMATCH'}), "
        f"so the two rows ran the same work.\n"
    )

    md.append("## Per-window timings\n")
    md.append("| window | before (ms) | after (ms) |")
    md.append("|---|---|---|")
    for i in range(max(len(before), len(after))):
        b = fmt(before[i]) if i < len(before) else ""
        c = fmt(after[i]) if i < len(after) else ""
        md.append(f"| {i} | {b} | {c} |")
    md.append("")
    md.append(
        "The shape matters more than the totals. `before` is flat: a method past "
        "HotSpot's `HugeMethodLimit` is never JIT-compiled, so it is as slow in the "
        "last window as the first. `after` starts comparable and improves as HotSpot "
        "compiles the chunks.\n"
    )
    md.append("![chart](results.svg)\n")

    with open(a.out_md, "w") as fh:
        fh.write("\n".join(md))
    svg(before, after, a.out_svg)

    if cb != ca:
        raise SystemExit("checksums differ between runs: results are not comparable")


if __name__ == "__main__":
    main()
