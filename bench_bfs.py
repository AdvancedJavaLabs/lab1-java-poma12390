import argparse
import csv
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import List, Dict, Any, Tuple

RESULTS_FILE_NAME = "results.txt"
RESULTS_PATTERN = re.compile(
    r"Times for\s+(\d+)\s+vertices\s+and\s+(\d+)\s+connections:\s*"
    r"Serial:\s*(\d+)\s*"
    r"Parallel:\s*(\d+)\s*",
    re.IGNORECASE | re.MULTILINE
)

def run_gradle_test(project: Path, test_filter: str, cpu: int, clean: bool, extra_args: List[str]) -> None:
    is_windows = os.name == "nt"
    gradlew = "gradlew.bat" if is_windows else "./gradlew"

    cmd = [gradlew]
    if clean:
        cmd.append("clean")
    cmd += ["test", "--no-daemon", "--tests", test_filter]
    cmd += extra_args

    env = os.environ.copy()
    jto = (
        f"-Xms4g -Xmx4g "
        f"-XX:+UseG1GC -XX:ParallelGCThreads=1 -XX:ConcGCThreads=1 -XX:+AlwaysPreTouch "
        f"-Dbfs.threads={cpu} -Dfile.encoding=UTF-8"
    )
    env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " " + jto).strip()
    env["GRADLE_OPTS"] = (env.get("GRADLE_OPTS", "") + f" -Dbfs.threads={cpu}").strip()

    print(f"[run] CPU={cpu} :: {' '.join(cmd)}")
    subprocess.run(cmd, cwd=str(project), env=env, check=True)



def parse_results(text: str) -> List[Tuple[int, int, int, int]]:
    out = []
    for m in RESULTS_PATTERN.finditer(text):
        v = int(m.group(1))
        e = int(m.group(2))
        s = int(m.group(3))
        p = int(m.group(4))
        out.append((v, e, s, p))
    return out


def main():
    ap = argparse.ArgumentParser(description="Benchmark Parallel vs Serial BFS via Gradle test runs")
    ap.add_argument("--project", required=True, help="Путь к проекту с tmp\\results.txt")
    ap.add_argument("--test", default="org.itmo.BFSTest", help="Фильтр теста (--tests)")
    ap.add_argument("--cpu", default="1,2,4,6,8", help="Список CPU для -XX:ActiveProcessorCount (через запятую)")
    ap.add_argument("--no-clean", action="store_true", help="Не выполнять gradle clean перед каждым тестом")
    ap.add_argument("--plot", action="store_true", help="Строить PNG-графики после парсинга")
    ap.add_argument("--extra", default="", help="Доп. аргументы для Gradle, например: --info")
    args = ap.parse_args()

    project = Path(args.project).resolve()
    tmp_dir = project / "tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    raw_results = tmp_dir / RESULTS_FILE_NAME
    cpu_list = [int(x) for x in args.cpu.split(",") if x.strip()]
    clean_each = not args.no_clean
    extra_args = args.extra.split() if args.extra.strip() else []

    combined_rows: List[Dict[str, Any]] = []

    for cpu in cpu_list:
        if raw_results.exists():
            raw_results.unlink()

        start = time.time()
        run_gradle_test(project, args.test, cpu, clean_each, extra_args)
        dur = time.time() - start
        print(f"[ok] CPU={cpu} :: build+test finished in {dur:.1f}s")

        if not raw_results.exists():
            print(f"[warn] {raw_results} not found after test run.", file=sys.stderr)
            continue

        snapshot = tmp_dir / f"results_{cpu}cpu.txt"
        shutil.copyfile(raw_results, snapshot)
        print(f"[save] Snapshot -> {snapshot}")

        text = raw_results.read_text(encoding="utf-8", errors="ignore")
        parsed = parse_results(text)
        if not parsed:
            print(f"[warn] No blocks parsed for CPU={cpu}", file=sys.stderr)

        for (v, e, s, p) in parsed:
            combined_rows.append({
                "vertices": v,
                "connections": e,
                "serial_ms": s,
                "parallel_ms": p,
                "cpu": cpu,
            })

    csv_path = tmp_dir / "perf_data.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["vertices", "connections", "cpu", "serial_ms", "parallel_ms"])
        w.writeheader()
        for row in sorted(combined_rows, key=lambda r: (r["vertices"], r["connections"], r["cpu"])):
            w.writerow(row)

    print(f"[csv] Written {csv_path} with {len(combined_rows)} rows")

    if args.plot:
        try:
            import matplotlib.pyplot as plt
        except Exception as e:
            print(f"[plot] matplotlib не установлен: {e}. Установите: pip install matplotlib", file=sys.stderr)
            return

        if combined_rows:
            all_sizes = {(r["vertices"], r["connections"]) for r in combined_rows}
            pick_vertices = max(v for (v, _) in all_sizes)
            pick_conns = max(e for (v, e) in all_sizes if v == pick_vertices)

            rows1 = [r for r in combined_rows if r["vertices"] == pick_vertices and r["connections"] == pick_conns]
            rows1.sort(key=lambda r: r["cpu"])

            xs = [r["cpu"] for r in rows1]
            ys = [r["parallel_ms"] for r in rows1]

            plt.figure()
            plt.title(f"Parallel BFS time vs CPU\n(V={pick_vertices}, E={pick_conns})")
            plt.xlabel("ActiveProcessorCount (CPU)")
            plt.ylabel("Time, ms (parallel)")
            plt.plot(xs, ys, marker="o")
            out1 = tmp_dir / "plot_parallel_vs_cpu.png"
            plt.savefig(out1, dpi=150, bbox_inches="tight")
            print(f"[plot] {out1}")

        if combined_rows:
            max_cpu = max(r["cpu"] for r in combined_rows)
            rows2 = [r for r in combined_rows if r["cpu"] == max_cpu]
            agg: Dict[int, Dict[str, float]] = {}
            for r in rows2:
                v = r["vertices"]
                if v not in agg:
                    agg[v] = {"serial": r["serial_ms"], "parallel": r["parallel_ms"]}
                else:
                    agg[v]["serial"] = min(agg[v]["serial"], r["serial_ms"])
                    agg[v]["parallel"] = min(agg[v]["parallel"], r["parallel_ms"])

            vs_sorted = sorted(agg.keys())
            serials = [agg[v]["serial"] for v in vs_sorted]
            pars = [agg[v]["parallel"] for v in vs_sorted]

            plt.figure()
            plt.title(f"Serial vs Parallel vs Size (CPU={max_cpu})")
            plt.xlabel("Vertices")
            plt.ylabel("Time, ms")
            plt.plot(vs_sorted, serials, marker="o", label="Serial")
            plt.plot(vs_sorted, pars, marker="s", label="Parallel")
            plt.legend()
            out2 = tmp_dir / "plot_serial_vs_parallel_vs_size.png"
            plt.savefig(out2, dpi=150, bbox_inches="tight")
            print(f"[plot] {out2}")


if __name__ == "__main__":
    main()
