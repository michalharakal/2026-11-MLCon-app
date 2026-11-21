#!/usr/bin/env python3
"""descriptor.json for a locally built pack: the blueprint's template plus one target.

    descriptor.py <blueprint.json> <out descriptor.json> <cartridge id> <target id> <language>

For targets the blueprint declares (cpu-arm64, vulkan-armv7, ...) the target and requirements are the blueprint's
own. cpu-x86_64 and vulkan-x86_64 are not blueprint targets yet and are described here. No measurements are
written: the blueprint's materializer refuses to invent them, and so does this.
"""
import json
import sys

bp_path, out, cid, target, lang = sys.argv[1:]
bp = json.load(open(bp_path))
d = {"spec_version": bp["spec_version"], "id": cid, "version": bp["version"]}
d.update(bp["descriptor_template"])
d["model"] = dict(d["model"], variant=d["model"]["variant"] + "-" + lang)
d["attributes"] = dict(d["attributes"], languages=[lang])
d["license"] = bp["license"]
declared = {t["id"]: t for t in bp["targets"]}
if target in declared:
    d["target"] = declared[target]["target"]
    d["requirements"] = declared[target]["requirements"]
else:
    gpu = target.startswith("vulkan")
    d["target"] = {"hardware": "IREE-linux-x86_64", "abi": "linux-x86_64"}
    if gpu:
        d["target"]["accelerator"] = "Vulkan GPU (generic)"
    d["requirements"] = {"needs_accelerator": gpu, "memory_mb": 1024, "os": {"name": "linux"}}
json.dump(d, open(out, "w"), indent=2)
