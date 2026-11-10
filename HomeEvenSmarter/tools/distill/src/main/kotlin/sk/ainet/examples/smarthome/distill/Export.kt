package sk.ainet.examples.smarthome.distill

import sk.ainet.compile.hlo.StableHloConverterFactory
import sk.ainet.context.ExecutionContext
import sk.ainet.lang.graph.ComputeGraph
import sk.ainet.lang.graph.DefaultExecutionTape
import sk.ainet.lang.graph.DefaultGraphExecutionContext
import sk.ainet.lang.nn.Module
import sk.ainet.lang.tensor.Shape
import sk.ainet.lang.tensor.VoidOpsTensor
import sk.ainet.lang.tensor.data.DenseFloatArrayTensorData
import sk.ainet.lang.tensor.data.TensorData
import sk.ainet.lang.tensor.ops.VoidTensorOps
import sk.ainet.lang.types.FP32
import sk.ainet.examples.smarthome.router.RouterFeatures
import sk.ainet.tape.Execution

/**
 * The trained router as StableHLO: the same [Router.model] definition is rebuilt on a tracing context, the trained
 * weights are copied in as dense FP32, and one forward pass is recorded. Weights become `stablehlo.constant`s, the
 * features `[1, DIM]` are the only argument, the logits `[1, classes]` the only result. IREE compiles this text.
 */
object Export {
    const val FUNCTION = "main"

    fun stableHlo(trained: Module<FP32, Float>): String {
        val ctx = DefaultGraphExecutionContext.tape(baseOps = VoidTensorOps())
        val traced = Router.model(ctx)
        val src = trained.trainableParameters()
        val dst = traced.trainableParameters()
        check(src.map { it.name } == dst.map { it.name }) { "trained and traced parameters differ: ${src.map { it.name }} vs ${dst.map { it.name }}" }
        for ((s, d) in src.zip(dst)) {
            val shape = s.value.shape
            val n = shape.dimensions.fold(1) { a, b -> a * b }
            val floats = FloatArray(n)
            var i = 0
            @Suppress("UNCHECKED_CAST")
            val trainedData = s.value.data as TensorData<FP32, Float>
            forEachIndex(shape.dimensions) { idx -> floats[i++] = trainedData.get(*idx) }
            @Suppress("UNCHECKED_CAST")
            (d.moduleParameter as sk.ainet.lang.nn.topology.ModuleParameter<FP32, Float>).value = ctx.fromData(DenseFloatArrayTensorData<FP32>(shape, floats) as TensorData<FP32, Float>, FP32::class)
        }
        val graph = trace(ctx) { traced.forward(void(Shape(1, RouterFeatures.DIM)), it) }
        val mlir = StableHloConverterFactory.createExtended().convert(graph, FUNCTION).content
        check("stablehlo.constant" in mlir) { "the trained weights did not fold into constants" }
        check("tensor<1x${Router.classes}xf32>" in mlir) { "the exported function does not return tensor<1x${Router.classes}xf32>" }
        return mlir
    }

    private fun forEachIndex(dims: IntArray, f: (IntArray) -> Unit) {
        val idx = IntArray(dims.size)
        val total = dims.fold(1) { a, b -> a * b }
        repeat(total) {
            f(idx)
            var k = dims.size - 1
            while (k >= 0) { idx[k]++; if (idx[k] < dims[k]) break; idx[k] = 0; k-- }
        }
    }

    private fun trace(ctx: DefaultGraphExecutionContext, body: (ExecutionContext) -> Unit): ComputeGraph {
        val tape = ctx.record {
            val current = (this as DefaultGraphExecutionContext).currentTape ?: error("no tape")
            Execution.tapeStack.pushTape(current)
            try { body(this as ExecutionContext) } finally { Execution.tapeStack.popTape() }
        }.first
        return (tape as DefaultExecutionTape).toComputeGraph(synthesizeExternalInputs = true, embedConstants = true)
    }

    private fun void(shape: Shape): VoidOpsTensor<FP32, Float> = VoidOpsTensor(
        object : TensorData<FP32, Float> {
            override val shape: Shape = shape
            override fun get(vararg indices: Int): Float = 0.0f
            override fun set(vararg indices: Int, value: Float) {}
        },
        FP32::class,
    )
}
