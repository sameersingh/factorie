package cc.factorie.la

import cc.factorie.util.{DoubleAccumulator, LocalDoubleAccumulator, Accumulator}
import cc.factorie.{WeightsMap, TensorSet, Weights}

// TODO why doesn't this implement Accumulator[WeightsMap]? -luke
// answer: it's hard - contravariance on the method arguments
trait WeightsMapAccumulator {
  def accumulate(key: Weights, t: Tensor): Unit
  def accumulate(key: Weights, t: Tensor, factor: Double): Unit
}

class LocalWeightsMapAccumulator(val tensorSet: WeightsMap) extends WeightsMapAccumulator {
  def accumulate(key: Weights, t: Tensor): Unit = tensorSet(key) += t
  def accumulate(key: Weights, index: Int, value: Double): Unit = tensorSet(key)(index) += value
  def accumulate(key: Weights, t: Tensor, factor: Double): Unit = tensorSet(key) += (t, factor)
  def combine(a: WeightsMapAccumulator): Unit = a match {
    case a: LocalWeightsMapAccumulator => tensorSet += a.tensorSet
  }
}

class SmartGradientAccumulator extends WeightsMapAccumulator {
  val map = new WeightsMap(w => throw new Error("trying to read an inexistent gradient"))
  val stateMap = collection.mutable.HashMap[Weights,Int]()
  val EMPTY = 0
  val SINGLE_TENSOR = 1
  val ACCUMULATOR = 3
  def clear() {
    map.clear()
    stateMap.clear()
  }
  def getMap: WeightsMap = map
  def accumulate(key: Weights, t: Tensor, d: Double) {
    stateMap.getOrElse(key, EMPTY) match {
      case ACCUMULATOR => map(key) += (t,d)
      case SINGLE_TENSOR =>
        val newTensor = map(key) match {
          case t: Outer1Tensor2 if t.tensor1.isDense && t.tensor2.isDense => new DenseTensor2(t.dim1, t.dim2)
          case t: DenseTensor1 => new DenseTensor1(t.dim1)
          case t: DenseTensor2 => new DenseTensor2(t.dim1, t.dim2)
          case t: DenseTensor3 => new DenseTensor3(t.dim1, t.dim2, t.dim3)
          case t: DenseTensor4 => new DenseTensor4(t.dim1, t.dim2, t.dim3, t.dim3)
          case t: Tensor1 => new SparseIndexedTensor1(t.dim1)
          case t: Tensor2 => new SparseIndexedTensor2(t.dim1, t.dim2)
          case t: Tensor3 => new SparseIndexedTensor3(t.dim1, t.dim2, t.dim3)
          case t: Tensor4 => new SparseIndexedTensor4(t.dim1, t.dim2, t.dim3, t.dim4)
          case _ => throw new Error(s"Any concrete tensor should be either a Tensor1, Tensor2, Tensor3, or Tensor4. Offending class: ${map(key).getClass.getName}")
        }
        newTensor += map(key)
        newTensor += (t,d)
        map(key) = newTensor
        stateMap(key) = ACCUMULATOR
      case EMPTY =>
        stateMap(key) = SINGLE_TENSOR
        t match {
          case t: SparseTensor if !t.isInstanceOf[SparseIndexedTensor] =>
            // This again suggests we really want more tensors supporting *=
            val newT = Tensor.newSparse(t)
            newT += (t,d)
            map(key) = newT
          case t: Singleton2BinaryLayeredTensor3 =>
            val newT = Tensor.newSparse(t)
            newT += (t,d)
            map(key) = newT
          case t: Tensor =>
            t *= d
            map(key) = t
        }
    }
  }

  def accumulate(key: Weights, t: Tensor) { accumulate(key, t, 1.0) }
}

class SynchronizedWeightsMapAccumulator(val tensorSet: WeightsMap) extends WeightsMapAccumulator {
  val l = new LocalWeightsMapAccumulator(tensorSet)
  override def accumulate(key: Weights, t: Tensor): Unit = l.synchronized { l.accumulate(key, t) }
  override def accumulate(key: Weights, t: Tensor, factor: Double): Unit = l.synchronized { l.accumulate(key, t, factor) }
}

class SynchronizedDoubleAccumulator extends DoubleAccumulator {
  val l = new LocalDoubleAccumulator()
  def accumulate(t: Double): Unit = { l synchronized { l.accumulate(t) } }
  def combine(ta: Accumulator[Double]): Unit = { l.synchronized { l.combine(ta)}}
}

