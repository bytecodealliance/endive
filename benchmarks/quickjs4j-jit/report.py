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


def svg(before, after, redline, path):
    w, h = 900, 420
    pad_l, pad_r, pad_t, pad_b = 70, 150, 54, 56
    pw, ph = w - pad_l - pad_r, h - pad_t - pad_b
    series = [s for s in (before, after, redline) if s]
    n = max(len(s) for s in series)
    ymax = max(max(s) for s in series) * 1.12

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
    C_REDLINE = "#2e8b57"
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

    plots = [(before, C_BEFORE), (after, C_AFTER)]
    if redline:
        plots.append((redline, C_REDLINE))
    for vals, colour in plots:
        p.append(
            f'<polyline points="{poly(vals)}" fill="none" stroke="{colour}" '
            f'stroke-width="2.5" stroke-linejoin="round"/>'
        )
        for i, v in enumerate(vals):
            p.append(f'<circle cx="{X(i):.1f}" cy="{Y(v):.1f}" r="3" fill="{colour}"/>')

    lx = pad_l + pw + 18
    legend = [("before", before, C_BEFORE), ("after", after, C_AFTER)]
    if redline:
        legend.append(("redline", redline, C_REDLINE))
    for idx, (name, vals, colour) in enumerate(legend):
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
    ap.add_argument("--redline")
    ap.add_argument("--redline-endive", default="")
    ap.add_argument("--redline-runner", default="")
    ap.add_argument("--redline-rev", default="")
    ap.add_argument("--jdk", default="")
    ap.add_argument("--workload", default="")
    ap.add_argument("--out-md", required=True)
    ap.add_argument("--out-svg", required=True)
    a = ap.parse_args()

    before, cb = read_series(a.before)
    after, ca = read_series(a.after)
    redline, cr = (read_series(a.redline) if a.redline else (None, None))

    sb, sa = steady(before), steady(after)
    md = []
    md.append("# QuickJS throughput: splitting oversized functions for the JIT\n")
    if a.workload:
        md.append(f"Workload: **{a.workload}**.\n")
    md.append(
        "Same quickjs4j source and JVM throughout. `before` and `after` differ only "
        "in which build of the endive compiler precompiled the Javy plugin bytecode "
        "inside the quickjs4j jar; `redline`, where present, swaps the bytecode "
        "backend for natively compiled code and is a reference point rather than "
        "part of the before/after comparison.\n"
    )
    # The base is an upstream commit, so naming it is stable. The "after" side is
    # this branch's tip, whose hash does not survive a rebase or a squash-merge --
    # so describe it rather than pinning a hash that goes stale on its own.
    md.append(f"- **before** &mdash; endive at `{a.base_rev}`")
    md.append(f"- **after** &nbsp;&mdash; endive at this branch ({a.head_rev})")
    if redline:
        md.append(
            f"- **redline** &mdash; endive "
            f"{'`main`' if (a.redline_endive or '').endswith('SNAPSHOT') else '`' + a.redline_endive + '`'}"
            f", native via Cranelift"
        )
    if a.jdk:
        md.append(f"\nMeasured on `{a.jdk}`.")
    md.append("")

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
    if redline:
        sr = steady(redline)
        md.append(
            f"| redline (native) | {fmt(redline[0])} ms | **{fmt(sr)} ms** | "
            f"{fmt(min(redline))} ms | n/a | n/a |"
        )
    md.append(f"| | | **{sb/sa:.1f}x faster** | {min(before)/min(after):.1f}x | | |\n")

    if redline:
        sr = steady(redline)
        if sr < sa:
            md.append(
                f"The Redline backend, which compiles to native code via Cranelift instead "
                f"of to JVM bytecode, reaches **{fmt(sr)} ms** steady -- {sa/sr:.1f}x faster "
                f"than the split bytecode path, which therefore closes "
                f"{((sb - sa) / (sb - sr) * 100):.0f}% of the distance from the unsplit "
                f"baseline to native.\n"
            )
        else:
            md.append(
                f"The Redline backend, which compiles to native code via Cranelift instead "
                f"of to JVM bytecode, reaches **{fmt(sr)} ms** steady on this JDK -- "
                f"{sr/sa:.1f}x slower than the split bytecode path, and {sb/sr:.1f}x faster "
                f"than the unsplit baseline. Do not read that as Cranelift being slow; read "
                f"the caveats below first, because on this JDK the native path pays FFI "
                f"overhead the bytecode path does not.\n"
            )
        panama = "panama" in (a.redline_runner or "").lower()
        from_checkout = (a.redline_endive or "").endswith("SNAPSHOT")
        origin = (
            f"from endive `{a.redline_rev}` -- that is `main`, the same revision as the "
            f"`before` row, since this branch does not touch the Redline path"
            if from_checkout
            else f"against released endive `{a.redline_endive}`, since its Panama runner "
            f"targets Java 25 and cannot be built by an older JDK"
        )
        if panama:
            md.append(
                f"Redline was built {origin}. The runner it selected was "
                f"**{a.redline_runner}**, the intended one on this JDK. Treat it as a "
                f"reference point for how much of the gap to native the bytecode path "
                f"closes, not as a rival measurement.\n"
            )
        else:
            md.append(
                f"Two things qualify it. Redline was built {origin}. And the runner "
                f"selected was **{a.redline_runner}**: below Java 25 that is jffi, whose "
                f"per-call overhead is markedly higher than Panama's, and this benchmark "
                f"crosses the boundary often because it builds a fresh engine per "
                f"iteration. Treat it as a floor for Redline, not its best showing; "
                f"re-run on Java 25+ for the Panama path.\n"
            )

    md.append("## Per-window timings\n")
    extra = " redline (ms) |" if redline else ""
    md.append("| window | before (ms) | after (ms) |" + extra)
    md.append("|---|---|---|" + ("---|" if redline else ""))
    rows = max(len(before), len(after), len(redline) if redline else 0)
    for i in range(rows):
        b = fmt(before[i]) if i < len(before) else ""
        c = fmt(after[i]) if i < len(after) else ""
        d = (" " + (fmt(redline[i]) if i < len(redline) else "") + " |") if redline else ""
        md.append(f"| {i} | {b} | {c} |" + d)
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
    svg(before, after, redline, a.out_svg)

    sums = {"before": cb, "after": ca}
    if redline:
        sums["redline"] = cr
    if len(set(sums.values())) != 1:
        raise SystemExit(f"checksums differ between runs, not comparable: {sums}")


if __name__ == "__main__":
    main()
