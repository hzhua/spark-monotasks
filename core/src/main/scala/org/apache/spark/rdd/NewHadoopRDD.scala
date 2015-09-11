/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright 2014 The Regents of The University California
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.rdd

import java.io.EOFException
import java.text.SimpleDateFormat
import java.util.Date

import scala.collection.mutable.{HashMap, HashSet}
import scala.reflect.{classTag, ClassTag}
import scala.util.control.NonFatal

import org.apache.hadoop.conf.{Configurable, Configuration}
import org.apache.hadoop.io.Writable
import org.apache.hadoop.mapreduce.{InputFormat, InputSplit, JobID}
import org.apache.hadoop.mapreduce.lib.input.FileSplit

import org.apache.spark._
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.input.WholeTextFileInputFormat
import org.apache.spark.executor.DataReadMethod
import org.apache.spark.mapreduce.SparkHadoopMapReduceUtil
import org.apache.spark.monotasks.Monotask
import org.apache.spark.monotasks.disk.{HdfsReadMonotask, MemoryStoreFileSystem, MemoryStorePath}
import org.apache.spark.rdd.NewHadoopRDD.NewHadoopMapPartitionsWithSplitRDD
import org.apache.spark.storage.{BlockException, BlockId, RDDBlockId, StorageLevel}
import org.apache.spark.util.NextIterator

private[spark] class NewHadoopPartition(
    rddId: Int,
    val index: Int,
    @transient rawSplit: InputSplit with Writable)
  extends Partition {

  val serializableHadoopSplit = new SerializableWritable(rawSplit)

  // The BlockId used to cache this partition's serialized bytes in the BlockManager after it is
  // read from disk. This is required because the process of reading a block from disk and the
  // process of deserializing it take place in two different monotasks. This variable is set when
  // this partition's corresponding HdfsReadMonotask is created in NewHadoopRDD.getInputMonotasks().
  var serializedDataBlockId: Option[BlockId] = None

  override def hashCode(): Int = 41 * (41 + rddId) + index
}

/**
 * :: DeveloperApi ::
 * An RDD that provides core functionality for reading data stored in Hadoop (e.g., files in HDFS,
 * sources in HBase, or S3), using the new MapReduce API (`org.apache.hadoop.mapreduce`).
 *
 * Note: Instantiating this class directly is not recommended, please use
 * [[org.apache.spark.SparkContext.newAPIHadoopRDD()]]
 *
 * @param sc The SparkContext to associate the RDD with.
 * @param inputFormatClass Storage format of the data to be read.
 * @param keyClass Class of the key associated with the inputFormatClass.
 * @param valueClass Class of the value associated with the inputFormatClass.
 * @param hadoopConf The Hadoop configuration.
 */
@DeveloperApi
class NewHadoopRDD[K, V](
    sc : SparkContext,
    private val inputFormatClass: Class[_ <: InputFormat[K, V]],
    keyClass: Class[K],
    valueClass: Class[V],
    @transient private val hadoopConf: Configuration)
  extends RDD[(K, V)](sc, Nil) with SparkHadoopMapReduceUtil with Logging {

  // A Hadoop Configuration can be about 10 KB, which is pretty big, so broadcast it.
  private val confBroadcast = sc.broadcast(new SerializableWritable(hadoopConf))

  private val jobTrackerId: String = new SimpleDateFormat("yyyyMMddHHmm").format(new Date())

  @transient protected val jobId = new JobID(jobTrackerId, id)

  override def getInputMonotasks(
      sparkPartition: Partition,
      dependencyIdToPartitions: HashMap[Long, HashSet[Partition]],
      sparkTaskContext: TaskContextImpl,
      nextMonotask: Monotask): Seq[Monotask] = {
    // The DAG will look like this:
    //   HdfsReadMonotask ---> nextMonotask
    val readMonotask =
      new HdfsReadMonotask(sparkTaskContext, id, sparkPartition, confBroadcast.value.value)
    nextMonotask.addDependency(readMonotask)

    sparkPartition.asInstanceOf[NewHadoopPartition].serializedDataBlockId =
      Some(readMonotask.getResultBlockId())
    Seq(readMonotask)
  }

  override def getPartitions: Array[Partition] = {
    val inputFormat = inputFormatClass.newInstance
    inputFormat match {
      case configurable: Configurable => configurable.setConf(hadoopConf)
      case _ =>
    }

    val rawSplits = inputFormat.getSplits(newJobContext(hadoopConf, jobId)).toArray
    (0 until rawSplits.size).map(i =>
      new NewHadoopPartition(id, i, rawSplits(i).asInstanceOf[InputSplit with Writable])).toArray
  }

  override def compute(
      sparkPartition: Partition,
      sparkTaskContext: TaskContext): InterruptibleIterator[(K, V)] = {
    val memoryStoreHadoopPartition = sparkPartition.asInstanceOf[NewHadoopPartition]
    val hadoopSplit = memoryStoreHadoopPartition.serializableHadoopSplit.value match {
        case fileSplit: FileSplit =>
          fileSplit
        case otherSplit =>
          throw new UnsupportedOperationException("Unsupported InputSplit: " +
            s"${otherSplit.getClass().getName()}. NewHadoopRDD only supports InputSplits of " +
            s"type ${classTag[FileSplit]}.")
    }

    val startPosition = hadoopSplit.getStart()
    val path = hadoopSplit.getPath()
    val localHadoopConf = confBroadcast.value.value
    val memoryStoreFileSystem = new MemoryStoreFileSystem(
      SparkEnv.get.blockManager,
      startPosition,
      path.getFileSystem(localHadoopConf),
      localHadoopConf)

    val blockId = new RDDBlockId(id, sparkPartition.index)
    val serializedDataBlockId = memoryStoreHadoopPartition.serializedDataBlockId.getOrElse(
      throw new BlockException(
        blockId, s"Could not find the serialized data blockId for block $blockId."))

    val memoryStorePath =
      new MemoryStorePath(path.toUri(), serializedDataBlockId, memoryStoreFileSystem)
    val memoryStoreFileSplit =
      new FileSplit(memoryStorePath, startPosition, hadoopSplit.getLength(), null)

    val inputFormat = inputFormatClass.newInstance
    inputFormat match {
      case configurable: Configurable => configurable.setConf(localHadoopConf)
      case _ =>
    }

    // Hadoop wants a 32-bit task attempt ID, so if ours is bigger than Int.MaxValue, roll it
    // around by taking a mod. We expect that no task will be attempted 2 billion times.
    val shortenedTaskId = (sparkTaskContext.taskAttemptId % Int.MaxValue).toInt
    val hadoopTaskId =
      newTaskAttemptID(jobTrackerId, id, isMap = false, sparkPartition.index, shortenedTaskId)
    val hadoopTaskContext = newTaskAttemptContext(localHadoopConf, hadoopTaskId)
    val recordReader = inputFormat.createRecordReader(memoryStoreFileSplit, hadoopTaskContext)
    recordReader.initialize(memoryStoreFileSplit, hadoopTaskContext)

    val inputMetrics =
      sparkTaskContext.taskMetrics.getInputMetricsForReadMethod(DataReadMethod.Hadoop)

    new InterruptibleIterator[(K, V)](sparkTaskContext, new NextIterator[(K, V)] {
      // Register an on-task-completion callback to close the RecordReader.
      sparkTaskContext.addTaskCompletionListener(_ => closeIfNeeded())

      override def getNext(): (K, V)  = {
        try {
          finished = !recordReader.nextKeyValue()
        } catch  {
          case _: EOFException => finished = true
        }
        if (!finished) {
          inputMetrics.incRecordsRead(1)
        }

        (recordReader.getCurrentKey(), recordReader.getCurrentValue())
      }

      override def close(): Unit = {
        try {
          recordReader.close()
        } catch {
          case NonFatal(e) => logWarning("Exception in RecordReader.close()", e)
        }
      }
    })
  }

  /** Maps over a partition, providing the InputSplit that was used as the base of the partition. */
  @DeveloperApi
  def mapPartitionsWithInputSplit[U: ClassTag](
      f: (InputSplit, Iterator[(K, V)]) => Iterator[U],
      preservesPartitioning: Boolean = false): RDD[U] =
    new NewHadoopMapPartitionsWithSplitRDD(this, f, preservesPartitioning)

  override def getPreferredLocations(sparkPartition: Partition): Seq[String] = {
    val hadoopSplit = sparkPartition.asInstanceOf[NewHadoopPartition].serializableHadoopSplit.value
    val locs = HadoopRDD.SPLIT_INFO_REFLECTIONS match {
      case Some(c) =>
        try {
          val infos = c.newGetLocationInfo.invoke(hadoopSplit).asInstanceOf[Array[AnyRef]]
          Some(HadoopRDD.convertSplitLocationInfo(infos))
        } catch {
          case e : Exception =>
            logDebug("Failed to use InputSplit.getLocationInfo.", e)
            None
        }
      case None => None
    }
    locs.getOrElse(hadoopSplit.getLocations.filter(_ != "localhost"))
  }

  override def persist(storageLevel: StorageLevel): this.type = {
    if (storageLevel.deserialized) {
      logWarning("Caching NewHadoopRDDs as deserialized objects usually leads to undesired" +
        " behavior because Hadoop's RecordReader reuses the same Writable object for all records." +
        " Use a map transformation to make copies of the records.")
    }
    super.persist(storageLevel)
  }

  def getConf: Configuration = confBroadcast.value.value
}

private[spark] object NewHadoopRDD {
  /**
   * Analogous to [[org.apache.spark.rdd.MapPartitionsRDD]], but passes in an InputSplit to
   * the given function rather than the index of the partition.
   */
  private[spark] class NewHadoopMapPartitionsWithSplitRDD[U: ClassTag, T: ClassTag](
      prev: RDD[T],
      f: (InputSplit, Iterator[T]) => Iterator[U],
      preservesPartitioning: Boolean = false)
    extends RDD[U](prev) {

    override val partitioner = if (preservesPartitioning) firstParent[T].partitioner else None

    override def getPartitions: Array[Partition] = firstParent[T].partitions

    override def compute(sparkPartition: Partition, sparkTaskContext: TaskContext) = {
      val hadoopSplit =
        sparkPartition.asInstanceOf[NewHadoopPartition].serializableHadoopSplit.value
      f(hadoopSplit, firstParent[T].iterator(sparkPartition, sparkTaskContext))
    }
  }
}

private[spark] class WholeTextFileRDD(
    sc : SparkContext,
    inputFormatClass: Class[_ <: WholeTextFileInputFormat],
    keyClass: Class[String],
    valueClass: Class[String],
    @transient hadoopConf: Configuration,
    private val minPartitions: Int)
  extends NewHadoopRDD[String, String](sc, inputFormatClass, keyClass, valueClass, hadoopConf) {

  override def getPartitions: Array[Partition] = {
    val inputFormat = inputFormatClass.newInstance
    inputFormat match {
      case configurable: Configurable => configurable.setConf(hadoopConf)
      case _ =>
    }

    val jobContext = newJobContext(hadoopConf, jobId)
    inputFormat.setMinPartitions(jobContext, minPartitions)

    val rawSplits = inputFormat.getSplits(jobContext).toArray
    (0 until rawSplits.size).map(i =>
      new NewHadoopPartition(id, i, rawSplits(i).asInstanceOf[InputSplit with Writable])).toArray
  }
}
