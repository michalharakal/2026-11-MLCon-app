"""A general instruct LLM as the router's teacher: multiple choice over the catalog, read from next-token logits.

The options are the catalog functions with their descriptions (the same text the on-device model reads, from the
generated home-tools.v1.json) plus "none of these". The teacher's distribution is the softmax over the option
letters at the first answer position, so it is a soft label, not a sampled one.

    python -I score_instruct.py <dir> <catalog.json> <model dir> [gguf file] [out name]

Writes <dir>/<out name> (default teacher-instruct.jsonl) in the format the student reads: {"text", "logprobs"}.
"""
import json
import sys
import time

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def main(out_dir: str, catalog_path: str, model_dir: str, gguf: str | None, out_name: str) -> None:
    prompt = json.load(open(f"{out_dir}/prompt.json"))
    phrases = [json.loads(line) for line in open(f"{out_dir}/phrases.jsonl") if line.strip()]
    catalog = {f["name"]: f["description"] for f in json.load(open(catalog_path))["functions"]}
    labels = prompt["labels"]
    letters = [chr(ord("A") + i) for i in range(len(labels))]
    options = "\n".join(
        f"{l}. {catalog[name]}" if name in catalog else f"{l}. None of these: the request is not about the lights, heating, "
        f"blinds, door locks, scenes or status of this home, and not about the weather."
        for l, name in zip(letters, labels))
    system = ("You route requests for a smart-home voice assistant. The home has a living room, kitchen, bedroom, "
              "bathroom and hallway, a front and a back door, and scenes for morning, movie, night and away.\n"
              f"Options:\n{options}\n\nAnswer with the letter of the one option that handles the request, nothing else.")

    device = "cuda" if torch.cuda.is_available() else "cpu"
    kw = {"gguf_file": gguf} if gguf else {}
    tok = AutoTokenizer.from_pretrained(model_dir, **kw)
    model = AutoModelForCausalLM.from_pretrained(model_dir, dtype=torch.float16 if device == "cuda" else torch.float32, **kw)
    model = model.to(device).eval()

    # each letter may be tokenized with or without a leading space; take the better of the two
    variants = [[tok.encode(v, add_special_tokens=False) for v in (l, " " + l)] for l in letters]
    ids = [[v[0] for v in vs if len(v) == 1] for vs in variants]
    assert all(ids), f"letters are not single tokens: {variants}"

    @torch.no_grad()
    def score(text: str) -> list:
        msgs = [{"role": "system", "content": system}, {"role": "user", "content": f"Request: {text}"}]
        x = tok.apply_chat_template(msgs, add_generation_prompt=True, return_tensors="pt").to(device)
        lp = torch.log_softmax(model(x).logits[0, -1].float(), dim=-1)
        return [max(lp[i].item() for i in alts) for alts in ids]

    print(f"teacher {gguf or model_dir} on {device}; options {letters}", flush=True)
    t0 = time.time()
    with open(f"{out_dir}/{out_name}", "w") as w:
        for i, p in enumerate(phrases):
            w.write(json.dumps({"text": p["text"], "logprobs": score(p["text"])}) + "\n")
            if (i + 1) % 200 == 0:
                print(f"  {i + 1}/{len(phrases)} in {time.time() - t0:.0f} s", flush=True)
    print(f"{out_name}: {len(phrases)} phrases in {time.time() - t0:.0f} s")


if __name__ == "__main__":
    a = sys.argv[1:]
    main(a[0], a[1], a[2], a[3] if len(a) > 3 and a[3] else None, a[4] if len(a) > 4 else "teacher-instruct.jsonl")
