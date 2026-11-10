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

/** Accuracy of a scorer on a labelled set: argmax label equals the intended one. */
fun accuracy(cases: List<Phrase>, scores: List<FloatArray>): Int =
    cases.zip(scores).count { (p, l) -> l.argmax() == p.label }

/**
 * `prepare <dir>` writes what the teacher scores (TeacherInputs); `train <dir> [teacher.jsonl] [alpha] [temperature]` trains the
 * student twice (hard labels alone, then distilled from the teacher file in `<dir>`), reports both against the teacher on
 * the held-out sets, and exports the distilled one to `<dir>/router.mlir`.
 */
fun main(args: Array<String>) {
    val cmd = args.getOrNull(0) ?: "train"
    val dir = java.io.File(args.getOrNull(1) ?: "build/router").absoluteFile
    if (cmd == "prepare") { TeacherInputs.write(dir); println("wrote ${dir.path}/prompt.json, phrases.jsonl"); return }
    val teacherFile = java.io.File(dir, args.getOrNull(2) ?: "teacher.jsonl")
    val alpha = args.getOrNull(3)?.toDouble() ?: 0.7
    val temperature = args.getOrNull(4)?.toDouble() ?: 2.0
    val epochs = 200

    val phrases = Phrases.generate()
    val teacher = TeacherInputs.readTeacher(teacherFile)
    val sets = listOf("golden" to Phrases.heldOut, "paraphrase" to Paraphrases.cases)
    val lines = mutableListOf<String>()
    fun report(name: String, score: (List<Phrase>) -> List<FloatArray>?) {
        val parts = sets.map { (n, c) -> score(c)?.let { "$n ${accuracy(c, it)}/${c.size}" } ?: "$n -" }
        lines += "%-22s %s".format(name, parts.joinToString("  "))
    }
    println("phrases: ${phrases.size}, teacher ${teacherFile.name}: ${teacher.size} scores")
    if (teacher.isNotEmpty()) {
        report("teacher") { c -> c.map { teacher[it.text] ?: return@report null } }
        val agree = phrases.count { p -> teacher[p.text]?.argmax() == p.label }
        lines += "teacher agrees with the generator on ${agree}/${phrases.size} training phrases"
    }
    val hard = train(phrases, null, alpha = 0.0, temperature = 1.0, epochs = epochs)
    report("student, hard labels") { c -> predict(hard.model, c.map { it.text }) }
    val final = if (teacher.isEmpty()) hard else {
        val t0 = System.nanoTime()
        val distilled = train(phrases.filter { it.text in teacher }, teacher, alpha, temperature, epochs)
        println("distilled in ${(System.nanoTime() - t0) / 1_000_000} ms (alpha $alpha, T $temperature)")
        report("student, distilled") { c -> predict(distilled.model, c.map { it.text }) }
        distilled
    }
    lines.forEach(::println)
    val mlir = Export.stableHlo(final.model)
    java.io.File(dir, "router.mlir").writeText(mlir)
    java.io.File(dir, "report.txt").writeText(lines.joinToString("\n", postfix = "\n"))
    val params = Router.HIDDEN * (RouterFeatures.DIM + 1) + Router.classes * (Router.HIDDEN + 1)
    println("exported ${dir.path}/router.mlir: $params parameters, ${params * 4 / 1024} KiB fp32")
}
