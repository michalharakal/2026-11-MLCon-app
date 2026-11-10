package sk.ainet.examples.smarthome.distill

import sk.ainet.context.ExecutionContext
import sk.ainet.lang.nn.loss.CrossEntropyLoss
import sk.ainet.lang.nn.loss.Loss
import sk.ainet.lang.nn.loss.Reduction
import sk.ainet.lang.tensor.Tensor
import sk.ainet.lang.types.DType
import kotlin.math.exp

/**
 * Knowledge distillation (Hinton et al., 2015) as a SKaiNET [Loss]: cross-entropy of the student's logits, softened
 * by [temperature], against a target distribution, scaled by T² so gradients keep their size as T changes.
 *
 * The target is built by [target]: the teacher's probabilities at temperature T blended with the true label
 * (`alpha` teacher, `1 - alpha` label). Because cross-entropy is linear in the target, this is the classic two-term
 * loss with both terms at temperature T. `alpha = 0` is plain supervised training.
 */
class DistillationLoss(val temperature: Double = 2.0) : Loss {
    private val ce = CrossEntropyLoss()

    override fun <T : DType, V> forward(preds: Tensor<T, V>, targets: Tensor<out DType, *>, ctx: ExecutionContext, reduction: Reduction): Tensor<T, V> {
        val soft = preds.ops.mulScalar(preds, 1.0 / temperature)
        val loss = ce.forward(soft, targets, ctx, reduction)
        return loss.ops.mulScalar(loss, temperature * temperature)
    }

    companion object {
        /** Teacher log-scores (any scale: logits or log-probs) at [temperature], blended with the hard [label]. */
        fun target(teacherLogits: FloatArray?, label: Int, classes: Int, alpha: Double, temperature: Double): FloatArray {
            val t = FloatArray(classes)
            if (teacherLogits == null || alpha == 0.0) { t[label] = 1f; return t }
            val m = teacherLogits.max()
            val e = DoubleArray(classes) { exp((teacherLogits[it] - m) / temperature) }
            val z = e.sum()
            for (i in 0 until classes) t[i] = (alpha * e[i] / z).toFloat()
            t[label] += (1 - alpha).toFloat()
            return t
        }
    }
}
