package sk.ainet.examples.smarthome.distill

import sk.ainet.context.DirectCpuExecutionContext
import sk.ainet.context.ExecutionContext
import sk.ainet.context.Phase
import sk.ainet.lang.graph.DefaultGradientTape
import sk.ainet.lang.graph.DefaultGraphExecutionContext
import sk.ainet.lang.nn.Module
import sk.ainet.lang.nn.dsl.sequential
import sk.ainet.lang.nn.dsl.training
import sk.ainet.lang.nn.optim.adam
import sk.ainet.lang.tensor.Shape
import sk.ainet.lang.tensor.Tensor
import sk.ainet.lang.tensor.relu
import sk.ainet.lang.types.FP32
import sk.ainet.examples.smarthome.router.RouterFeatures
import sk.ainet.examples.smarthome.router.RouterLabels
import kotlin.random.Random

/** The student: a two-layer MLP over hashed n-grams, [RouterFeatures.DIM] → [HIDDEN] → one logit per router label. */
object Router {
    const val HIDDEN = 64
    val classes: Int get() = RouterLabels.all.size

    fun model(ctx: ExecutionContext, seed: Int = 42): Module<FP32, Float> {
        val rng = Random(seed)
        return sequential<FP32, Float>(ctx) {
            input(RouterFeatures.DIM)
            dense(HIDDEN) { weights { randn(std = 0.08f, random = rng) } }
            activation { it.relu() }
            dense(classes) { weights { randn(std = 0.1f, random = rng) } }
        }
    }
}

fun features(ctx: ExecutionContext, phrases: List<String>): Tensor<FP32, Float> {
    val flat = FloatArray(phrases.size * RouterFeatures.DIM)
    phrases.forEachIndexed { i, p -> RouterFeatures.vector(p).copyInto(flat, i * RouterFeatures.DIM) }
    return ctx.fromData(ctx.tensorDataFactory.fromFloatArray(Shape(phrases.size, RouterFeatures.DIM), FP32::class, flat), FP32::class)
}

fun matrix(ctx: ExecutionContext, rows: List<FloatArray>): Tensor<FP32, Float> {
    val cols = rows.first().size
    val flat = FloatArray(rows.size * cols).also { f -> rows.forEachIndexed { i, r -> r.copyInto(f, i * cols) } }
    return ctx.fromData(ctx.tensorDataFactory.fromFloatArray(Shape(rows.size, cols), FP32::class, flat), FP32::class)
}

data class Trained(val model: Module<FP32, Float>, val losses: List<Float>)

/** Full-batch training: a few hundred phrases fit in one matmul, so there is no data loader to get wrong. */
fun train(phrases: List<Phrase>, teacher: Map<String, FloatArray>?, alpha: Double, temperature: Double, epochs: Int, lr: Double = 0.01): Trained {
    val base = DirectCpuExecutionContext()
    val ctx = DefaultGraphExecutionContext(baseOps = base.ops, phase = Phase.TRAIN, createTapeFactory = { _ -> DefaultGradientTape() })
    val model = Router.model(ctx)
    val x = features(ctx, phrases.map { it.text })
    val y = matrix(ctx, phrases.map { DistillationLoss.target(teacher?.get(it.text), it.label, Router.classes, alpha, temperature) })
    val runner = training<FP32, Float> {
        model { model }
        loss { DistillationLoss(temperature) }
        optimizer { adam(lr = lr).apply { model.trainableParameters().forEach { addParameter(it) } } }
    }
    val losses = (0 until epochs).map { runner.step(ctx, x, y).data.get() }
    return Trained(model, losses)
}

/** Logits for each phrase, on a plain (non-recording) context. */
fun predict(model: Module<FP32, Float>, texts: List<String>): List<FloatArray> {
    val ctx = DirectCpuExecutionContext()
    val out = model.forward(features(ctx, texts), ctx)
    return texts.indices.map { i -> FloatArray(Router.classes) { c -> out.data.get(i, c) } }
}

fun FloatArray.argmax(): Int = indices.maxBy { this[it] }

fun main(args: Array<String>) {
    val phrases = Phrases.generate()
    println("phrases: ${phrases.size} (${phrases.groupingBy { it.intended ?: "none" }.eachCount()})")
    val t0 = System.nanoTime()
    val trained = train(phrases, teacher = null, alpha = 0.0, temperature = 1.0, epochs = 200)
    println("train: ${(System.nanoTime() - t0) / 1_000_000} ms, loss ${trained.losses.first()} -> ${trained.losses.last()}")
    val held = Phrases.heldOut
    val preds = predict(trained.model, held.map { it.text })
    var ok = 0
    held.zip(preds).forEach { (p, l) ->
        val got = RouterLabels.all[l.argmax()]
        if (got == (p.intended ?: RouterLabels.NONE)) ok++
        println("  ${if (got == (p.intended ?: RouterLabels.NONE)) "✓" else "✗"} ${p.text} -> $got")
    }
    println("held-out golden: $ok/${held.size}")
    val para = predict(trained.model, Paraphrases.cases.map { it.text })
    val pok = Paraphrases.cases.zip(para).count { (p, l) -> RouterLabels.all[l.argmax()] == (p.intended ?: RouterLabels.NONE) }
    println("held-out paraphrases: $pok/${Paraphrases.cases.size}")
    val out = java.io.File(args.firstOrNull() ?: "build/router").apply { mkdirs() }
    val mlir = Export.stableHlo(trained.model)
    java.io.File(out, "router.mlir").writeText(mlir)
    val probe = Paraphrases.cases.first().text
    java.io.File(out, "probe.txt").writeText(probe + "\n" + RouterFeatures.vector(probe).joinToString(",") + "\n" + predict(trained.model, listOf(probe))[0].joinToString(",") + "\n")
    println("exported ${out.path}/router.mlir (${mlir.length} chars)")
}
