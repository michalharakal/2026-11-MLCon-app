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

/** A student architecture: how to build it, how to turn phrases into its input, and the input shape of one phrase. */
interface Student {
    val id: String
    fun model(ctx: ExecutionContext, seed: Int = 42): Module<FP32, Float>
    fun input(ctx: ExecutionContext, texts: List<String>): Tensor<FP32, Float>
    val inputShape: Shape

    /** The two-layer MLP over hashed n-grams ([Router]). */
    object Mlp : Student {
        override val id = "mlp"
        override fun model(ctx: ExecutionContext, seed: Int) = Router.model(ctx, seed)
        override fun input(ctx: ExecutionContext, texts: List<String>) = features(ctx, texts)
        override val inputShape = Shape(1, RouterFeatures.DIM)
    }

    /** One Transformer block over character tokens ([TransformerStudent]). */
    object Transformer : Student {
        override val id = "transformer"
        override fun model(ctx: ExecutionContext, seed: Int): Module<FP32, Float> = TransformerStudent(ctx, Router.classes, seed = seed)
        override fun input(ctx: ExecutionContext, texts: List<String>) = CharTokens.batch(ctx, texts)
        override val inputShape = Shape(1, CharTokens.L)
    }

    companion object {
        fun byId(id: String): Student = listOf(Mlp, Transformer).firstOrNull { it.id == id } ?: error("unknown student '$id' (mlp | transformer)")
    }
}

/**
 * Training loop. `batchSize = 0` is full batch: a few hundred phrases fit in one matmul, so there is no data loader to
 * get wrong, and the MLP keeps its original numbers. A positive [batchSize] shuffles the phrases once, cuts them into
 * batches and visits the batches in a fresh order each epoch; the per-epoch loss is the mean over its batches. The trace
 * session is cleared after every step, otherwise it keeps every intermediate tensor of every step alive.
 */
fun train(student: Student, phrases: List<Phrase>, teacher: Map<String, FloatArray>?, alpha: Double, temperature: Double, epochs: Int, lr: Double = 0.01, batchSize: Int = 0, log: (Int, Float) -> Unit = { _, _ -> }): Trained {
    val base = DirectCpuExecutionContext()
    val ctx = DefaultGraphExecutionContext(baseOps = base.ops, phase = Phase.TRAIN, createTapeFactory = { _ -> DefaultGradientTape() })
    val model = student.model(ctx)
    val order = Random(1)
    val chunks = (if (batchSize > 0) phrases.shuffled(order).chunked(batchSize) else listOf(phrases)).map { chunk ->
        student.input(ctx, chunk.map { it.text }) to
            matrix(ctx, chunk.map { DistillationLoss.target(teacher?.get(it.text), it.label, Router.classes, alpha, temperature) })
    }
    val runner = training<FP32, Float> {
        model { model }
        loss { DistillationLoss(temperature) }
        optimizer { adam(lr = lr).apply { model.trainableParameters().forEach { addParameter(it) } } }
    }
    val losses = (0 until epochs).map { e ->
        val visit = if (chunks.size > 1) chunks.shuffled(order) else chunks
        val mean = visit.map { (x, y) -> runner.step(ctx, x, y).data.get().also { ctx.clearSession() } }.average().toFloat()
        mean.also { log(e, it) }
    }
    return Trained(model, losses)
}

/** Logits for each phrase, on a plain (non-recording) context. */
fun predict(student: Student, model: Module<FP32, Float>, texts: List<String>): List<FloatArray> {
    val ctx = DirectCpuExecutionContext()
    val out = model.forward(student.input(ctx, texts), ctx)
    return texts.indices.map { i -> FloatArray(Router.classes) { c -> out.data.get(i, c) } }
}

fun Module<FP32, Float>.parameterCount(): Int = trainableParameters().sumOf { it.value.shape.dimensions.fold(1) { a, b -> a * b } }

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
    val student = Student.byId(args.getOrNull(5) ?: "mlp")
    val epochs = args.getOrNull(6)?.toInt() ?: 200
    val batchSize = args.getOrNull(7)?.toInt() ?: if (student === Student.Mlp) 0 else 64
    // the MLP keeps its original file names; other students get their id in the name
    val suffix = if (student === Student.Mlp) "" else "-${student.id}"
    val logEvery = if (student === Student.Mlp) 0 else 10
    val rt = Runtime.getRuntime()
    val log: (Int, Float) -> Unit = { e, l ->
        if (logEvery > 0 && (e % logEvery == 0 || e == epochs - 1)) println("  epoch $e loss $l  heap ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB")
    }
    // DISTILL_LIMIT=<n> trains on the first n phrases only (memory and timing experiments)
    val limit = System.getenv("DISTILL_LIMIT")?.toIntOrNull()
    println("max heap ${rt.maxMemory() shr 20} MB")

    val phrases = Phrases.generate().let { if (limit != null) it.take(limit) else it }
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
    println("student: ${student.id}, $epochs epochs, batch ${if (batchSize > 0) batchSize else "full"}")
    val t1 = System.nanoTime()
    val hard = train(student, phrases, null, alpha = 0.0, temperature = 1.0, epochs = epochs, batchSize = batchSize, log = log)
    println("hard labels in ${(System.nanoTime() - t1) / 1_000_000} ms, final loss ${hard.losses.last()}")
    report("student, hard labels") { c -> predict(student, hard.model, c.map { it.text }) }
    val final = if (teacher.isEmpty()) hard else {
        val t0 = System.nanoTime()
        val distilled = train(student, phrases.filter { it.text in teacher }, teacher, alpha, temperature, epochs, batchSize = batchSize, log = log)
        println("distilled in ${(System.nanoTime() - t0) / 1_000_000} ms (alpha $alpha, T $temperature), final loss ${distilled.losses.last()}")
        report("student, distilled") { c -> predict(student, distilled.model, c.map { it.text }) }
        distilled
    }
    lines.forEach(::println)
    val mlir = Export.stableHlo(student, final.model)
    java.io.File(dir, "router$suffix.mlir").writeText(mlir)
    java.io.File(dir, "report$suffix.txt").writeText(lines.joinToString("\n", postfix = "\n"))
    val params = final.model.parameterCount()
    println("exported ${dir.path}/router$suffix.mlir: $params parameters, ${params * 4 / 1024} KiB fp32")
    writeProbe(student, final.model, Phrases.heldOut.first().text, java.io.File(dir, "probe$suffix"))
}

/**
 * One phrase for checking the compiled module against SKaiNET: `<name>.txt` holds the phrase, its input vector and the
 * eager logits; `<name>.bin` the input as little-endian f32 for `iree-run-module --input=<shape>xf32=@<name>.bin`.
 */
fun writeProbe(student: Student, model: Module<FP32, Float>, phrase: String, base: java.io.File) {
    val ctx = DirectCpuExecutionContext()
    val x = student.input(ctx, listOf(phrase))
    val n = student.inputShape.dimensions.last()
    val input = FloatArray(n) { x.data.get(0, it) }
    val logits = predict(student, model, listOf(phrase)).first()
    java.io.File("${base.path}.txt").writeText("$phrase\n${input.joinToString(",")}\n${logits.joinToString(",")}\n")
    val bytes = java.nio.ByteBuffer.allocate(n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    input.forEach { bytes.putFloat(it) }
    java.io.File("${base.path}.bin").writeBytes(bytes.array())
    println("probe: \"$phrase\" → ${base.path}.txt/.bin, input ${student.inputShape.dimensions.joinToString("x")}")
}
