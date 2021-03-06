// Copyright 2014,2015,2016,2017 Commonwealth Bank of Australia
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package commbank.grimlock.framework

import commbank.grimlock.framework.aggregate.{ Aggregator, AggregatorWithValue }
import commbank.grimlock.framework.content.Content
import commbank.grimlock.framework.distance.PairwiseDistance
import commbank.grimlock.framework.distribution.ApproximateDistribution
import commbank.grimlock.framework.encoding.Value
import commbank.grimlock.framework.environment.Context
import commbank.grimlock.framework.environment.tuner.Tuner
import commbank.grimlock.framework.pairwise.{ Comparer, Operator, OperatorWithValue }
import commbank.grimlock.framework.partition.{ Partitioner, PartitionerWithValue }
import commbank.grimlock.framework.position.{ Position, Positions, Slice }
import commbank.grimlock.framework.sample.{ Sampler, SamplerWithValue }
import commbank.grimlock.framework.squash.{ Squasher, SquasherWithValue }
import commbank.grimlock.framework.statistics.Statistics
import commbank.grimlock.framework.transform.{ Transformer, TransformerWithValue }
import commbank.grimlock.framework.utility.{ =:!=, Distinct, Escape, Quote }
import commbank.grimlock.framework.window.{ Window, WindowWithValue }

import org.apache.hadoop.io.Writable

import scala.reflect.ClassTag

import shapeless.{ Nat, Witness }
import shapeless.nat.{ _0, _1, _2, _3, _4, _5, _6, _7, _8, _9 }
import shapeless.ops.nat.{ Diff, LTEq, GT, GTEq, ToInt }

/** Trait for matrix operations. */
trait Matrix[L <: Nat, P <: Nat, U[_], E[_], C <: Context[U, E]] extends Persist[Cell[P], U, E, C] {
  /**
   * Change the variable type of `positions` in a matrix.
   *
   * @param slice     Encapsulates the dimension(s) to change.
   * @param tuner     The tuner for the job.
   * @param positions The position(s) within the dimension(s) to change.
   * @param schema    The schema to change to.
   * @param writer    The writer to call in case or change error.
   *
   * @return A `U[Cell[P]]` with the changed contents and a `U[String]` with errors.
   */
  def change[
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    positions: U[Position[slice.S]],
    schema: Content.Parser,
    writer: Persist.TextWriter[Cell[P]] = Cell.toString()
  )(implicit
    ev1: ClassTag[Position[slice.S]],
    ev2: Diff.Aux[P, _1, L],
    ev3: Matrix.ChangeTuners[U, T]
  ): (U[Cell[P]], U[String])

  /**
   * Compacts a matrix to a `Map`.
   *
   * @return A `E[Map[Position[P], Content]]` containing the Map representation of this matrix.
   *
   * @note Avoid using this for very large matrices.
   */
  def compact()(implicit ev: ClassTag[Position[P]]): E[Map[Position[P], Content]]

  /**
   * Compact a matrix to a `Map`.
   *
   * @param slice Encapsulates the dimension(s) along which to convert.
   * @param tuner The tuner for the job.
   *
   * @return A `E[Map[Position[slice.S], Compactable[L, P]#C[slice.R]]]` containing the Map
   *         representation of this matrix.
   *
   * @note Avoid using this for very large matrices.
   */
  def compact[
    T <: Tuner,
    V[_ <: Nat]
  ](
    slice: Slice[L, P],
    tuner: T
  )(implicit
    ev1: slice.S =:!= _0,
    ev2: ClassTag[Position[slice.S]],
    ev3: Compactable[L, P, V],
    ev4: Diff.Aux[P, _1, L],
    ev5: Matrix.CompactTuners[U, T]
  ): E[Map[Position[slice.S], V[slice.R]]]

  /**
   * Return all possible positions of a matrix.
   *
   * @param tuner The tuner for the job.
   */
  def domain[T <: Tuner](tuner: T)(implicit ev: Matrix.DomainTuners[U, T]): U[Position[P]]

  /**
   * Fill a matrix with `values` for a given `slice`.
   *
   * @param slice  Encapsulates the dimension(s) on which to fill.
   * @param tuner  The tuner for the job.
   * @param values The content to fill a matrix with.
   *
   * @return A `U[Cell[P]]` where all missing values have been filled in.
   *
   * @note This joins `values` onto this matrix, as such it can be used for imputing missing values. As
   *       the join is an inner join, any positions in the matrix that aren't in `values` are filtered
   *       from the resulting matrix.
   */
  def fillHeterogeneous[
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    values: U[Cell[slice.S]]
  )(implicit
    ev1: ClassTag[Position[P]],
    ev2: ClassTag[Position[slice.S]],
    ev3: Diff.Aux[P, _1, L],
    ev4: Matrix.FillHeterogeneousTuners[U, T]
  ): U[Cell[P]]

  /**
   * Fill a matrix with `value`.
   *
   * @param value The content to fill a matrix with.
   * @param tuner The tuner for the job.
   *
   * @return A `U[Cell[P]]` where all missing values have been filled in.
   */
  def fillHomogeneous[
    T <: Tuner
  ](
    value: Content,
    tuner: T
  )(implicit
    ev1: ClassTag[Position[P]],
    ev2: Matrix.FillHomogeneousTuners[U, T]
  ): U[Cell[P]]

  /**
   * Return contents of a matrix at `positions`.
   *
   * @param positions The positions for which to get the contents.
   * @param tuner     The tuner for the job.
   *
   * @return A `U[Cell[P]]` of the `positions` together with their content.
   */
  def get[
    T <: Tuner
  ](
    positions: U[Position[P]],
    tuner: T
  )(implicit
    ev1: ClassTag[Position[P]],
    ev2: Matrix.GetTuners[U, T]
  ): U[Cell[P]]

  /**
   * Join two matrices.
   *
   * @param slice Encapsulates the dimension(s) along which to join.
   * @param tuner The tuner for the job.
   * @param that  The matrix to join with.
   *
   * @return A `U[Cell[P]]` consisting of the inner-join of the two matrices.
   */
  // TODO: Add inner/left/right/outer join functionality?
  def join[
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    that: U[Cell[P]]
  )(implicit
    ev1: P =:!= _1,
    ev2: ClassTag[Position[slice.S]],
    ev3: Diff.Aux[P, _1, L],
    ev4: Matrix.JoinTuners[U, T]
  ): U[Cell[P]]

  /**
   * Returns the matrix as in in-memory list of cells.
   *
   * @param tuner The tuner for the job.
   *
   * @return A `List[Cell[P]]` of the cells.
   *
   * @note Avoid using this for very large matrices.
   */
  def materialise[T <: Tuner](tuner: T)(implicit ev: Matrix.MaterialiseTuners[U, T]): List[Cell[P]]

  /**
   * Returns the distinct position(s) (or names) for a given `slice`.
   *
   * @param slice Encapsulates the dimension(s) for which the names are to be returned.
   * @param tuner The tuner for the job.
   *
   * @return A `U[Position[slice.S]]` of the distinct position(s).
   */
  def names[
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(implicit
    ev1: slice.S =:!= _0,
    ev2: ClassTag[Position[slice.S]],
    ev3: Diff.Aux[P, _1, L],
    ev4: Positions.NamesTuners[U, T]
  ): U[Position[slice.S]]

  /**
   * Compute pairwise values between all pairs of values given a slice.
   *
   * @param slice     Encapsulates the dimension(s) along which to compute values.
   * @param tuner     The tuner for the job.
   * @param comparer  Defines which element the pairwise operations should apply to.
   * @param operators The pairwise operator(s) to apply.
   *
   * @return A `U[Cell[Q]]` where the content contains the pairwise values.
   */
  def pairwise[
    Q <: Nat,
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    comparer: Comparer,
    operators: Operator[P, Q]*
  )(implicit
    ev1: slice.S =:!= _0,
    ev2: GT[Q, slice.R],
    ev3: ClassTag[Position[slice.S]],
    ev4: ClassTag[Position[slice.R]],
    ev5: Diff.Aux[P, _1, L],
    ev6: Matrix.PairwiseTuners[U, T]
  ): U[Cell[Q]]

  /**
   * Compute pairwise values between all pairs of values given a slice with a user supplied value.
   *
   * @param slice     Encapsulates the dimension(s) along which to compute values.
   * @param tuner     The tuner for the job.
   * @param comparer  Defines which element the pairwise operations should apply to.
   * @param value     The user supplied value.
   * @param operators The pairwise operator(s) to apply.
   *
   * @return A `U[Cell[Q]]` where the content contains the pairwise values.
   */
  def pairwiseWithValue[
    Q <: Nat,
    W,
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    comparer: Comparer,
    value: E[W],
    operators: OperatorWithValue[P, Q] { type V >: W }*
  )(implicit
    ev1: slice.S =:!= _0,
    ev2: GT[Q, slice.R],
    ev3: ClassTag[Position[slice.S]],
    ev4: ClassTag[Position[slice.R]],
    ev5: Diff.Aux[P, _1, L],
    ev6: Matrix.PairwiseTuners[U, T]
  ): U[Cell[Q]]

  /**
   * Compute pairwise values between all values of this and that given a slice.
   *
   * @param slice     Encapsulates the dimension(s) along which to compute values.
   * @param tuner     The tuner for the job.
   * @param comparer  Defines which element the pairwise operations should apply to.
   * @param that      Other matrix to compute pairwise values with.
   * @param operators The pairwise operator(s) to apply.
   *
   * @return A `U[Cell[Q]]` where the content contains the pairwise values.
   */
  def pairwiseBetween[
    Q <: Nat,
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    comparer: Comparer,
    that: U[Cell[P]],
    operators: Operator[P, Q]*
  )(implicit
    ev1: slice.S =:!= _0,
    ev2: GT[Q, slice.R],
    ev3: ClassTag[Position[slice.S]],
    ev4: ClassTag[Position[slice.R]],
    ev5: Diff.Aux[P, _1, L],
    ev6: Matrix.PairwiseTuners[U, T]
  ): U[Cell[Q]]

  /**
   * Compute pairwise values between all values of this and that given a slice with a user supplied value.
   *
   * @param slice     Encapsulates the dimension(s) along which to compute values.
   * @param tuner     The tuner for the job.
   * @param comparer  Defines which element the pairwise operations should apply to.
   * @param that      Other matrix to compute pairwise values with.
   * @param value     The user supplied value.
   * @param operators The pairwise operator(s) to apply.
   *
   * @return A `U[Cell[Q]]` where the content contains the pairwise values.
   */
  def pairwiseBetweenWithValue[
    Q <: Nat,
    W,
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    comparer: Comparer,
    that: U[Cell[P]],
    value: E[W],
    operators: OperatorWithValue[P, Q] { type V >: W }*
  )(implicit
    ev1: slice.S =:!= _0,
    ev2: GT[Q, slice.R],
    ev3: ClassTag[Position[slice.S]],
    ev4: ClassTag[Position[slice.R]],
    ev5: Diff.Aux[P, _1, L],
    ev6: Matrix.PairwiseTuners[U, T]
  ): U[Cell[Q]]

  /**
   * Relocate the coordinates of the cells.
   *
   * @param locate Function that relocates coordinates.
   *
   * @return A `U[Cell[Q]]` where the cells have been relocated.
   */
  def relocate[Q <: Nat](locate: Locate.FromCell[P, Q])(implicit ev: GTEq[Q, P]): U[Cell[Q]]

  /**
   * Relocate the coordinates of the cells using user a suplied value.
   *
   * @param value  A `E` holding a user supplied value.
   * @param locate Function that relocates coordinates.
   *
   * @return A `U[Cell[Q]]` where the cells have been relocated.
   */
  def relocateWithValue[
    Q <: Nat,
    W
  ](
    value: E[W],
    locate: Locate.FromCellWithValue[P, Q, W]
  )(implicit
    ev: GTEq[Q, P]
  ): U[Cell[Q]]

  /**
   * Persist as a sparse matrix file (index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   * @param tuner      The tuner for the job.
   *
   * @return A `U[Cell[P]]`; that is it returns `data`.
   */
  def saveAsIV[
    T <: Tuner
  ](
    file: String,
    dictionary: String = "%1$s.dict.%2$d",
    separator: String = "|",
    tuner: T
  )(implicit
    ev: Matrix.SaveAsIVTuners[U, T]
  ): U[Cell[P]]

  /**
   * Persist to disk.
   *
   * @param file   Name of the output file.
   * @param writer Writer that converts `Cell[P]` to string.
   * @param tuner  The tuner for the job.
   *
   * @return A `U[Cell[P]]`; that is it returns `data`.
   */
  def saveAsText[
    T <: Tuner
  ](
    file: String,
    writer: Persist.TextWriter[Cell[P]] = Cell.toString(),
    tuner: T
  )(implicit
    ev: Persist.SaveAsTextTuners[U, T]
  ): U[Cell[P]]

  /**
   * Set the `values` in a matrix.
   *
   * @param values The values to set.
   * @param tuner  The tuner for the job.
   *
   * @return A `U[Cell[P]]` with the `values` set.
   */
  def set[
    T <: Tuner
  ](
    values: U[Cell[P]],
    tuner: T
  )(implicit
    ev1: ClassTag[Position[P]],
    ev2: Matrix.SetTuners[U, T]
  ): U[Cell[P]]

  /**
   * Returns the shape of the matrix.
   *
   * @param tuner The tuner for the job.
   *
   * @return A `U[Cell[Position1D]]`. The position consists of a long value of the dimension (`Nat.toInt[dim.N]`). The
   *         content has the actual size in it as a discrete variable.
   */
  def shape[T <: Tuner](tuner: T)(implicit ev: Matrix.ShapeTuners[U, T]): U[Cell[_1]]

  /**
   * Returns the size of the matrix in dimension `dim`.
   *
   * @param dim      The dimension for which to get the size.
   * @param distinct Indicates if each coordinate in dimension `dim` occurs only once. If this is the case, then
   *                 enabling this flag has better run-time performance.
   * @param tuner    The tuner for the job.
   *
   * @return A `U[Cell[_1]]`. The position consists of a long value of the dimension (`Nat.toInt[dim.N]`). The
   *         content has the actual size in it as a discrete variable.
   */
  def size[
    T <: Tuner
  ](
    dim: Nat,
    distinct: Boolean = false,
    tuner: T
  )(implicit
    ev1: LTEq[dim.N, P],
    ev2: ToInt[dim.N],
    ev3: Witness.Aux[dim.N],
    ev4: Matrix.SizeTuners[U, T]
  ): U[Cell[_1]]

  /**
   * Slice a matrix.
   *
   * @param slice     Encapsulates the dimension(s) to slice.
   * @param tuner     The tuner for the job.
   * @param keep      Indicates if the `positions` should be kept or removed.
   * @param positions The position(s) within the dimension(s) to slice.
   *
   * @return A `U[Cell[P]]` of the remaining content.
   */
  def slice[
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    keep: Boolean,
    positions: U[Position[slice.S]]
  )(implicit
    ev1: ClassTag[Position[slice.S]],
    ev2: Diff.Aux[P, _1, L],
    ev3: Matrix.SliceTuners[U, T]
  ): U[Cell[P]]

  /**
   * Create window based derived data.
   *
   * @param slice     Encapsulates the dimension(s) to slide over.
   * @param tuner     The tuner for the job.
   * @param ascending Indicator if the data should be sorted ascending or descending.
   * @param windows   The window function(s) to apply to the content.
   *
   * @return A `U[Cell[Q]]` with the derived data.
   */
  def slide[
    Q <: Nat,
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    ascending: Boolean,
    windows: Window[P, slice.S, slice.R, Q]*
  )(implicit
    ev1: slice.R =:!= _0,
    ev2: GT[Q, slice.S],
    ev3: ClassTag[Position[slice.S]],
    ev4: ClassTag[Position[slice.R]],
    ev5: Diff.Aux[P, _1, L],
    ev6: Matrix.SlideTuners[U, T]
  ): U[Cell[Q]]

  /**
   * Create window based derived data with a user supplied value.
   *
   * @param slice     Encapsulates the dimension(s) to slide over.
   * @param tuner     The tuner for the job.
   * @param ascending Indicator if the data should be sorted ascending or descending.
   * @param value     A `E` holding a user supplied value.
   * @param windows   The window function(s) to apply to the content.
   *
   * @return A `U[Cell[Q]]` with the derived data.
   */
  def slideWithValue[
    Q <: Nat,
    W,
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    ascendig: Boolean,
    value: E[W],
    windows: WindowWithValue[P, slice.S, slice.R, Q] { type V >: W }*
  )(implicit
    ev1: slice.R =:!= _0,
    ev2: GT[Q, slice.S],
    ev3: ClassTag[Position[slice.S]],
    ev4: ClassTag[Position[slice.R]],
    ev5: Diff.Aux[P, _1, L],
    ev6: Matrix.SlideTuners[U, T]
  ): U[Cell[Q]]

  /**
   * Partition a matrix according to `partitioner`.
   *
   * @param partitioners Assigns each position to zero, one or more partition(s).
   *
   * @return A `U[(I, Cell[P])]` where `I` is the partition for the corresponding tuple.
   */
  def split[I](partitioners: Partitioner[P, I]*): U[(I, Cell[P])]

  /**
   * Partition a matrix according to `partitioner` using a user supplied value.
   *
   * @param value        A `E` holding a user supplied value.
   * @param partitioners Assigns each position to zero, one or more partition(s).
   *
   * @return A `U[(I, Cell[P])]` where `I` is the partition for the corresponding tuple.
   */
  def splitWithValue[I, W](value: E[W], partitioners: PartitionerWithValue[P, I] { type V >: W }*): U[(I, Cell[P])]

  /**
   * Stream this matrix through `command` and apply `script`.
   *
   * @param command   The command to stream (pipe) the data through.
   * @param files     A list of text files that will be available to `command`. Note that all files must be
   *                  located in the same directory as which the job is started.
   * @param writer    Function that converts a cell to a string (prior to streaming it through `command`).
   * @param parser    Function that parses the resulting string back to a cell.
   * @param hash      Function maps each cell's position to an integer. Use this function to tag functions
   *                  that should be sent to the same reducer.
   * @param tuner     The tuner for the job.
   *
   * @return A `U[Cell[Q]]` with the new data as well as a `U[String]` with any parse errors.
   *
   * @note The `command` must be installed on each node of the cluster.
   */
  def stream[
    Q <: Nat,
    T <: Tuner
  ](
    command: String,
    files: List[String],
    writer: Persist.TextWriter[Cell[P]],
    parser: Cell.TextParser[Q],
    hash: (Position[P]) => Int = _ => 0,
    tuner: T
  )(implicit
    ev: Matrix.StreamTuners[U, T]
  ): (U[Cell[Q]], U[String])

  /**
   * Stream this matrix through `command` and apply `script`, after grouping all data by `slice`.
   *
   * @param slice     Encapsulates the dimension(s) along which to stream.
   * @param tuner     The tuner for the job.
   * @param command   The command to stream (pipe) the data through.
   * @param files     A list of text files that will be available to `command`. Note that all files must be
   *                  located in the same directory as which the job is started.
   * @param writer    Function that converts a group of cells to a string (prior to streaming it through `command`).
   * @param parser    Function that parses the resulting string back to a cell.
   *
   * @return A `U[Cell[Q]]` with the new data as well as a `U[String]` with any parse errors.
   *
   * @note The `command` must be installed on each node of the cluster. The implementation assumes that all
   *       values for a given slice fit into memory.
   */
  def streamByPosition[
    Q <: Nat,
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    command: String,
    files: List[String],
    writer: Persist.TextWriterByPosition[Cell[P]],
    parser: Cell.TextParser[Q]
  )(implicit
    ev1: GTEq[Q, slice.S],
    ev2: ClassTag[slice.S],
    ev3: Diff.Aux[P, _1, L],
    ev4: Matrix.StreamTuners[U, T]
  ): (U[Cell[Q]], U[String])

  /**
   * Sample a matrix according to some `sampler`. It keeps only those cells for which `sampler` returns true.
   *
   * @param samplers Sampling function(s).
   *
   * @return A `U[Cell[P]]` with the sampled cells.
   */
  def subset(samplers: Sampler[P]*): U[Cell[P]]

  /**
   * Sample a matrix according to some `sampler` using a user supplied value. It keeps only those cells for which
   * `sampler` returns true.
   *
   * @param value    A `E` holding a user supplied value.
   * @param samplers Sampling function(s).
   *
   * @return A `U[Cell[P]]` with the sampled cells.
   */
  def subsetWithValue[W](value: E[W], samplers: SamplerWithValue[P] { type V >: W }*): U[Cell[P]]

  /**
   * Summarise a matrix and return the aggregates.
   *
   * @param slice       Encapsulates the dimension(s) along which to aggregate.
   * @param tuner       The tuner for the job.
   * @param aggregators The aggregator(s) to apply to the data.
   *
   * @return A `U[Cell[Q]]` with the aggregates.
   */
  def summarise[
    Q <: Nat,
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    aggregators: Aggregator[P, slice.S, Q]*
  )(implicit
    ev1: GTEq[Q, slice.S],
    ev2: ClassTag[Position[slice.S]],
    ev3: Aggregator.Validate[P, slice.S, Q],
    ev4: Diff.Aux[P, _1, L],
    ev5: Matrix.SummariseTuners[U, T]
  ): U[Cell[Q]]

  /**
   * Summarise a matrix, using a user supplied value, and return the aggregates.
   *
   * @param slice       Encapsulates the dimension(s) along which to aggregate.
   * @param tuner       The tuner for the job.
   * @param value       A `E` holding a user supplied value.
   * @param aggregators The aggregator(s) to apply to the data.
   *
   * @return A `U[Cell[Q]]` with the aggregates.
   */
  def summariseWithValue[
    Q <: Nat,
    W,
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    value: E[W],
    aggregators: AggregatorWithValue[P, slice.S, Q] { type V >: W }*
  )(implicit
    ev1: GTEq[Q, slice.S],
    ev2: ClassTag[Position[slice.S]],
    ev3: AggregatorWithValue.Validate[P, slice.S, Q, W],
    ev4: Diff.Aux[P, _1, L],
    ev5: Matrix.SummariseTuners[U, T]
  ): U[Cell[Q]]

  /**
   * Convert all cells to key value tuples.
   *
   * @param writer The writer to convert a cell to key value tuple.
   *
   * @return A `U[(K, V)]` with all cells as key value tuples.
   */
  def toSequence[K <: Writable, V <: Writable](writer: Persist.SequenceWriter[Cell[P], K, V]): U[(K, V)]

  /**
   * Convert all cells to strings.
   *
   * @param writer The writer to convert a cell to string.
   *
   * @return A `U[String]` with all cells as string.
   */
  def toText(writer: Persist.TextWriter[Cell[P]]): U[String]

  /**
   * Merge all dimensions into a single.
   *
   * @param melt A function that melts the coordinates to a single valueable.
   *
   * @return A `U[CellPosition[_1]]]` where all coordinates have been merged into a single position.
   */
  def toVector(melt: (List[Value]) => Value): U[Cell[_1]]

  /**
   * Transform the content of a matrix.
   *
   * @param transformers The transformer(s) to apply to the content.
   *
   * @return A `U[Cell[Q]]` with the transformed cells.
   */
  def transform[Q <: Nat](transformers: Transformer[P, Q]*)(implicit ev: GTEq[Q, P]): U[Cell[Q]]

  /**
   * Transform the content of a matrix using a user supplied value.
   *
   * @param value        A `E` holding a user supplied value.
   * @param transformers The transformer(s) to apply to the content.
   *
   * @return A `U[Cell[Q]]` with the transformed cells.
   */
  def transformWithValue[
    Q <: Nat,
    W
  ](
    value: E[W],
    transformers: TransformerWithValue[P, Q] { type V >: W }*
  )(implicit
    ev: GTEq[Q, P]
  ): U[Cell[Q]]

  /**
   * Returns the variable type of the content(s) for a given `slice`.
   *
   * @param slice    Encapsulates the dimension(s) for this the types are to be returned.
   * @param tuner    The tuner for the job.
   * @param specific Indicates if the most specific type should be returned, or it's generalisation (default).
   *
   * @return A `U[Cell[slice.S]]` of the distinct position(s) together with their type.
   */
  def types[
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    specific: Boolean
  )(implicit
    ev1: slice.S =:!= _0,
    ev2: ClassTag[Position[slice.S]],
    ev3: Diff.Aux[P, _1, L],
    ev4: Matrix.TypesTuners[U, T]
  ): U[Cell[slice.S]]

  /**
   * Return the unique (distinct) contents of an entire matrix.
   *
   * @param tuner The tuner for the job.
   *
   * @note Comparison is performed based on the string representation of the `Content`.
   */
  def unique[T <: Tuner](tuner: T)(implicit ev: Matrix.UniqueTuners[U, T]): U[Content]

  /**
   * Return the unique (distinct) contents along a dimension.
   *
   * @param slice Encapsulates the dimension(s) along which to find unique contents.
   * @param tuner The tuner for the job.
   *
   * @return A `U[(Position[slice.S], Content)]` consisting of the unique values for each selected position.
   *
   * @note Comparison is performed based on the string representation of the `slice.S` and `Content`.
   */
  def uniqueByPosition[
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(implicit
    ev1: slice.S =:!= _0,
    ev2: Diff.Aux[P, _1, L],
    ev3: Matrix.UniqueTuners[U, T]
  ): U[(Position[slice.S], Content)]

  /**
   * Query the contents of a matrix and return the positions of those that match the predicate.
   *
   * @param predicate The predicate used to filter the contents.
   *
   * @return A `U[Position[P]]` of the positions for which the content matches `predicate`.
   */
  def which(predicate: Cell.Predicate[P])(implicit ev: ClassTag[Position[P]]): U[Position[P]]

  /**
   * Query the contents of one of more positions of a matrix and return the positions of those that match the
   * corresponding predicates.
   *
   * @param slice      Encapsulates the dimension(s) to query.
   * @param tuner      The tuner for the job.
   * @param predicates The position(s) within the dimension(s) to query together with the predicates used to
   *                   filter the contents.
   *
   * @return A `U[Position[P]]` of the positions for which the content matches predicates.
   */
  def whichByPosition[
    T <: Tuner
  ](
    slice: Slice[L, P],
    tuner: T
  )(
    predicates: List[(U[Position[slice.S]], Cell.Predicate[P])]
  )(implicit
    ev1: ClassTag[Position[slice.S]],
    ev2: ClassTag[Position[P]],
    ev3: Diff.Aux[P, _1, L],
    ev4: Matrix.WhichTuners[U, T]
  ): U[Position[P]]

  // TODO: Add more compile-time type checking
  // TODO: Add label join operations
  // TODO: Add read/write[CSV|Hive|HBase|VW|LibSVM] operations
  // TODO: Add machine learning operations (SVD/finding cliques/etc.) - use Spark instead?
}

/** Companion object to `Matrix` with types, implicits, etc. */
object Matrix {
  /** Trait for tuners permitted on a call to `change`. */
  trait ChangeTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `compact` functions. */
  trait CompactTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `domain`. */
  trait DomainTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `fill` with hetrogeneous data. */
  trait FillHeterogeneousTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `fill` with homogeneous data. */
  trait FillHomogeneousTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `get`. */
  trait GetTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `join`. */
  trait JoinTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `materialise`. */
  trait MaterialiseTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `pairwise` functions. */
  trait PairwiseTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `reshape` functions. */
  trait ReshapeTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `saveAsCSV`. */
  trait SaveAsCSVTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `saveAsIV`. */
  trait SaveAsIVTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `saveAsVW*`. */
  trait SaveAsVWTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `shape`. */
  trait ShapeTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `set` functions. */
  trait SetTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `shape`. */
  trait SizeTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `slice`. */
  trait SliceTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `slide` functions. */
  trait SlideTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `squash` functions. */
  trait SquashTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `stream` functions. */
  trait StreamTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `summarise` functions. */
  trait SummariseTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `types`. */
  trait TypesTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `unique` functions. */
  trait UniqueTuners[U[_], T <: Tuner]

  /** Trait for tuners permitted on a call to `which` functions. */
  trait WhichTuners[U[_], T <: Tuner]
}

/** Trait for methods that reduce the number of dimensions or that can be filled. */
trait ReducibleMatrix[L <: Nat, P <: Nat, U[_], E[_], C <: Context[U, E]] { self: Matrix[L, P, U, E, C] =>
  /**
   * Melt one dimension of a matrix into another.
   *
   * @param dim   The dimension to melt
   * @param into  The dimension to melt into
   * @param merge The function for merging two coordinates.
   *
   * @return A `U[Cell[L]]` with one fewer dimension.
   *
   * @note A melt coordinate is always a string value constructed from the string representation of the `dim` and
   *       `into` coordinates.
   */
  def melt[
    D <: Nat : ToInt,
    I <: Nat : ToInt
  ](
    dim: D,
    into: I,
    merge: (Value, Value) => Value
  )(implicit
    ev1: LTEq[D, P],
    ev2: LTEq[I, P],
    ev3: D =:!= I,
    ev4: Diff.Aux[P, _1, L]
  ): U[Cell[L]]

  /**
   * Squash a dimension of a matrix.
   *
   * @param dim      The dimension to squash.
   * @param squasher The squasher that reduces two cells.
   * @param tuner    The tuner for the job.
   *
   * @return A `U[Cell[L]]` with the dimension `dim` removed.
   */
  def squash[
    D <: Nat : ToInt,
    T <: Tuner
  ](
    dim: D,
    squasher: Squasher[P],
    tuner: T
  )(implicit
    ev1: LTEq[D, P],
    ev2: ClassTag[Position[L]],
    ev3: Diff.Aux[P, _1, L],
    ev4: Matrix.SquashTuners[U, T]
  ): U[Cell[L]]

  /**
   * Squash a dimension of a matrix with a user supplied value.
   *
   * @param dim      The dimension to squash.
   * @param value    The user supplied value.
   * @param squasher The squasher that reduces two cells.
   * @param tuner    The tuner for the job.
   *
   * @return A `U[Cell[L]]` with the dimension `dim` removed.
   */
  def squashWithValue[
    D <: Nat : ToInt,
    W,
    T <: Tuner
  ](
    dim: D,
    value: E[W],
    squasher: SquasherWithValue[P] { type V >: W },
    tuner: T
  )(implicit
    ev1: LTEq[D, P],
    ev2: ClassTag[Position[L]],
    ev3: Diff.Aux[P, _1, L],
    ev4: Matrix.SquashTuners[U, T]
  ): U[Cell[L]]
}

/** Trait for methods that reshapes the number of dimension of a matrix. */
trait ReshapeableMatrix[L <: Nat, P <: Nat, U[_], E[_], C <: Context[U, E]] { self: Matrix[L, P, U, E, C] =>
  /**
   * Reshape a coordinate into it's own dimension.
   *
   * @param dim        The dimension to reshape.
   * @param coordinate The coordinate (in `dim`) to reshape into its own dimension.
   * @param locate     A locator that defines the coordinate for the new dimension.
   * @param tuner      The tuner for the job.
   *
   * @return A `U[Cell[Q]]` with reshaped dimensions.
   */
  def reshape[
    D <: Nat : ToInt,
    Q <: Nat,
    T <: Tuner
  ](
    dim: D,
    coordinate: Value,
    locate: Locate.FromCellAndOptionalValue[P, Q],
    tuner: T
  )(implicit
    ev1: LTEq[D, P],
    ev2: GT[Q, P],
    ev3: ClassTag[Position[L]],
    ev4: Diff.Aux[P, _1, L],
    ev5: Matrix.ReshapeTuners[U, T]
  ): U[Cell[Q]]
}

/** Trait for 1D specific operations. */
trait Matrix1D[U[_], E[_], C <: Context[U, E]] extends Matrix[_0, _1, U, E, C]
  with ApproximateDistribution[_0, _1, U, E, C]
  with Statistics[_0, _1, U, E, C] {
}

/** Trait for 2D specific operations. */
trait Matrix2D[U[_], E[_], C <: Context[U, E]] extends Matrix[_1, _2, U, E, C]
  with ReducibleMatrix[_1, _2, U, E, C]
  with ReshapeableMatrix[_1, _2, U, E, C]
  with ApproximateDistribution[_1, _2, U, E, C]
  with PairwiseDistance[_1, _2, U, E, C]
  with Statistics[_1, _2, U, E, C] {
  /**
   * Permute the order of the coordinates in a position.
   *
   * @param dim1 Dimension to use for the first coordinate.
   * @param dim2 Dimension to use for the second coordinate.
   */
  def permute[
    D1 <: Nat : ToInt,
    D2 <: Nat : ToInt
  ](
    dim1: D1,
    dim2: D2
  )(implicit
    ev1: LTEq[D1, _2],
    ev2: LTEq[D2, _2],
    ev3: D1 =:!= D2
  ): U[Cell[_2]]

  /**
   * Persist as a CSV file.
   *
   * @param slice       Encapsulates the dimension that makes up the columns.
   * @param tuner       The tuner for the job.
   * @param file        File to write to.
   * @param separator   Column separator to use.
   * @param escapee     The method for escaping the separator character.
   * @param writeHeader Indicator of the header should be written to a separate file.
   * @param header      Postfix for the header file name.
   * @param writeRowId  Indicator if row names should be written.
   * @param rowId       Column name of row names.
   *
   * @return A `U[Cell[_2]]`; that is it returns `data`.
   */
  def saveAsCSV[
    T <: Tuner
  ](
    slice: Slice[_1, _2],
    tuner: T
  )(
    file: String,
    separator: String = "|",
    escapee: Escape = Quote("|"),
    writeHeader: Boolean = true,
    header: String = "%s.header",
    writeRowId: Boolean = true,
    rowId: String = "id"
  )(implicit
    ev1: ClassTag[Position[slice.S]],
    ev2: Matrix.SaveAsCSVTuners[U, T]
  ): U[Cell[_2]]

  /**
   * Persist a `Matrix2D` as a Vowpal Wabbit file.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param tuner      The tuner for the job.
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name, use `%``s` for the file name.
   * @param tag        Indicator if the selected position should be added as a tag.
   * @param separator  Separator to use in dictionary.
   *
   * @return A `U[Cell[_2]]`; that is it returns `data`.
   */
  def saveAsVW[
    T <: Tuner
  ](
    slice: Slice[_1, _2],
    tuner: T
  )(
    file: String,
    dictionary: String = "%s.dict",
    tag: Boolean = false,
    separator: String = "|"
  )(implicit
    ev1: ClassTag[Position[slice.S]],
    ev2: Matrix.SaveAsVWTuners[U, T]
  ): U[Cell[_2]]

  /**
   * Persist a `Matrix2D` as a Vowpal Wabbit file with the provided labels.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param tuner      The tuner for the job.
   * @param file       File to write to.
   * @param labels     The labels.
   * @param dictionary Pattern for the dictionary file name, use `%``s` for the file name.
   * @param tag        Indicator if the selected position should be added as a tag.
   * @param separator  Separator to use in dictionary.
   *
   * @return A `U[Cell[_2]]`; that is it returns `data`.
   *
   * @note The labels are joined to the data keeping only those examples for which data and a label are available.
   */
  def saveAsVWWithLabels[
    T <: Tuner
  ](
    slice: Slice[_1, _2],
    tuner: T
  )(
    file: String,
    labels: U[Cell[slice.S]],
    dictionary: String = "%s.dict",
    tag: Boolean = false,
    separator: String = "|"
  )(implicit
    ev1: ClassTag[Position[slice.S]],
    ev2: Matrix.SaveAsVWTuners[U, T]
  ): U[Cell[_2]]

  /**
   * Persist a `Matrix2D` as a Vowpal Wabbit file with the provided importance weights.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param tuner      The tuner for the job.
   * @param file       File to write to.
   * @param importance The importance weights.
   * @param dictionary Pattern for the dictionary file name, use `%``s` for the file name.
   * @param tag        Indicator if the selected position should be added as a tag.
   * @param separator  Separator to use in dictionary.
   *
   * @return A `U[Cell[_2]]`; that is it returns `data`.
   *
   * @note The weights are joined to the data keeping only those examples for which data and a weight are available.
   */
  def saveAsVWWithImportance[
    T <: Tuner
  ](
    slice: Slice[_1, _2],
    tuner: T
  )(
    file: String,
    importance: U[Cell[slice.S]],
    dictionary: String = "%s.dict",
    tag: Boolean = false,
    separator: String = "|"
  )(implicit
    ev1: ClassTag[Position[slice.S]],
    ev2: Matrix.SaveAsVWTuners[U, T]
  ): U[Cell[_2]]

  /**
   * Persist a `Matrix2D` as a Vowpal Wabbit file with the provided labels and importance weights.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param tuner      The tuner for the job.
   * @param file       File to write to.
   * @param labels     The labels.
   * @param importance The importance weights.
   * @param dictionary Pattern for the dictionary file name, use `%``s` for the file name.
   * @param tag        Indicator if the selected position should be added as a tag.
   * @param separator  Separator to use in dictionary.
   *
   * @return A `U[Cell[_2]]`; that is it returns `data`.
   *
   * @note The labels and weights are joined to the data keeping only those examples for which data and a label
   *       and weight are available.
   */
  def saveAsVWWithLabelsAndImportance[
    T <: Tuner
  ](
    slice: Slice[_1, _2],
    tuner: T
  )(
    file: String,
    labels: U[Cell[slice.S]],
    importance: U[Cell[slice.S]],
    dictionary: String = "%s.dict",
    tag: Boolean = false,
    separator: String = "|"
  )(implicit
    ev1: ClassTag[Position[slice.S]],
    ev2: Matrix.SaveAsVWTuners[U, T]
  ): U[Cell[_2]]
}

/** Trait for 3D specific operations. */
trait Matrix3D[U[_], E[_], C <: Context[U, E]] extends Matrix[_2, _3, U, E, C]
  with ReducibleMatrix[_2, _3, U, E, C]
  with ReshapeableMatrix[_2, _3, U, E, C]
  with ApproximateDistribution[_2, _3, U, E, C]
  with PairwiseDistance[_2, _3, U, E, C]
  with Statistics[_2, _3, U, E, C] {
  /**
   * Permute the order of the coordinates in a position.
   *
   * @param dim1 Dimension to use for the first coordinate.
   * @param dim2 Dimension to use for the second coordinate.
   * @param dim3 Dimension to use for the third coordinate.
   */
  def permute[
    D1 <: Nat : ToInt,
    D2 <: Nat : ToInt,
    D3 <: Nat : ToInt
  ](
    dim1: D1,
    dim2: D2,
    dim3: D3
  )(implicit
    ev1: LTEq[D1, _3],
    ev2: LTEq[D2, _3],
    ev3: LTEq[D3, _3],
    ev4: Distinct[(D1, D2, D3)]
  ): U[Cell[_3]]
}

/** Trait for 4D specific operations. */
trait Matrix4D[U[_], E[_], C <: Context[U, E]] extends Matrix[_3, _4, U, E, C]
  with ReducibleMatrix[_3, _4, U, E, C]
  with ReshapeableMatrix[_3, _4, U, E, C]
  with ApproximateDistribution[_3, _4, U, E, C]
  with PairwiseDistance[_3, _4, U, E, C]
  with Statistics[_3, _4, U, E, C] {
  /**
   * Permute the order of the coordinates in a position.
   *
   * @param dim1 Dimension to use for the first coordinate.
   * @param dim2 Dimension to use for the second coordinate.
   * @param dim3 Dimension to use for the third coordinate.
   * @param dim4 Dimension to use for the fourth coordinate.
   */
  def permute[
    D1 <: Nat : ToInt,
    D2 <: Nat : ToInt,
    D3 <: Nat : ToInt,
    D4 <: Nat : ToInt
  ](
    dim1: D1,
    dim2: D2,
    dim3: D3,
    dim4: D4
  )(implicit
    ev1: LTEq[D1, _4],
    ev2: LTEq[D2, _4],
    ev3: LTEq[D3, _4],
    ev4: LTEq[D4, _4],
    ev5: Distinct[(D1, D2, D3, D4)]
  ): U[Cell[_4]]
}

/** Trait for 5D specific operations. */
trait Matrix5D[U[_], E[_], C <: Context[U, E]] extends Matrix[_4, _5, U, E, C]
  with ReducibleMatrix[_4, _5, U, E, C]
  with ReshapeableMatrix[_4, _5, U, E, C]
  with ApproximateDistribution[_4, _5, U, E, C]
  with PairwiseDistance[_4, _5, U, E, C]
  with Statistics[_4, _5, U, E, C] {
  /**
   * Permute the order of the coordinates in a position.
   *
   * @param dim1 Dimension to use for the first coordinate.
   * @param dim2 Dimension to use for the second coordinate.
   * @param dim3 Dimension to use for the third coordinate.
   * @param dim4 Dimension to use for the fourth coordinate.
   * @param dim5 Dimension to use for the fifth coordinate.
   */
  def permute[
    D1 <: Nat : ToInt,
    D2 <: Nat : ToInt,
    D3 <: Nat : ToInt,
    D4 <: Nat : ToInt,
    D5 <: Nat : ToInt
  ](
    dim1: D1,
    dim2: D2,
    dim3: D3,
    dim4: D4,
    dim5: D5
  )(implicit
    ev1: LTEq[D1, _5],
    ev2: LTEq[D2, _5],
    ev3: LTEq[D3, _5],
    ev4: LTEq[D4, _5],
    ev5: LTEq[D5, _5],
    ev6: Distinct[(D1, D2, D3, D4, D5)]
  ): U[Cell[_5]]
}

/** Trait for 6D specific operations. */
trait Matrix6D[U[_], E[_], C <: Context[U, E]] extends Matrix[_5, _6, U, E, C]
  with ReducibleMatrix[_5, _6, U, E, C]
  with ReshapeableMatrix[_5, _6, U, E, C]
  with ApproximateDistribution[_5, _6, U, E, C]
  with PairwiseDistance[_5, _6, U, E, C]
  with Statistics[_5, _6, U, E, C] {
  /**
   * Permute the order of the coordinates in a position.
   *
   * @param dim1 Dimension to use for the first coordinate.
   * @param dim2 Dimension to use for the second coordinate.
   * @param dim3 Dimension to use for the third coordinate.
   * @param dim4 Dimension to use for the fourth coordinate.
   * @param dim5 Dimension to use for the fifth coordinate.
   * @param dim6 Dimension to use for the sixth coordinate.
   */
  def permute[
    D1 <: Nat : ToInt,
    D2 <: Nat : ToInt,
    D3 <: Nat : ToInt,
    D4 <: Nat : ToInt,
    D5 <: Nat : ToInt,
    D6 <: Nat : ToInt
  ](
    dim1: D1,
    dim2: D2,
    dim3: D3,
    dim4: D4,
    dim5: D5,
    dim6: D6
  )(implicit
    ev1: LTEq[D1, _6],
    ev2: LTEq[D2, _6],
    ev3: LTEq[D3, _6],
    ev4: LTEq[D4, _6],
    ev5: LTEq[D5, _6],
    ev6: LTEq[D6, _6],
    ev7: Distinct[(D1, D2, D3, D4, D5, D6)]
  ): U[Cell[_6]]
}

/** Trait for 7D specific operations. */
trait Matrix7D[U[_], E[_], C <: Context[U, E]] extends Matrix[_6, _7, U, E, C]
  with ReducibleMatrix[_6, _7, U, E, C]
  with ReshapeableMatrix[_6, _7, U, E, C]
  with ApproximateDistribution[_6, _7, U, E, C]
  with PairwiseDistance[_6, _7, U, E, C]
  with Statistics[_6, _7, U, E, C] {
  /**
   * Permute the order of the coordinates in a position.
   *
   * @param dim1 Dimension to use for the first coordinate.
   * @param dim2 Dimension to use for the second coordinate.
   * @param dim3 Dimension to use for the third coordinate.
   * @param dim4 Dimension to use for the fourth coordinate.
   * @param dim5 Dimension to use for the fifth coordinate.
   * @param dim6 Dimension to use for the sixth coordinate.
   * @param dim7 Dimension to use for the seventh coordinate.
   */
  def permute[
    D1 <: Nat : ToInt,
    D2 <: Nat : ToInt,
    D3 <: Nat : ToInt,
    D4 <: Nat : ToInt,
    D5 <: Nat : ToInt,
    D6 <: Nat : ToInt,
    D7 <: Nat : ToInt
  ](
    dim1: D1,
    dim2: D2,
    dim3: D3,
    dim4: D4,
    dim5: D5,
    dim6: D6,
    dim7: D7
  )(implicit
    ev1: LTEq[D1, _7],
    ev2: LTEq[D2, _7],
    ev3: LTEq[D3, _7],
    ev4: LTEq[D4, _7],
    ev5: LTEq[D5, _7],
    ev6: LTEq[D6, _7],
    ev7: LTEq[D7, _7],
    ev8: Distinct[(D1, D2, D3, D4, D5, D6, D7)]
  ): U[Cell[_7]]
}

/** Trait for 8D specific operations. */
trait  Matrix8D[U[_], E[_], C <: Context[U, E]] extends Matrix[_7, _8, U, E, C]
  with ReducibleMatrix[_7, _8, U, E, C]
  with ReshapeableMatrix[_7, _8, U, E, C]
  with ApproximateDistribution[_7, _8, U, E, C]
  with PairwiseDistance[_7, _8, U, E, C]
  with Statistics[_7, _8, U, E, C] {
  /**
   * Permute the order of the coordinates in a position.
   *
   * @param dim1 Dimension to use for the first coordinate.
   * @param dim2 Dimension to use for the second coordinate.
   * @param dim3 Dimension to use for the third coordinate.
   * @param dim4 Dimension to use for the fourth coordinate.
   * @param dim5 Dimension to use for the fifth coordinate.
   * @param dim6 Dimension to use for the sixth coordinate.
   * @param dim7 Dimension to use for the seventh coordinate.
   * @param dim8 Dimension to use for the eighth coordinate.
   */
  def permute[
    D1 <: Nat : ToInt,
    D2 <: Nat : ToInt,
    D3 <: Nat : ToInt,
    D4 <: Nat : ToInt,
    D5 <: Nat : ToInt,
    D6 <: Nat : ToInt,
    D7 <: Nat : ToInt,
    D8 <: Nat : ToInt
  ](
    dim1: D1,
    dim2: D2,
    dim3: D3,
    dim4: D4,
    dim5: D5,
    dim6: D6,
    dim7: D7,
    dim8: D8
  )(implicit
    ev1: LTEq[D1, _8],
    ev2: LTEq[D2, _8],
    ev3: LTEq[D3, _8],
    ev4: LTEq[D4, _8],
    ev5: LTEq[D5, _8],
    ev6: LTEq[D6, _8],
    ev7: LTEq[D7, _8],
    ev8: LTEq[D8, _8],
    ev9: Distinct[(D1, D2, D3, D4, D5, D6, D7, D8)]
  ): U[Cell[_8]]
}

/** Trait for 9D specific operations. */
trait Matrix9D[U[_], E[_], C <: Context[U, E]] extends Matrix[_8, _9, U, E, C]
  with ReducibleMatrix[_8, _9, U, E, C]
  with ApproximateDistribution[_8, _9, U, E, C]
  with PairwiseDistance[_8, _9, U, E, C]
  with Statistics[_8, _9, U, E, C] {
  /**
   * Permute the order of the coordinates in a position.
   *
   * @param dim1 Dimension to use for the first coordinate.
   * @param dim2 Dimension to use for the second coordinate.
   * @param dim3 Dimension to use for the third coordinate.
   * @param dim4 Dimension to use for the fourth coordinate.
   * @param dim5 Dimension to use for the fifth coordinate.
   * @param dim6 Dimension to use for the sixth coordinate.
   * @param dim7 Dimension to use for the seventh coordinate.
   * @param dim8 Dimension to use for the eighth coordinate.
   * @param dim9 Dimension to use for the ninth coordinate.
   */
  def permute[
    D1 <: Nat : ToInt,
    D2 <: Nat : ToInt,
    D3 <: Nat : ToInt,
    D4 <: Nat : ToInt,
    D5 <: Nat : ToInt,
    D6 <: Nat : ToInt,
    D7 <: Nat : ToInt,
    D8 <: Nat : ToInt,
    D9 <: Nat : ToInt
  ](
    dim1: D1,
    dim2: D2,
    dim3: D3,
    dim4: D4,
    dim5: D5,
    dim6: D6,
    dim7: D7,
    dim8: D8,
    dim9: D9
  )(implicit
    ev1: LTEq[D1, _9],
    ev2: LTEq[D2, _9],
    ev3: LTEq[D3, _9],
    ev4: LTEq[D4, _9],
    ev5: LTEq[D5, _9],
    ev6: LTEq[D6, _9],
    ev7: LTEq[D7, _9],
    ev8: LTEq[D8, _9],
    ev9: LTEq[D9, _9],
    ev10: Distinct[(D1, D2, D3, D4, D5, D6, D7, D8, D9)]
  ): U[Cell[_9]]
}

/**
 * Convenience type for access results from `load` methods that return the data and any parse errors.
 *
 * @param data   The parsed matrix.
 * @param errors Any parse errors.
 */
case class MatrixWithParseErrors[P <: Nat, U[_]](data: U[Cell[P]], errors: U[String])

