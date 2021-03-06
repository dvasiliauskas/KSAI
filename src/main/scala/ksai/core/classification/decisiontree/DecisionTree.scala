package ksai.core.classification.decisiontree

import java.util.NoSuchElementException

import akka.actor.ActorSystem
import akka.util.Timeout
import ksai.core.classification.decisiontree.SplitRule.SplitRule
import ksai.core.classification.{Attribute, NUMERIC}

import scala.annotation.tailrec

class DecisionTree(
                    trainingInstances: Array[Array[Double]],
                    val labels: Array[Int],
                    val importance: Array[Double],
                    val order: Array[Option[Array[Int]]],
                    root: Node,
                    val attributes: Array[Attribute],
                    val mtry: Int,
                    splitRule: SplitRule = SplitRule.GINI,
                    val nodeSize: Int = 1,
                    maxNodes: Int = 100,
                    maybeSamples: Option[Array[Int]] = None,
                    val noOfClasses: Int = 2
                  ) {

  def impurity(count: Array[Int], n: Int): Double = splitRule match {
    case SplitRule.GINI =>
      var impurity = 1.0
      var i = 0
      while (i < count.length) {
        val countElement = count(i)
        if (countElement > 0) {
          val p = countElement.toDouble / n
          impurity -= p * p
        }
        i += 1
      }

      impurity

    case SplitRule.ENTROPY =>
      var impurity = 0.0
      var i = 0
      while (i < count.length) {
        val countElement = count(i)
        if (countElement > 0) {
          val p = countElement.toDouble / n
          impurity -= p * (Math.log(p) / Math.log(2))
        }
        i += 1
      }

      impurity

    case SplitRule.CLASSIFICATION_ERROR =>
      val maxCount = count.map { labelCount =>
        if (labelCount > 0) labelCount.toDouble / n else 0.0
      }.max

      Math.abs(1 - Math.max(0, maxCount))
  }

  def predict(x: Array[Double]) = {
    root.predict(x, attributes)
  }
}

object DecisionTree {

  /**
    * Constructor. Learns a classification tree with (most) given number of
    * leaves. All attributes are assumed to be numeric.
    *
    * @param trainingInstances the training instances.
    * @param labels            the response variable.
    * @param maxNodes          the maximum number of leaf nodes in the tree.
    */
  def apply(trainingInstances: Array[Array[Double]], labels: Array[Int], maxNodes: Int)
           (implicit actorSystem: ActorSystem, timeout: Timeout): DecisionTree =
    apply(maybeAttributes = None, trainingInstances, labels, maxNodes)

  /**
    * Constructor. Learns a classification tree with (most) given number of
    * leaves.
    *
    * @param maybeAttributes   the attribute properties.
    * @param trainingInstances the training instances.
    * @param labels            the response variable.
    * @param maxNodes          the maximum number of leaf nodes in the tree.
    */
  def apply(maybeAttributes: Option[Array[Attribute]],
            trainingInstances: Array[Array[Double]],
            labels: Array[Int],
            maxNodes: Int)(implicit actorSystem: ActorSystem, timeout: Timeout): DecisionTree =
    apply(maybeAttributes, trainingInstances, labels, maxNodes, SplitRule.GINI)

  /**
    * Constructor. Learns a classification tree with (most) given number of
    * leaves.
    *
    * @param maybeAttributes   the attribute properties.
    * @param trainingInstances the training instances.
    * @param labels            the response variable.
    * @param maxNodes          the maximum number of leaf nodes in the tree.
    * @param splitRule         the splitting rule.
    */
  def apply(maybeAttributes: Option[Array[Attribute]],
            trainingInstances: Array[Array[Double]],
            labels: Array[Int],
            maxNodes: Int,
            splitRule: SplitRule)(implicit actorSystem: ActorSystem, timeout: Timeout): DecisionTree =
    apply(trainingInstances, labels, maxNodes, maybeAttributes, splitRule, 1, trainingInstances(0).length, None, None)

  /**
    * Constructor. Learns a classification tree.
    *
    * @param maybeAttributes   the attribute properties.
    * @param trainingInstances the training instances.
    * @param labels            the response variable.
    * @param nodeSize          the minimum size of leaf nodes.
    * @param maxNodes          the maximum number of leaf nodes in the tree.
    * @param mtry              the number of input variables to pick to split on at each
    *                          node. It seems that sqrt(p) give generally good performance, where p
    *                          is the number of variables.
    * @param splitRule         the splitting rule.
    * @param maybeOrder        the index of training values in ascending order. Note
    *                          that only numeric attributes need be sorted.
    * @param maybeSamples      the sample set of instances for stochastic learning.
    *                          samples[i] is the number of sampling for instance i.
    */
  def apply(
             trainingInstances: Array[Array[Double]],
             labels: Array[Int],
             maxNodes: Int,
             maybeAttributes: Option[Array[Attribute]],
             splitRule: SplitRule,
             nodeSize: Int,
             mtry: Int,
             maybeSamples: Option[Array[Int]],
             maybeOrder: Option[Array[Option[Array[Int]]]]
           )(implicit actorSystem: ActorSystem, timeout: Timeout): DecisionTree = {
    if (trainingInstances.length != labels.length) {
      throw new IllegalArgumentException(s"The length of training set and labels is not equal. " +
        s"${trainingInstances.length} != ${labels.length}")
    }

    if (mtry < 1 || mtry > trainingInstances(0).length) {
      throw new IllegalArgumentException("Invalid number of variables to split on at a node of the tree: " + mtry)
    }

    if (maxNodes < 2) {
      throw new IllegalArgumentException("Invalid maximum leaves: " + maxNodes)
    }

    if (nodeSize < 1) {
      throw new IllegalAccessException("Invalid minimum size of leaf nodes: " + nodeSize)
    }

    val uniqueLabels = labels.distinct.sorted

    checkForNegativeAndMissingValues(uniqueLabels)

    val noOfClasses = uniqueLabels.length

    if (noOfClasses < 2) {
      throw new IllegalArgumentException("Only one class")
    }

    val attributes = maybeAttributes.fold {
      trainingInstances(0).indices.map { index =>
        Attribute(`type` = NUMERIC, name = "V" + (index + 1))
      }.toArray
    }(identity)

    val order = maybeOrder.fold {
      attributes.indices.map { index =>
        attributes(index).`type` match {
          case NUMERIC =>
            val n = trainingInstances.length
            val a = new Array[(Double, Int)](n)
            (0 until n).foreach(i => a(i) = (trainingInstances(i)(index), i))
            Option(a.sortBy(_._1).map(_._2))
          case _ => None
        }
      }.toArray
    }(identity)

    val count = maybeSamples.fold {
      labels.groupBy(identity).mapValues(_.length).toSeq.sortBy(_._1).map(_._2).toArray
    } { samples =>
      labels.zip(samples).groupBy(_._1).mapValues(_.map(_._2)).mapValues(_.sum).toSeq.sortBy(_._1).map(_._2).toArray
    }

    val posteriori = uniqueLabels.map(uniqueLabel => count(uniqueLabel) / labels.length.toDouble)

    val root = Node(count.indexOf(count.max), Option(posteriori))

    val samples = maybeSamples.fold(Array.fill[Int](labels.length)(1))(identity)

    val trainRoot = TrainNode(root, trainingInstances, labels, samples)

    val decisionTree = new DecisionTree(trainingInstances, labels, new Array[Double](attributes.length), order, root, attributes, mtry, splitRule = splitRule, nodeSize = nodeSize, maxNodes = maxNodes, noOfClasses = noOfClasses)

    val nextSplits = new java.util.PriorityQueue[TrainNode]()

    if (trainRoot.findBestSplit(decisionTree)) {
      nextSplits.add(trainRoot)
    }

    splitBestLeaf(1, maxNodes, nextSplits, decisionTree)

    decisionTree
  }

  private def checkForNegativeAndMissingValues(uniqueLabels: Array[Int]): Unit = {
    uniqueLabels.indices.foreach { i =>
      if (uniqueLabels(i) < 0) {
        throw new IllegalArgumentException("Negative class label: " + uniqueLabels(i))
      }

      if (i > 0 && uniqueLabels(i) - uniqueLabels(i - 1) > 1) {
        throw new IllegalArgumentException("Missing class: " + (uniqueLabels(i) + 1))
      }
    }
  }

  private def splitBestLeaf(currentLeaves: Int,
                            maxNodes: Int,
                            nextSplits: java.util.PriorityQueue[TrainNode],
                            decisionTree: DecisionTree)(implicit actorSystem: ActorSystem, timeout: Timeout): Boolean = {
    try {
      (1 to maxNodes).foreach { _ =>
        val node = nextSplits.poll()
        node.split(Option(nextSplits), decisionTree)
      }
      true
    } catch {
      case _ => false
    }
  }
}
