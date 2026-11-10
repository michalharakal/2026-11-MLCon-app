"""Teacher scores for the router: FunctionGemma 270M's probability of calling each catalog function.

Reads prompt.json and phrases.jsonl (written by `./gradlew :tools:distill:run --args="prepare <dir>"`, i.e. the
exact on-device prompt rendered by the Kotlin FunctionGemmaPrompt) and writes teacher.jsonl:

    log p(tool) = log p(<start_function_call> | prompt) + sum log p(tokens of "call:NAME{" | ...)
    log p(none) = log(1 - p(<start_function_call> | prompt))

which is how the device decodes: a first token other than <start_function_call> is a prose answer (NoCall), and the
grammar matches "call:NAME{" after it. The catalog prefix is prefilled once and its KV cache reused per phrase.

    python -I score.py <dir> <hf model dir of google/functiongemma-270m-it>

Any teacher can replace this script: the student only reads teacher.jsonl.
"""
import copy
import json
import math
import sys
import time

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def main(out_dir: str, model_dir: str) -> None:
    prompt = json.load(open(f"{out_dir}/prompt.json"))
    phrases = [json.loads(line) for line in open(f"{out_dir}/phrases.jsonl") if line.strip()]
    device = "cuda" if torch.cuda.is_available() else "cpu"
    tok = AutoTokenizer.from_pretrained(model_dir)
    model = AutoModelForCausalLM.from_pretrained(model_dir, torch_dtype=torch.float32, attn_implementation="eager")
    model = model.to(device).eval()

    call_id = tok.convert_tokens_to_ids(prompt["start_function_call"])
    assert call_id == 48, f"<start_function_call> is {call_id}, the device grammar expects 48"
    enc = lambda s: tok.encode(s, add_special_tokens=False)
    prefix = [prompt["bos"]] + enc(prompt["prefix"])
    calls = [enc(c) if c is not None else None for c in prompt["calls"]]

    @torch.no_grad()
    def run(ids, cache):
        """Feeds ids after cache (mutated); returns per-position log-probs [len(ids), vocab] and the cache."""
        out = model(torch.tensor([ids], device=device), past_key_values=cache, use_cache=True)
        return torch.log_softmax(out.logits[0].float(), dim=-1), out.past_key_values

    _, prefix_cache = run(prefix, None)

    def score(text: str) -> list:
        lp, cache = run(enc(text + prompt["suffix"]), copy.deepcopy(prefix_cache))
        lp_call = lp[-1, call_id].item()
        lp_none = math.log1p(-math.exp(lp_call)) if lp_call < 0 else -1e9
        first, after_call = run([call_id], cache)
        out = []
        for ids in calls:
            if ids is None:
                out.append(lp_none)
                continue
            total = lp_call + first[-1, ids[0]].item()
            if len(ids) > 1:
                rest, _ = run(ids[:-1], copy.deepcopy(after_call))
                total += sum(rest[j, ids[j + 1]].item() for j in range(len(ids) - 1))
            out.append(total)
        return out

    # the cached path must equal a single uncached forward over prefix + utterance
    probe = phrases[0]["text"]
    full, _ = run(prefix + enc(probe + prompt["suffix"]), None)
    cached, _ = run(enc(probe + prompt["suffix"]), copy.deepcopy(prefix_cache))
    drift = (full[-1] - cached[-1]).abs().max().item()
    print(f"prefix {len(prefix)} tokens on {device}; cache drift on probe {drift:.2e}", flush=True)
    assert drift < 1e-3, "KV-cache reuse does not reproduce the uncached forward"

    t0 = time.time()
    with open(f"{out_dir}/teacher.jsonl", "w") as w:
        for i, p in enumerate(phrases):
            w.write(json.dumps({"text": p["text"], "logprobs": score(p["text"])}) + "\n")
            if (i + 1) % 100 == 0:
                print(f"  {i + 1}/{len(phrases)} in {time.time() - t0:.0f} s", flush=True)
    print(f"teacher.jsonl: {len(phrases)} phrases in {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
