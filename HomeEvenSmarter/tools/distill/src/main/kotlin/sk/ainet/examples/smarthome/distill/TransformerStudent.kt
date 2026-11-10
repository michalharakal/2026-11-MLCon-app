package sk.ainet.examples.smarthome.distill

import sk.ainet.context.ExecutionContext
import sk.ainet.lang.nn.Linear
import sk.ainet.lang.nn.Module
import sk.ainet.lang.nn.topology.ModuleParameter
import sk.ainet.lang.nn.topology.ModuleParameters
import sk.ainet.lang.tensor.Shape
import sk.ainet.lang.tensor.Tensor
import sk.ainet.lang.types.DType
import sk.ainet.lang.types.FP32
import sk.ainet.lang.types.Int32
import sk.ainet.examples.smarthome.router.RouterFeatures
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Character tokens for the Transformer student: a class token first, then the normalised phrase, padded to [L].
 * Ids travel as FP32 because the model's input is one FP32 tensor; `gather` reads them as integers.
 */
object CharTokens {
    const val L = 48
    const val PAD = 0
    const val CLS = 1
    const val UNK = 2
    const val SPACE = 3
    const val VOCAB = 40 // pad, cls, unk, space, a–z (4..29), 0–9 (30..39)

    fun id(c: Char): Int = when (c) {
        in 'a'..'z' -> 4 + (c - 'a')
        in '0'..'9' -> 30 + (c - '0')
        ' ' -> SPACE
        else -> UNK
    }

    fun encode(text: String): FloatArray {
        val s = RouterFeatures.normalize(text).trim()
        val out = FloatArray(L) { PAD.toFloat() }
        out[0] = CLS.toFloat()
        for (i in 0 until minOf(s.length, L - 1)) out[i + 1] = id(s[i]).toFloat()
        return out
    }

    fun batch(ctx: ExecutionContext, texts: List<String>): Tensor<FP32, Float> {
        val flat = FloatArray(texts.size * L)
        texts.forEachIndexed { i, t -> encode(t).copyInto(flat, i * L) }
        return ctx.fromData(ctx.tensorDataFactory.fromFloatArray(Shape(texts.size, L), FP32::class, flat), FP32::class)
    }
}

/**
 * One pre-norm Transformer block as a plain [Module] over `TensorOps`: character embedding plus learned positions,
 * two-head self-attention through `scaledDotProductAttention`, a gated GELU feed-forward, and a classifier on the
 * class token. Every op has a backward rule in SKaiNET's tape, so the whole block trains in Kotlin.
 */
class TransformerStudent(
    ctx: ExecutionContext,
    val classes: Int,
    val dim: Int = 32,
    val heads: Int = 2,
    val ffn: Int = 64,
    seed: Int = 42,
    override val name: String = "student",
) : Module<FP32, Float>(), ModuleParameters<FP32, Float> {
    private val rng = Random(seed)
    private val headDim = dim / heads

    private fun randn(ctx: ExecutionContext, shape: Shape, std: Float): Tensor<FP32, Float> =
        ctx.fromData(ctx.tensorDataFactory.randn<FP32, Float>(shape, FP32::class, 0f, std, rng), FP32::class)

    private fun ones(ctx: ExecutionContext, n: Int): Tensor<FP32, Float> =
        ctx.fromData(ctx.tensorDataFactory.fromFloatArray(Shape(n), FP32::class, FloatArray(n) { 1f }), FP32::class)

    private fun linear(ctx: ExecutionContext, id: String, inF: Int, outF: Int, bias: Boolean = false) = Linear(
        inF, outF, "$name.$id",
        randn(ctx, Shape(outF, inF), 1f / sqrt(inF.toFloat())),
        if (bias) ctx.fromData(ctx.tensorDataFactory.fromFloatArray(Shape(outF), FP32::class, FloatArray(outF)), FP32::class) else null,
    )

    private val tok = ModuleParameter.WeightParameter("$name.tok", randn(ctx, Shape(CharTokens.VOCAB, dim), 0.1f))
    private val pos = ModuleParameter.WeightParameter("$name.pos", randn(ctx, Shape(CharTokens.L, dim), 0.1f))
    private val norm1 = ModuleParameter.WeightParameter("$name.norm1", ones(ctx, dim))
    private val norm2 = ModuleParameter.WeightParameter("$name.norm2", ones(ctx, dim))
    private val normOut = ModuleParameter.WeightParameter("$name.normOut", ones(ctx, dim))
    private val wq = linear(ctx, "wq", dim, dim)
    private val wk = linear(ctx, "wk", dim, dim)
    private val wv = linear(ctx, "wv", dim, dim)
    private val wo = linear(ctx, "wo", dim, dim)
    private val wGate = linear(ctx, "wGate", dim, ffn)
    private val wUp = linear(ctx, "wUp", dim, ffn)
    private val wDown = linear(ctx, "wDown", ffn, dim)
    private val head = linear(ctx, "head", dim, classes, bias = true)

    /**
     * The ids arrive as FP32 (the model has one FP32 input) and become Int32 here: `gather` reads them either way, but
     * StableHLO's gather needs integer indices, so the cast must be part of the traced model. No gradient flows into ids.
     */
    @Suppress("UNCHECKED_CAST")
    private fun ids(ctx: ExecutionContext, t: Tensor<FP32, Float>): Tensor<DType, *> = ctx.ops.convert(t, Int32) as Tensor<DType, *>

    override val params: List<ModuleParameter<FP32, Float>> = listOf(tok, pos, norm1, norm2, normOut)
    override val modules: List<Module<FP32, Float>> = listOf(wq, wk, wv, wo, wGate, wUp, wDown, head)

    /** `x · rsqrt(mean(x²) + eps) · g` over the last axis, from primitives so every step has a gradient. */
    private fun rmsNorm(ctx: ExecutionContext, x: Tensor<FP32, Float>, g: Tensor<FP32, Float>): Tensor<FP32, Float> {
        val ops = ctx.ops
        val last = x.rank - 1
        val ms = ops.mean(ops.multiply(x, x), last)                  // last axis squeezed away
        val inv = ops.unsqueeze(ops.rdivScalar(1.0, ops.sqrt(ops.addScalar(ms, 1e-6))), last)
        return ops.multiply(ops.multiply(x, inv), g)
    }

    /** `[N·L, dim]` → `[N, heads, L, headDim]`. */
    private fun splitHeads(ctx: ExecutionContext, x: Tensor<FP32, Float>, n: Int): Tensor<FP32, Float> =
        ctx.ops.permute(ctx.ops.reshape(x, Shape(n, CharTokens.L, heads, headDim)), intArrayOf(0, 2, 1, 3))

    override fun onForward(input: Tensor<FP32, Float>, ctx: ExecutionContext): Tensor<FP32, Float> {
        val ops = ctx.ops
        val n = input.shape[0]
        val l = CharTokens.L
        // embeddings: tokens [N, L, dim] + the position table [L, dim], broadcast over the batch
        val x = ops.add(ops.gather(tok.value, ids(ctx, input), 0), pos.value)
        // attention
        val h = ops.reshape(rmsNorm(ctx, x, norm1.value), Shape(n * l, dim))
        val q = splitHeads(ctx, wq.forward(h, ctx), n)
        val k = splitHeads(ctx, wk.forward(h, ctx), n)
        val v = splitHeads(ctx, wv.forward(h, ctx), n)
        val att = ops.scaledDotProductAttention(q, k, v)
        val merged = ops.reshape(ops.permute(att, intArrayOf(0, 2, 1, 3)), Shape(n * l, dim))
        val x1 = ops.add(x, ops.reshape(wo.forward(merged, ctx), Shape(n, l, dim)))
        // gated feed-forward
        val h2 = ops.reshape(rmsNorm(ctx, x1, norm2.value), Shape(n * l, dim))
        val f = wDown.forward(ops.multiply(ops.gelu(wGate.forward(h2, ctx)), wUp.forward(h2, ctx)), ctx)
        val x2 = ops.add(x1, ops.reshape(f, Shape(n, l, dim)))
        // class token → logits
        val cls = ops.reshape(ops.narrow(x2, 1, 0, 1), Shape(n, dim))
        return head.forward(rmsNorm(ctx, cls, normOut.value), ctx)
    }
}
