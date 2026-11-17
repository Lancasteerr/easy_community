"""点赞历史对比的只读基线校验、原始样本复核和报告生成。"""
import argparse
import csv
import hashlib
import json
import math
import statistics
from pathlib import Path
import xml.etree.ElementTree as ET

BASELINES = [("20261116-113300-1b831540", "round-1"), ("20261116-113300-1b831540", "round-2"),
             ("20261116-120704-560d41cc", "round-1")]


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write(path, data):
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[math.ceil(len(ordered) * fraction) - 1] / 1e6 if ordered else 0


def samples(stage, path, rich=False):
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    assert len(rows) == stage["completed"] and rows, f"样本数量错误: {path}"
    values = [int(row["latencyNanos"]) for row in rows]
    for p in (50, 95, 99):
        assert abs(percentile(values, p / 100) - stage[f"p{p}Ms"]) < 1e-6, f"分位数错误: {path} P{p}"
    assert sum(stage["statusCounts"].values()) == len(rows)
    if rich:
        assert stage["planned"] == len(rows) + stage["skippedBusyClient"]
        assert sum(int(row["status"]) == 202 for row in rows) == stage["success"]
        assert all(int(row["endedOffsetNanos"]) - int(row["startedOffsetNanos"]) == int(row["latencyNanos"]) for row in rows)
        for status, count in stage["statusCounts"].items():
            assert sum(int(row["status"]) == int(status) for row in rows) == count
        assert abs(len(rows) / stage["elapsedSeconds"] - stage["actualQps"]) < 1e-6
        assert abs(stage["success"] / stage["elapsedSeconds"] - stage["successQps"]) < 1e-6
    return rows


def baseline(root, output):
    result = []
    for run, name in BASELINES:
        directory = root / run / name
        report = read(directory / "like-capacity.json")
        junit = directory / "TEST-com.febrie.backend.article.like.LikeCapacityTests.xml"
        xml = ET.parse(junit).getroot()
        assert int(xml.attrib["tests"]) == 1 and all(int(xml.attrib.get(k, 0)) == 0 for k in ("failures", "errors", "skipped"))
        assert report["sqlAuditConsistent"] and report["sampleSeconds"] == 60
        fingerprints = {"like-capacity.json": digest(directory / "like-capacity.json"), junit.name: digest(junit)}
        for stage in report["measurements"]:
            if "targetQps" not in stage:
                continue
            path = directory / f"like-{stage['scenario']}-{stage['targetQps']}.csv"
            samples(stage, path)
            fingerprints[path.name] = digest(path)
        source = root / run / "sources/LikeCapacityTests.java"
        fingerprints["archivedLikeCapacitySource"] = digest(source)
        result.append({"run": run, "round": name, "path": str(directory.resolve()),
                       "revision": read(root / run / "metadata.json")["revision"], "sha256": fingerprints,
                       "measurements": report["measurements"]})
    write(output, {"validated": True, "rounds": result, "qualification": "两个完整轮次来自整体中断的运行，第三轮来自独立补测；不冒充一次完整三轮实验"})


def verify(run):
    checks = []
    metadata = read(run / "metadata.json")
    assert metadata["status"] == "measured"
    for directory in sorted(run.glob("round-*")):
        report = read(directory / "like-capacity.json")
        assert report["schemaVersion"] == 2 and report["status"] == "measured"
        assert report["sqlAuditConsistent"] and report["targetAuditConsistent"]
        for key in ("consumersBefore", "consumersAfter"):
            assert sum(c["active"] for c in report[key]) == 4 and len(report[key]) == 8
        stages = [s for s in report["measurements"] if "targetQps" in s]
        assert len(stages) == 7
        for stage in stages:
            name = f"like-{stage['scenario']}-{stage['targetQps']}"
            rows = samples(stage, directory / f"{name}.csv", True)
            assert len({r["request"] for r in rows}) == len(rows)
            for row in rows:
                number = int(row["request"])
                assert int(row["userIndex"]) == number % 16
                expected_article = 0 if stage["scenario"].startswith("hot") else number // 16 % 32
                assert int(row["articleIndex"]) == expected_article
                assert int(row["plannedOffsetNanos"]) == number * (1_000_000_000 // stage["targetQps"])
            with (directory / f"{name}-coverage.csv").open(encoding="utf-8") as stream:
                covered = list(csv.DictReader(stream))
            assert len(covered) == stage["coverageSamples"] == stage["success"]
            assert sorted(int(r["version"]) for r in covered) == sorted(int(r["version"]) for r in rows if int(r["status"]) == 202)
            assert all(int(r["observedVersion"]) >= int(r["version"]) for r in covered)
            for p in (50, 95, 99):
                assert abs(percentile([int(r["observedLatencyNanos"]) for r in covered], p / 100) - stage[f"coverageP{p}Ms"]) < 1e-6
            assert stage["drained"] and stage["targetAuditConsistent"]
            final = stage["brokerFinal"]
            assert final["ready"] == final["unacked"] == final["dead"] == 0
            metrics = stage["metrics"]["total"]
            assert metrics["relations"] + metrics["coalesced"] == stage["success"]
            assert metrics["applied"] + metrics["duplicate"] + metrics["stale"] == metrics["relations"]
            assert metrics["sqlAttempts"] >= metrics["groups"]
            # 正常无重试时，关系锁/更新及计数锁的调用必须与裁决数量对应。
            prefix = "sql.com.febrie.backend.article.like.infrastructure.persistence.mapper.LikeMapper."
            if metrics["retry"] == 0:
                assert metrics.get(prefix + "initializeState", 0) == metrics["relations"]
                assert metrics.get(prefix + "lock", 0) == metrics["relations"]
                assert metrics.get(prefix + "update", 0) == metrics["applied"]
                assert metrics.get(prefix + "lockCount", 0) == metrics["groups"]
            assert metrics.get(prefix + "addDelta", 0) <= metrics["groups"]
            if stage["scenario"] == "backlog":
                assert stage["planned"] == 300
            checks.append({"round": directory.name, "stage": name, "samples": len(rows), "coverage": len(covered),
                           "pendingSamplingErrors": stage["pendingSamplingErrors"], "brokerSamplingErrors": stage["brokerSamplingErrors"]})
    assert len(list(run.glob("round-*"))) == metadata["rounds"]
    write(run / "like-verification.json", {"passed": True, "checks": checks, "httpSamples": sum(c["samples"] for c in checks)})


def stats(values):
    return {"median": statistics.median(values), "min": min(values), "max": max(values), "values": values}


def compare(run):
    metadata = read(run / "metadata.json")
    history = read(run / "baseline-manifest.json")
    # 再次检查输入哈希，确保压测和汇总期间未改动历史产物。
    for item in history["rounds"]:
        directory = Path(item["path"])
        for name, expected in item["sha256"].items():
            path = directory / name if name != "archivedLikeCapacitySource" else directory.parent / "sources/LikeCapacityTests.java"
            assert digest(path) == expected, f"历史输入发生变化: {path}"
    reports = [read(d / "like-capacity.json") for d in sorted(run.glob("round-*"))]
    results = []
    for scenario in ("uniform", "hot"):
        for rate in (60, 120):
            old = [next(s for s in r["measurements"] if s["scenario"] == scenario and s.get("targetQps") == rate) for r in history["rounds"]]
            new = [next(s for s in r["measurements"] if s["scenario"] == scenario and s.get("targetQps") == rate) for r in reports]
            old_rate = stats([s["actualQps"] * s["success"] / s["completed"] for s in old])
            new_rate = stats([s["successQps"] for s in new])
            matched = abs(new_rate["median"] / old_rate["median"] - 1) <= .05
            clean = all(s["success"] == s["completed"] and not s["pendingSamplingErrors"] and not s["brokerSamplingErrors"] for s in new)
            entry = {"scenario": scenario, "targetQps": rate, "oldSuccessQps": old_rate, "newSuccessQps": new_rate,
                     "loadMatchedWithin5Percent": matched, "cleanNewSamples": clean, "metrics": {}}
            for key in ("p50Ms", "p95Ms", "p99Ms", "acceptedPending", "sqlRowLockWaits", "sqlRowLockWaitMs"):
                a, b = stats([s[key] for s in old]), stats([s[key] for s in new])
                entry["metrics"][key] = {"old": a, "new": b, "changePercent": (b["median"] / a["median"] - 1) * 100 if a["median"] else None}
                if key.startswith("p") and not (matched and clean):
                    entry["metrics"][key]["changePercent"] = None
            results.append(entry)
    old_recovery = [next(s["milliseconds"] for s in r["measurements"] if s["scenario"] == "backlog_recovery") for r in history["rounds"]]
    new_recovery = [next(s for s in r["measurements"] if s["scenario"] == "backlog_recovery") for r in reports]
    result = {"purpose": metadata["purpose"], "comparisonType": "cross_time_history_only", "baselineRounds": len(history["rounds"]), "newRounds": len(reports),
              "comparisons": results, "backlogCoverageRecovery": {"oldMs": stats(old_recovery), "newMs": stats([s["milliseconds"] for s in new_recovery]),
              "comparable300Accepted": all(s["backlogComparable"] for s in new_recovery)},
              "qualification": "不是同步配对实验；正变化百分比表示新版数值更高；没有历史数据的指标不计算提升"}
    write(run / "historical-comparison.json", result)
    lines = ["# 点赞新版与历史结果对比", "", "运行用途：" + metadata["purpose"] + "（smoke仅校验流程，不用于性能结论）。", "", "跨时间历史对比，非同步配对实验。uniform实际为16请求轮转热点。数值为各轮中位数，范围见JSON。", "",
             "| 场景 | QPS | 旧成功QPS | 新成功QPS | 旧P95/P99 ms | 新P95/P99 ms | 旧/新阶段末待覆盖 | 负载匹配 |",
             "|---|---:|---:|---:|---:|---:|---:|---|"]
    for e in results:
        m = e["metrics"]
        lines.append(f"| {e['scenario']} | {e['targetQps']} | {e['oldSuccessQps']['median']:.2f} | {e['newSuccessQps']['median']:.2f} | {m['p95Ms']['old']['median']:.2f}/{m['p99Ms']['old']['median']:.2f} | {m['p95Ms']['new']['median']:.2f}/{m['p99Ms']['new']['median']:.2f} | {m['acceptedPending']['old']['median']}/{m['acceptedPending']['new']['median']} | {e['loadMatchedWithin5Percent']} |")
    lines += ["", f"300请求的SQL覆盖恢复：旧中位数{statistics.median(old_recovery):.0f}ms，新中位数{statistics.median(s['milliseconds'] for s in new_recovery):.0f}ms。物理ACK排空另列，不与历史混用。", "", "240 QPS仅为新版探索，不证明持续容量上限。HTTP包含Session/CSRF、版本签发与Broker确认，不能代表SQL提交延迟。"]
    (run / "historical-comparison.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    plot(run, results)


def plot(run, results):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np
    fig, axes = plt.subplots(2, 2, figsize=(12, 8))
    labels = [f"{e['scenario']}\n{e['targetQps']} QPS" for e in results]
    for ax, metric, title in zip(axes.flat, ("p95Ms", "p99Ms", "acceptedPending", "sqlRowLockWaitMs"),
                                       ("HTTP P95 (ms)", "HTTP P99 (ms)", "SQL version-uncovered at window end", "MySQL row-lock wait (ms)")):
        x = np.arange(len(results))
        for side, offset, color, label in (("old", -.18, "#8b949e", "Historical"), ("new", .18, "#238636", "New")):
            data = [e["metrics"][metric][side] for e in results]
            med = [d["median"] for d in data]
            ax.bar(x + offset, med, width=.36, color=color, label=label,
                   yerr=[[d["median"] - d["min"] for d in data], [d["max"] - d["median"] for d in data]], capsize=3)
        ax.set_xticks(x, labels); ax.set_title(title); ax.grid(axis="y", alpha=.2); ax.legend()
    fig.suptitle("Cross-time historical comparison: median and min/max, not paired A/B")
    fig.tight_layout(); fig.savefig(run / "historical-comparison.png", dpi=160); fig.savefig(run / "historical-comparison.svg"); plt.close(fig)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("baseline", "verify", "compare"))
    parser.add_argument("--run", type=Path)
    parser.add_argument("--root", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.action == "baseline": baseline(args.root, args.output)
    elif args.action == "verify": verify(args.run)
    else: compare(args.run)
    print("LIKE_HISTORY", args.action, "OK")
