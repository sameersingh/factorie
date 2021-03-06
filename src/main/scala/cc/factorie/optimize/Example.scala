package cc.factorie.optimize

import cc.factorie._
import cc.factorie.util._
import app.classify
import cc.factorie.la._

/**
 * Created by IntelliJ IDEA.
 * User: Alexandre Passos, Luke Vilnis
 * Date: 10/5/12
 * Time: 11:13 AM
 * To change this template use File | Settings | File Templates.
 */

/**
 * Main abstraction over a training example. It can compute a value and a gradient,
 * which are accumulated into accumulators and then given to the optimizer.
 */
trait Example {
  // gradient or value can be null if they don't need to be computed.
  // TODO should this be called (compute|accumulate)ValueAndGradient or something? -luke
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator): Unit
}

/**
 * Treats many examples as one. Useful in online training with a lot of parallelism.
 * @param baseExamples The examples in this batch.
 */
class MiniBatchExample(val baseExamples: Seq[Example]) extends Example {
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator) {
    baseExamples.foreach(_.accumulateValueAndGradient(value, gradient))
  }
}

object MiniBatchExample {
  /**
   * Groups a list of examples into mini batches.
   * @param batchSize The size of the batches
   * @param examples The examples to be split in mini batches
   * @return The mini batch examples
   */
  def apply(batchSize: Int, examples: Seq[Example]): Seq[MiniBatchExample] = {
    examples.grouped(batchSize).map(e => new MiniBatchExample(e.toSeq)).toSeq
  }
}

/**
 * Base example for maximizing log likelihood.
 *
 * Works by calling inference and getting a summary, and using the information
 * about the factor marginals in the summary to compute the value and gradient.
 *
 * It also works with MAP inference.
 * @param labels The first argument to inference
 * @param model The second argument to inference
 * @param infer The inference routine
 * @tparam A The type of the labels
 * @tparam B The type of the model
 */
class LikelihoodExample[A,B](labels: A, model: B, val infer: Infer[A,B]) extends Example {
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator): Unit = {
    val summary = infer.infer(labels, model)
    if (value != null)
      value.accumulate(-summary.logZ)
    val factorMarginals = summary.factorMarginals
    for (factorMarginal <- factorMarginals) {
      factorMarginal.factor match {
        case factor: DotFamily#Factor if factor.family.isInstanceOf[DotFamily] =>
          val aStat = factor.assignmentStatistics(TargetAssignment)
          if (value != null) value.accumulate(factor.statisticsScore(aStat))
          if (gradient != null) {
            gradient.accumulate(factor.family.weights, aStat)
            gradient.accumulate(factor.family.weights, factorMarginal.tensorStatistics, -1.0)
          }
        case factor: Family#Factor if !factor.family.isInstanceOf[DotFamily] =>
          if (value != null) value.accumulate(factor.assignmentScore(TargetAssignment))
      }
    }
  }
}

/**
 * An example which independently maximizes each label with respect to its neighbors' true assignments.
 *
 * You probably don't want to use it.
 * @param labels The set of labels
 * @param model The model
 */
class PseudomaxExample(labels: Iterable[LabeledDiscreteVar], model: Model with Parameters) extends Example {
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator): Unit = {
    for (label <- labels) {
      val factors = model.factors(label)
      val proportions = label.proportions(factors)
      val predIndex = proportions.maxIndex
      // TODO: this value is wrong - the loss is not defined in terms of log-normalized proportions, but raw scores (like perceptron/svm)
      // should we have a "scores" method like "proportions" that just goes through all possible settings and scores each?
      if (value != null) value.accumulate(math.max(0, proportions(label.targetIntValue) - proportions(predIndex)))
      if (gradient != null && predIndex != label.targetIntValue) {
        val predAssignment = new DiscreteAssignment1(label, predIndex)
        val groundAssignment = new DiscreteAssignment1(label, label.targetIntValue)
        for (f <- model.filterByFamilyClass[DotFamily](factors, classOf[DotFamily])) {
          gradient.accumulate(f.family.weights, f.assignmentStatistics(groundAssignment), 1.0)
          gradient.accumulate(f.family.weights, f.assignmentStatistics(predAssignment), -1.0)
        }
      }
    }
  }
}

// NOTE this doesn't seem to work too well in initial experiments... perhaps a margin is too strong a local requirement?
/**
 * A variant of PseudomaxExample which enforces a margin.
 *
 * You probably don't want to use it.
 * @param labels The labels
 * @param model The model
 */
class PseudomaxMarginExample(labels: Iterable[LabeledDiscreteVar], model: Model with Parameters) extends Example {
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator): Unit = {
    for (label <- labels) {
      val factors = model.factors(label)
      val proportionsNotAugmented = label.proportions(factors)
      val proportions = new DenseTensor1(proportionsNotAugmented.length)
      proportions -= (label.targetIntValue, 1.0)
      val predIndex = proportions.maxIndex
      // TODO: this value is wrong - see Pseudomax - luke
      if (value != null) value.accumulate(math.max(0, proportions(label.targetIntValue) - proportions(predIndex)))
      if (gradient != null && predIndex != label.targetIntValue) {
        val predAssignment = new DiscreteAssignment1(label, predIndex)
        val groundAssignment = new DiscreteAssignment1(label, label.targetIntValue)
        for (f <- model.filterByFamilyClass[DotFamily](factors, classOf[DotFamily])) {
          gradient.accumulate(f.family.weights, f.assignmentStatistics(groundAssignment), 1.0)
          gradient.accumulate(f.family.weights, f.assignmentStatistics(predAssignment), -1.0)
        }
      }
    }
  }
}

/**
 * Trains a model with pseudo likelihood.
 * @param labels The labels
 * @param model The model
 */
class PseudolikelihoodExample(labels: Iterable[LabeledDiscreteVar], model: Model with Parameters) extends Example {
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator): Unit = {
    for (label <- labels) {
      val factors = model.factors(label)
      val proportions = label.proportions(factors)
      if (value ne null) value.accumulate(math.log(proportions(label.targetIntValue)))
      if (gradient ne null) {
        val assignment = new DiscreteAssignment1(label, 0)
        var i = 0
        while (i < proportions.length) {
          assignment.intValue1 = i
          val p = if (i == label.targetIntValue) 1.0 - proportions(i) else -proportions(i)
          for (f <- model.filterByFamilyClass[DotFamily](factors, classOf[DotFamily]))
            gradient.accumulate(f.family.weights, f.assignmentStatistics(assignment), p) // TODO Consider instance weights here also?
          i += 1
        }
      }
    }
  }
}

/**
 * Generalization of pseudo likelihood to sets of variables, instead of a single one
 * @param components A list of sets of variables. Each set cannot be longer than four.
 * @param model The model
 */
class CompositeLikelihoodExample(components: Iterable[Iterable[LabeledDiscreteVar]], model: Model with Parameters) extends Example {
  def computeProportions(labels: Iterable[LabeledDiscreteVar], factors:Iterable[Factor]): (Proportions1, Int) = {
    val iterator = AssignmentIterator.assignments(labels.toSeq)
    val l = iterator.length
    val distribution = new DenseTensor1(l)
    var score = 0.0
    var i = 0
    var targetIndex = -1
    for (assignment <- iterator) {
      score = 0.0; factors.foreach(f => score += f.assignmentScore(assignment))   // compute score of variable with value 'i'
      distribution(i) = score
      if(labels.forall(v => assignment(v).intValue == v.targetIntValue)) targetIndex = i
      i += 1
    }
    distribution.expNormalize()
    (new DenseTensorProportions1(distribution.asArray, checkNormalization=false), targetIndex)
  }
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator): Unit = {
    for (component <- components) {
      val factors = model.factors(component) // should this be just DotFamily factors?
      val (proportions, targetIndex) = computeProportions(component, factors)
      if (value ne null) value.accumulate(math.log(proportions(targetIndex)))
      if (gradient ne null) {
        var i = 0
        for(assignment <- AssignmentIterator.assignments(component.toSeq)) {
          val p = if (i == targetIndex) 1.0 - proportions(i) else -proportions(i)
          for (f <- model.filterByFamilyClass[DotFamily](factors, classOf[DotFamily]))
            gradient.accumulate(f.family.weights, f.assignmentStatistics(assignment), p) // TODO Consider instance weights here also?
          i += 1
        }
      }
    }
  }
}

/**
 * An example for a single labeled discrete variable.
 *
 * This assumes the set of factors in the model does not change with the value of the variable.
 * @param label The variable
 * @param model The model
 */
class DiscreteLikelihoodExample(label: LabeledDiscreteVar, model: Model with Parameters) extends Example {
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator): Unit = {
    val factors = model.factors(label)
    val proportions = label.proportions(factors)
    if (value ne null) value.accumulate(math.log(proportions(label.targetIntValue)))
    if (gradient ne null) {
      val assignment = new DiscreteAssignment1(label, 0)
      var i = 0
      while (i < proportions.length) {
        assignment.intValue1 = i
        val p = if (i == label.targetIntValue) 1.0 - proportions(i) else -proportions(i)
        for (f <- model.filterByFamilyClass[DotFamily](factors, classOf[DotFamily]))
          gradient.accumulate(f.family.weights, f.assignmentStatistics(assignment), p) // TODO Consider instance weights here also?
        i += 1
      }
    }
  }
}

/**
 * A gradient from a single DiscreteVar, where the set of factors is allowed to change based on its value.
 * @param label The discrete var
 * @param model The model
 */
class CaseFactorDiscreteLikelihoodExample(label: LabeledMutableDiscreteVar[_], model: Model with Parameters) extends Example {
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator): Unit = {
    val proportions = label.caseFactorProportions(model)
    if (value ne null) value.accumulate(math.log(proportions(label.targetIntValue)))
    if (gradient ne null) {
      var i = 0
      while (i < proportions.length) {
        label := i
        val p = if (i == label.targetIntValue) 1.0 - proportions(i) else -proportions(i)
        // Note that label must be mutable here because there is no way to get different factors with an Assignment.  A little sad. -akm
        model.factorsOfFamilyClass[DotFamily](label).foreach(f => {
          gradient.accumulate(f.family.weights, f.currentStatistics, p) // TODO Consider instance weightsSet here also?
        })
        i += 1
      }
    }
  }
}

object GoodBadExample {
  /**
   * A convenient method for accumulating gradients which increase the score of "good" things
   * and decrease the score of "bad" things.
   * @param gradient The accumulator on which to put the gradient
   * @param model The model
   * @param good The variable whose score we want to increase
   * @param bad The variable whose score we want to decrease
   */
  def addGoodBad(gradient: WeightsMapAccumulator, model: Model with Parameters, good:Var, bad:Var) {
    model.factors(good).foreach({
      case f: DotFamily#Factor => gradient.accumulate(f.family.weights, f.currentStatistics)
      case _ => sys.error("Domination loss requires DotFamily")
    })
    model.factors(bad).foreach({
      case f: DotFamily#Factor => gradient.accumulate(f.family.weights, f.currentStatistics, -1.0)
      case _ => sys.error("Domination loss requires DotFamily")
    })
  }
}

/**
 * Implements the domination loss function: it penalizes models that rank any of
 * the badCandidates above any of the goodCandidates.
 *
 * The actual loss used in this version is the maximum (margin-augmented) difference between
 * goodCandidates and badCandidates.
 * @param goodCandidates The examples in the positive class
 * @param badCandidates The examples in the negative class
 * @param model The model
 *
 * You probably don't want to use this.
 */
class DominationLossExample(goodCandidates: Seq[Var], badCandidates: Seq[Var], model: Model with Parameters) extends Example {
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator) {
    require(gradient != null, "The DominationLossExample needs a gradient accumulator")
    val goodScores = goodCandidates.map(model.currentScore(_))
    val badScores = badCandidates.map(model.currentScore(_))
    val worstGoodIndex = goodScores.zipWithIndex.maxBy(i => -i._1)._2
    val bestBadIndex = badScores.zipWithIndex.maxBy(i => i._1)._2
    if (goodScores(worstGoodIndex) < badScores(bestBadIndex) + 1) {
      if (value != null) value.accumulate(goodScores(worstGoodIndex) - badScores(bestBadIndex) - 1)
      GoodBadExample.addGoodBad(gradient, model, goodCandidates(worstGoodIndex), badCandidates(bestBadIndex))
    }
  }
}

/**
 * Implements a variant of the the domination loss function.
 *
 * This is equivalent to one DominationLossExample per item in the positive class.
 * @param goodCandidates The examples in the positive class
 * @param badCandidates The examples in the negative class
 * @param model The model
 *
 * You probably don't want to use this.
 */
class DominationLossExampleAllGood(model: Model with Parameters, goodCandidates: Seq[Var], badCandidates: Seq[Var]) extends Example {
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator) {
    require(gradient != null, "The DominationLossExampleAllGood needs a gradient accumulator")
    val goodScores = goodCandidates.map(model.currentScore(_))
    val badScores = badCandidates.map(model.currentScore(_))
    val bestBadIndex = badScores.zipWithIndex.maxBy(i => i._1)._2
    for (i <- 0 until goodScores.length) {
      if (goodScores(i) < badScores(bestBadIndex) + 1) {
        if (value != null) value.accumulate(goodScores(i) - badScores(bestBadIndex) - 1)
        GoodBadExample.addGoodBad(gradient, model, goodCandidates(i), badCandidates(bestBadIndex))
      }
    }
  }
}

/**
 * Implements the structured perceptron. It's equivalent to maximum likelihood with MAP inference.
 * @param labels The first argument to inference
 * @param model The second argument to inference
 * @param infer The inference routine
 * @tparam A The type of the labels
 * @tparam B The type of the model
 */
class StructuredPerceptronExample[A,B](labels: A, model: B, infer: Maximize[A,B]) extends LikelihoodExample(labels, model, infer)

/**
 * Implements the structured SVM objective function, by doing loss-augmented inference.
 *
 * It needs to be a loss, not an objective.
 *
 * If you want to pass a specialized inference method use the structured perceptron example with loss-augmented inference directly.
 * @param labels The first argument to inference
 * @param model The second argument to inference
 * @param loss The loss function. It needs to score variables.
 * @param infer The inference routine
 * @tparam A The type of the labels
 */
class StructuredSVMExample[A](labels: A, model: Model with Parameters, loss: Model = HammingLoss, infer: Maximize[A,Model])
  extends StructuredPerceptronExample(labels, new CombinedModel(model, loss) with Parameters { override val parameters = model.parameters }, infer) {
  override def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator): Unit = {
    if (value != null) {
      val valueAcc = new LocalDoubleAccumulator(0.0)
      // TODO why are we doing this again? -luke
      super.accumulateValueAndGradient(valueAcc, gradient)
      // get a margin from LikelihoodExample (which equals value since value is the penalty of the most violated constraint)
      if (value != null) value.accumulate(valueAcc.value)
    }
  }
}

/**
 * Maximum likelihood in one semi supervised setting. It does constrained inference and maximizes the likelihood of
 * it when compared with unconstrained inference. This can implement Generalized Expectation and other algorithms.
 * @param labels The labels
 * @param model The model
 * @param inferConstrained Inference with constraints
 * @param inferUnconstrained Inference without constraints. Parameters will be learned to match these two inferences.
 * @tparam A The type of the labels
 * @tparam B The type of the model
 */
class SemiSupervisedLikelihoodExample[A,B](labels: A, model: B, inferConstrained: Infer[A,B], inferUnconstrained: Infer[A,B]) extends Example {
  def accumulateValueAndGradient(value: DoubleAccumulator, gradient: WeightsMapAccumulator): Unit = {
    val constrainedSummary = inferConstrained.infer(labels, model)
    val unconstrainedSummary = inferUnconstrained.infer(labels, model)
    if (value != null)
      value.accumulate(constrainedSummary.logZ - unconstrainedSummary.logZ)
    val factors = unconstrainedSummary.factorMarginals
    if (gradient != null) {
      for (factorMarginal <- factors; factorU <- factorMarginal.factor; if factorU.isInstanceOf[DotFamily#Factor]; factor <- factorU.asInstanceOf[DotFamily#Factor]) {
        gradient.accumulate(factor.family.weights, constrainedSummary.marginal(factor).asInstanceOf[FactorMarginal].tensorStatistics, 1.0)
        gradient.accumulate(factor.family.weights, unconstrainedSummary.marginal(factor).asInstanceOf[FactorMarginal].tensorStatistics, -1.0)
      }
    }
  }
}
