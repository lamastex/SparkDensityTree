/**************************************************************************
* Copyright 2017 Tilo Wiklund
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
**************************************************************************/
import scalaz.{ Ordering => OrderingZ, _ }
import Scalaz._

import vegas._

import scala.math.{min, max, exp, log}
import scala.math.BigInt._

import scala.collection.mutable.{ HashMap, PriorityQueue }
import scala.collection.mutable.{ Set => MSet, Map => MMap }
import scala.collection.{mutable, immutable}
import scala.collection.immutable.{Set, Map}

import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg
import org.apache.spark.mllib.linalg.{ Vector => MLVector, _ }
import org.apache.spark.mllib.random.RandomRDDs.normalVectorRDD

import org.apache.spark.{ SparkContext, SparkConf }
import org.apache.spark.sql.SQLContext
import org.apache.log4j.{ Logger, Level }

import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets

// import scala.util.Sorting

object ScalaDensity {

  // Axis parallel (bounding) boxes

  type Axis = Int
  type Intercept = Double
  type Volume = Double

  case class Rectangle(low : Array[Double], high : Array[Double]) {
    override def toString = (low, high).zipped.toArray.mkString("x")

    def dim() = high.length

    def centre(along : Axis) : Double =
      (high(along) + low(along))/2

    def split(along : Axis) : (Rectangle, Rectangle) = split(along, centre(along))

    def split(along : Axis, intercept : Intercept) : (Rectangle, Rectangle) = {
      val c = min(max(intercept, low(along)), high(along))
      (Rectangle(low,                   high.updated(along, c)),
       Rectangle(low.updated(along, c), high                  ))
    }

    def lower(along : Axis) : Rectangle = split(along)._1
    def lower(along : Axis, intercept : Intercept) : Rectangle =
      split(along, intercept)._1

    def upper(along : Axis) : Rectangle = split(along)._2
    def upper(along : Axis, intercept : Intercept) : Rectangle =
      split(along, intercept)._2

    def volume() : Volume =
      ((high, low).zipped map (_-_)) reduce (_*_)
  }

  def hull(b1 : Rectangle, b2 : Rectangle) : Rectangle =
    Rectangle( ( b1.low, b2.low ).zipped map min,
               (b1.high, b2.high).zipped map max )

  def point(v : MLVector) : Rectangle = Rectangle(v.toArray, v.toArray)

  def boundingBox(vs : RDD[MLVector]) : Rectangle =
    vs.map(point(_)).reduce(hull)

  // (The) infinite binary tree

  case class NodeLabel(lab : BigInt) {
    private val rootLabel : BigInt = 1

    def    left() : NodeLabel = NodeLabel(2*lab)
    def   right() : NodeLabel = NodeLabel(2*lab + 1)
    def isRight() : Boolean   =  lab.testBit(0)
    def  isLeft() : Boolean   = !lab.testBit(0)

    def depth() : Int = lab.bitLength - 1

    // WARNING: Not checking to make sure we're sufficiently deep
    def ancestor(level : Int) : NodeLabel = NodeLabel(lab >> level)
    def parent() : NodeLabel = ancestor(1)
    def sibling() : NodeLabel =
      if(isLeft()) parent().right else parent().left

    def children() : Set[NodeLabel] =
      Set(left(), right())

    def ancestors() : Stream[NodeLabel] =
      Stream.iterate(this)({_.parent}).takeWhile(_.lab >= rootLabel).tail
  }

  val rootLabel : NodeLabel = NodeLabel(1)

  def isAncestorOf(a : NodeLabel, b : NodeLabel) : Boolean =
    a.lab < b.lab && (b.ancestor(b.depth - a.depth) == a)

  def isDescendantOf(a : NodeLabel, b : NodeLabel) : Boolean = isAncestorOf(b, a)

  // Leaf-labelled finite (truncated) binary trees

  case class TruncatedTree[A](leafValues : Map[NodeLabel, A]) {
    // Warning: Will just fail if `at` is not currently a leaf node
    def splitLeaf(at : NodeLabel, by : A => (A, A)) : TruncatedTree[A] = {
      val (lvalue, rvalue) = by(leafValues(at))
      TruncatedTree(leafValues - at + (at.left -> lvalue, at.right -> rvalue))
    }

    def leafNodes() : Set[NodeLabel] = leafValues.keySet
    def hasLeaf(at : NodeLabel) : Boolean = leafValues.isDefinedAt(at)
    // WARNING: Does no checking
    def leafValue(lab : NodeLabel) : A = leafValues(lab)

    def leaves() : List[(NodeLabel, A)] = leafValues.toList

    // WARNING: Does not check to make sure start is an internal node
    // TODO: Turns this into a general traveral
    def dftInternalFrom(start : NodeLabel) : Stream[NodeLabel] = {
      def goRight(lab : NodeLabel) : Stream[NodeLabel] = {
        if(!hasLeaf(lab.right)) {
          goLeft(lab.right)
        } else {
          val Some(bt) = Stream.iterate(lab)(_.parent).find {
            x =>
            (x.isLeft && !hasLeaf(x.sibling)) || x == start
          }

          if(bt == start)
            Stream.empty
          else
            goLeft(bt.sibling)
        }
      }

      def goLeft(lab : NodeLabel) : Stream[NodeLabel] = {
        lazy val next =
          if(hasLeaf(lab.left))
            goRight(lab)
          else
            goLeft(lab.left)

        lab #:: next
      }

      if(hasLeaf(start))
        Stream.empty
      else
        goLeft(start)
    }

    def dftInternal() : Stream[NodeLabel] = dftInternalFrom(rootLabel)

    def mergeCherry(at : NodeLabel, by : (A, A) => A) : TruncatedTree[A] = {
      val value = by(leafValues(at.left), leafValues(at.right))
      TruncatedTree(((leafValues - at.left) - at.right) + (at -> value))
    }
  }

  def rootTree[A](value : A) : TruncatedTree[A] =
    TruncatedTree(Map(rootLabel -> value))

  // Piecewise constant functions on binary spatial partitions

  def descendSpatialTree(rootBox : Rectangle, point : MLVector) : Stream[(Rectangle, NodeLabel)] = {
    val d = rootBox.dim

    def step(box : Rectangle, lab : NodeLabel) : (Rectangle, NodeLabel) = {
      val along = lab.depth % d

      if(point(along) <= box.centre(along))
        (box.lower(along),  lab.left)
      else
        (box.upper(along), lab.right)
    }

    Stream.iterate((rootBox, rootLabel))(Function.tupled(step))
  }

  case class PCFunction[A](rootBox : Rectangle, partition : TruncatedTree[(Rectangle, A)]) {
    private def queryNode(point : MLVector) : NodeLabel =
      descendSpatialTree(rootBox, point).map(_._2).dropWhile(!partition.hasLeaf(_)).head

    private def valueAtNode(lab : NodeLabel) : (Rectangle, A) = partition.leafValue(lab)

    def apply(point : MLVector) : (NodeLabel, Rectangle, A) = {
      val lab = queryNode(point)
      val (box, a) = valueAtNode(lab)
      (lab, box, a)
    }

    def splitCell(at : NodeLabel, by : A => (A, A)) : PCFunction[A] = {
      val along = at.depth % rootBox.dim

      def actualBy(box : Rectangle, value : A) : ((Rectangle, A), (Rectangle, A)) = {
        val (lvalue, rvalue) = by(value)
        val (lbox, rbox) = box.split(along)
        ((lbox, lvalue), (rbox, rvalue))
      }

      PCFunction(rootBox, partition.splitLeaf(at, Function.tupled(actualBy)))
    }

    def mergeCell(at : NodeLabel, by : (A, A) => A) : PCFunction[A] = {
      def actualBy(lvals : (Rectangle, A), rvals : (Rectangle, A)) : (Rectangle, A) =
        (hull(lvals._1, rvals._1), by(lvals._2, rvals._2))

      PCFunction(rootBox, partition.mergeCherry(at, actualBy))
    }

    def cells() : List[(NodeLabel, Rectangle, A)] =
      partition.leaves().map { case (k, (r, x)) => (k, r, x) }
  }

  def constantPCFunction[A](rootBox : Rectangle, value : A) : PCFunction[A] =
    PCFunction(rootBox, rootTree((rootBox, value)))

  // Histograms

  case class Histogram(total : Long, counts : PCFunction[Long]) {
    def density(point : MLVector) : Double = {
      val (_, box, count) = counts(point)
      count / (box.volume * total)
    }
  }

  // type InfiniteTree[T] = NodeLabel => T
  // type FiniteTree[T] = Map[NodeLabel, T]
  // type Leaves[T] = Map[NodeLabel, T]
  // type Internal[T] = Map[NodeLabel, T]

  // // Partitions

  // // TODO: Clarify (or make explicitly "undefined") semantics of points outside
  // // the root bounding box

  // // TODO: Make FiniteParition a trait with the queryPart method

  // // A cell is nothing but its bounding rectangle
  // case class Cell(bound : Rectangle)
  // // A SplitCell is a rectangle split into two parts by a axis-parallel hyperplane
  // case class SplitCell(bound : Rectangle, axis : Axis, intercept : Intercept) {
  //   def toCell() : Cell = Cell(bound)
  // }

  // // WARNING: No checking is ever done to make sure leaves are the actual leaves!
  // // NOTE: This is a simple, compact representation of TruncatedSplitPartition that can
  // //       be sent to spark workers
  // case class FiniteSplitPartition(splits : FiniteTree[SplitCell], leaves : Leaves[Cell]) {
  //   def queryPart(v : MLVector) : NodeLabel = {
  //     var node = rootLabel
  //     while(splits.isDefinedAt(node)) {
  //       val cell = splits(node)
  //       if(v(cell.axis) < cell.intercept) {
  //         node = left(node)
  //       } else {
  //         node = right(node)
  //       }
  //     }
  //     node
  //   }
  // }

  // // A finite partition defined by truncating a binary splitting tree at some leaf nodes
  // case class TruncatedSplitPartition(splits : InfiniteTree[SplitCell], leaves : Leaves[Cell]) {
  //   def toFinite() : FiniteSplitPartition = {
  //     // TODO: Figure out if there is a tie-the-knot way to recursively
  //     //       define immutable Maps in Scala
  //     var truncSplits = MMap()
  //     for(leaf <- leaves.keySet) {
  //       for(lab <- ancestors(leaf).takeWhile(!truncSplits.isDefinedAt(_)))
  //         truncSplits += (lab, splits(lab))
  //     }
  //     // Freeze mutable map, sadly there appears to be no more efficent way to do it
  //     FiniteSplitPartition(truncSplits.toMap, leaves)
  //   }

  //   def queryPart(v : MLVector) : NodeLabel = {
  //     var node = rootLabel
  //     while(!leaves.isDefinedAt(node)) {
  //       val cell = splits(node)
  //       if(v(cell.axis) < cell.intercept) {
  //         node = left(node)
  //       } else {
  //         node = right(node)
  //       }
  //     }
  //     node
  //   }

  //   def extend() : InfiniteSplitPartition = InfiniteSplitPartition(splits)

  //   // WARNING: No checking is done to make sure oldLeaves is a subset of leaves!
  //   def splitLeaves(oldLeaves : Set[NodeLabel]) : TruncatedSplitPartition = {
  //     val newLeaves = leafSet.flatMap(x => Set(left(x), right(x)))
  //     TruncatedSplitPartition(splits, (leaves -- oldLeaves) ++ newLeaves)
  //   }

  //   // WARNING: No checking is done to ensure that lab is a cherry!
  //   def truncateCherry(lab : NodeLabel) : TruncatedSplitPartition =
  //     TruncatedSplitPartition(splits, (leaves -- Set(left(lab), right(lab))) ++ (lab, splits(lab)))
  // }

  // // An InfiniteSplitPartition defines a partitioning schemes by specifying splitting hyperplanes
  // case class InfiniteSplitPartition(splits : InfiniteTree[SplitCell]) {
  //   def truncate(leafset : Set[NodeLabel]) : TruncatedSplitPartition =
  //     TruncatedSplitPartition(splits, leafset.map(leaf => (leaf, splits(leaf).toCell)).toMap)
  //   def truncate() : TruncatedSplitPartition = truncate(Set(rootLabel))
  // }

  // // TODO: Compare memory use and performance with non-caching version
  // // TODO: Maybe there's some more clever memoizing version that only keeps recently used keys?
  // // A partitioning scheme given by recursively cutting rectangles in half, cycling through all axes
  // def binarySplitTree(bbox : Rectangle) : InfiniteSplitPartition = {
  //   val d = bbox.dim

  //   // Tree that caches bounding box and depth of every node
  //   lazy val t : InfiniteTree[(Rectangle, Int, Volume)] =
  //     Memo.mutableHashMapMemo {
  //       case `rootLabel` => (bbox, bbox.centre(0), 0)
  //       case n =>
  //         val (pbox, paxis, pintercept) = t(parent(n))
  //         val box =
  //           if(isRight(n))
  //             pbox.upper(paxis, pintercept)
  //           else
  //             pbox.lower(paxis, pintercept)
  //           val axis = (paxis + 1) % d
  //           (box, axis, box.centre(axis))
  //     }

  //   return (lab => t(lab) match { case (r, a, x) => SplitCell(r, a, x) })
  // }

  // // Histograms and filtrations of histograms

  // type Count = Long
  // def densityFormula(c : Count, v : Volume, n : Count) : Double = c/(v * n)

  // // WARNING: counts will only contain the non-zero counts!
  // // TODO: Parameterise Histogram over the partition type once its given a trait
  // // A histogram is a (finite) partition combined with Count labels for each part
  // case class Histogram( partition : TruncatedSplitPartition,
  //                       total : Count,
  //                       counts : Leaves[Count] ) {

  //   // WARNING: Does not check that lab is a leaf!
  //   def densityAtNode(lab : NodeLabel) : Double =
  //     densityFormula(counts(lab), partition.splits(lab).bound.volume, total)

  //   // Gives the density given by the histogram at point p
  //   def density(p : MLVector) : Double =
  //     densityAtNode(partition.queryPart(p))

  //   // NOTE: This is only approximate since we do not collapse cherries
  //   //       that no longer satisfy the splitting criterion after removing
  //   //       the point.
  //   // ALmost computes the standard leave one out L2 error estimate
  //   def looL2ErrorApprox() : Double = {
  //     counts.map {
  //       (x : NodeLabel, c : Count) =>
  //       // val c = counts(x)
  //       val v = partition.splits(x).bound.volume
  //   	  val dtotsq = (c/total)*(c/total)/v // (c/(v*total))^2 * v
  //       val douts = c*(c-1)/(v*(total - 1)*total) // 1/total * (c-1)/(v*(total-1)) * c
  //       dtotsq - 2*douts
  //     }.sum
  //   }

  //   def logQuasiLik() : Double =
  //     counts.map {
  //       // val c = counts(x)
  //       case (x, c) => if(c == 0) 0 else c*log(densityAtNode(x))
  //     }.sum

  //   def logPenalisedQuasiLik(taurec : Double) : Double =
  //     log(exp(taurec) - 1) - counts.size*taurec + logQuasiLik()

  //   // NOTE: Warning, does not check to make sure something is a cherry!
  //   def cutCherry(lab : BigInt) : Histogram = {
  //     val cherryCount = counts.getOrElse(left(lab), 0) + counts.getOrElse(right(lab), 0)
  //     Histogram(partition.truncateCherry(lab), total, (counts -- children(lab)) ++ (lab, cherryCount))
  //   }

  //   // TODO: Make this lazy?
  //   def completeCounts() : FiniteTree[Count] = {
  //     var currCounts = MMap(counts.toSeq: _*)
  //     for((lab, c) <- counts) {
  //       for(labanc <- ancestors(lab)) {
  //         currCountsb.update(labanc, currCountsb.getOrElse(labanc, 0) + c)
  //       }
  //     }
  //     currCounts.toMap
  //   }

  //   // TODO: Change this so as to take an ordering on (NodeLabel, Count, Volume) instead?
  //   def backtrack[H](prio : (NodeLabel, Count, Volume) => H)(implicit ord : Ordering[H])
  //       : Stream[(NodeLabel, Histogram)] = {

  //     // Rewrite this in terms of PartialOrder[NodeLabel] and lexicographic order
  //     object BacktrackOrder extends Ordering[(H, NodeLabel)] {
  //       def compare(x : (H, NodeLabel), y : (H, NodeLabel)) = {
  //         val (xH, xLab) = x
  //         val (yH, yLab) = y
  //         if(isAncestorOf(xLab, yLab)) 1
  //         else if(isAncestorOf(yLab, xLab)) -1
  //         else ord.compare(xH, yH)
  //       }
  //     }

  //     var q = new PriorityQueue()(BacktrackOrder.reverse)

  //     // TODO: There should be a nicer way to do this
  //     val allcounts = completeCounts()

  //     depthFirst(h.splits.leaves).foreach {
  //       case lab =>
  //         q += ((prio(lab, allcounts(lab), parition.splits(lab).bound.volume), lab))
  //     }

  //     val sorted : Stream[(H, NodeLabel)] = q.dequeueAll
  //     sorted.scanLeft((BigInt(0), h)) {
  //       // NOTE: This results in two extra lookups, but is nicer API-wise
  //       case ((_, hc), (_, lab)) => (lab, cutCherry(hc, lab))
  //     }.tail
  //   }
  // }

  // // Rule to decide whether or not to split a cell based on count and bounding box
  // type SplitRule = (Count, Rectangle) => Boolean

  // // Just dumps any additional information that may be useful
  // case class Refinements(leavesSplit : Set[NodeLabel], counts : Leaves[Count])

  // case class PartitionedPoints( partition : TruncatedSplitPartition,
  //                                   cells : RDD[(NodeLabel, MLVector)] ) {

  //   def refineWithUpdates(splitrule : SplitRule) : (PartitionedPoints, Refinements) = {
  //     val counts = cells.countByKey()

  //     val oldLeaves = counts.filter {
  //       case (lab, count) =>
  //         splitrule(count, partition.leaves(lab).bound)
  //     }.keySet

  //     val newPartition = partition.splitLeaves(oldLeaves)

  //     // NOTE: This is a compact representation that can serialised and sent to
  //     // spark workers
  //     // TODO: Benchmark Map vs HashMap vs ... since this is just a temporary
  //     // structure we're free to use anything serialisable
  //     val splits = oldLeaves.map {
  //       case lab =>
  //         val cell = partition.splits(lab)
  //         (lab, (cell.axis, cell.intercept))
  //     }.toMap

  //     val newCells = cells.map {
  //       case (k : NodeLabel, v : MLVector) =>
  //         splits.get(k) match {
  //           case Some((axis, intercept)) =>
  //             if(v(axis) < intercept) {
  //               (left(k), v)
  //             } else {
  //               (right(k), v)
  //             }
  //           case None =>
  //             (k, v)
  //         }
  //     }

  //     (PartitionedPoints(newPartition, newCells), Refinements(oldLeaves, counts))
  //   }

  //   def refine(splitrule : SplitRule) : PartitionedPoints = refineWithUpdates(stoprule)._1
  // }

  // // TODO: Move this into TruncatedSplitPartition
  // def partitionPoints( points : RDD[MLVector],
  //                   partition : TruncatedSplitPartition) :
  //     PartitionedPoints = {
  //   // NOTE: FiniteSplitPartition can be serialised and sent to workers
  //   val finitePartition = partition.toFinite()
  //   PartitionedPoints(truncated, points.map(x => (finitePartition.queryPart(x), x)))
  // }

  // // TODO: Taking both Histogram and Points separately makes little semantic
  // // sense, but I don't want to define yet another class like
  // // "populatedHistogram" or similar...
  // // TODO: Change this to just take partitioned points and recount, this also enables us to
  // // switch to a different set of points and makes more semantic sense
  // def histogramFromSplitUntil( partitioned : PartitionedPoints,
  //                                splitrule : SplitRule ) :
  //     (Histogram, PartitionedPoints) = {
  //   var currentPartitioned = partitioned
  //   var currentCounts = MMap.empty
  //   do {
  //     // case class Refinements(leavesSplit : Set[NodeLabel], counts : Leaves[Count])
  //     val (newPartitioned, refinements) = partitionedPoints.refineWithUpdates(splitrule)
  //     currentPartitioned = newPartitioned.localCheckpoint()
  //     currentCounts = refinements.counts
  //   } while(!refinements.leavesSplit.isEmpty)
  //   (Histogram(currentPartitioned.partition, currentCounts.toMap), currentPartitioned)
  // }

  // def histogramSplitUntil( points : RDD[MLVector],
  //                       partition : InfiniteSplitPartition,
  //                       splitrule : SplitRule ) :
  //     (Histogram, PartitionedPoints) = {
  //   val partitioned = partitionPoints(points, partition.truncate())
  //   histogramFromSplitUntil(partitioned, splitrule)
  // }

  // // Compute histogram given volume and count bounds
  // def histogram( points : RDD[MLVector],
  //             partition : InfiniteSplitPartition,
  //             minPoints : Count,
  //                minVol : Volume ) : Histogram = {
  //   def splitrule(count : Count, box : Rectangle) : Boolean =
  //     (count >= minPoints) && (box.volume > minVol)
  //   histogramSplitUntil(points, partition, splitRule)._1
  // }

  // def histograms( points : RDD[MLVector],
  //              partition : InfiniteSplitPartition,
  //              minPoints : Count,
  //                 minVol : Volume ) : Stream[Histogram] = {
  //   val hBase = histogram(points, partition, minPoints, minVol)

  //   val n = hBase.total

  //   def joinPrio(lvl : Int, lab : NodeLabel, c : Count, v : Volume) : Count = c

  //   hBase #:: backtrack(hBase, joinPrio)(Ordering[Count].reverse).map(_._2)
  // }

  // def supportCarvedHistogram( points : RDD[MLVector],
  //                          partition : InfiniteSplitPartition,
  //                     splitThreshold : Double ) :
  //     Histogram = {
  //   val n = points.count()
  //   // TODO: Factor this out
  //   def splitrule(count : Count, box : Rectangle) : Boolean =
  //     (count == n) | ((1 - c/n) * box.volume >= splitThreshold)
  //   histogramSplitUntil(points, partition, splitRule)
  // }

  // def supportCarvedHistograms( points : RDD[MLVector],
  //                           partition : InfiniteSplitPartition,
  //                      splitThreshold : Double ) :
  //     Stream[Histogram] = {

  //   val volumeThreshold = 0.000000001
  //   val hBase = supportCarvedHistogram(points, partition, splitThreshold)

  //   val n = hBase.total

  //   def joinPrio(lvl : Int, lab : NodeLabel, c : Count, v : Volume) : Double =
  //     (1 - c/n)*v

  //   hBase #:: backtrack(hBase, joinPrio)(Ordering[Double].reverse).map(_._2)
  // }

  // def runTemp( points : RDD[MLVector],
  //           partition : InfiniteSplitPartition,
  //      carveThreshold : Double,
  //      countThreshold : Count,
  //                 tau : Double ) : Histogram = {
  //   val minVol = 0.00001

  //   val optFromRoot = histograms(points, partition, countThreshold, minVol).
  //     min(Ordering[?????].on(?????))

  //   val carvedPartition =
  //     supportCarvedHistograms(points, partition, carveThreshold).
  //       min(Ordering[Double].on(logPenalisedQuasiLik(_, 1/tau))).
  //       partition

  //   def splitrule(count : Count, box : Rectangle) : Boolean =
  //     (count >= countThreshold) && (box.volume > minVol)

  //   val optFromCarved = histogramFromSplitUntil(partitionPoints(points, carvedPartition), splitrule).
  //     takeWhile(_ != carvedPartition).
  //     min(Ordering[?????].on(?????))

  //   if(???(optFromCarved) ? ???(optFromRoot))
  //     optFromCarved
  //   else
  //     optFromRoot
  // }

  // // def dumpHist(df : RDD[MLVector], h : Histogram, path : String) : Unit =
  // //   Files.write(
  // //     Paths.get(path),
  // //     Vegas("Histogram").
  // //       withData( df.collect().toSeq.map(x =>
  // //                  Map("x" -> x(0), "y" -> x(1),
  // //                      "d" -> density(h, Vectors.dense(x(0), x(1))),
  // //                      "n" -> queryPart(h, Vectors.dense(x(0), x(1)))
  // //                  )
  // //                ) ).
  // //       encodeX("x", Quant).
  // //       encodeY("y", Quant).
  // //       encodeOpacity("d", Quantitative).
  // //       // encodeColor("n", Nom).
  // //       mark(Point).
  // //       html.pageHTML().getBytes(StandardCharsets.UTF_8))

  // // def enumerate[A](xs : Stream[A]) : Stream[(Int, A)] =
  // //   (Stream.from(1), xs).zipped.toStream

  // def main(args: Array[String]) = {
  //   Logger.getLogger("org").setLevel(Level.ERROR)
  //   Logger.getLogger("akka").setLevel(Level.ERROR)
  //   val conf = new SparkConf().setAppName("ScalaDensity").setMaster("local[2]")
  //   val sc = new SparkContext(conf)

  //   val n = 200
  //   val df = normalVectorRDD(sc, n, 2)
  //   val partition = binarySplitTree(boundingBox(df))

  //   for(temp <- Array(0.5, 1, 2)) {
  //     println(temp)
  //     println(runTemp(df, partition, 0.005, 10, temp).looL2ErrorApprox())
  //   }

  //   sc.stop()
  // }
}
