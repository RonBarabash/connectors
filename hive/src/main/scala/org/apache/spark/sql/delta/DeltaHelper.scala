/*
 * Copyright 2019 Databricks, Inc.
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
 */

package org.apache.spark.sql.delta

import java.net.URI

import scala.collection.mutable
import io.delta.hive.{DeltaStorageHandler, PartitionColumnInfo}
import org.apache.hadoop.fs._
import org.apache.hadoop.hive.metastore.api.MetaException
import org.apache.hadoop.hive.ql.plan.TableScanDesc
import org.apache.hadoop.hive.serde2.typeinfo._
import org.apache.hadoop.mapred.JobConf
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.delta.actions.{AddFile, SingleAction}
import org.apache.spark.sql.types._

object DeltaHelper extends Logging {

  /**
   * List the file paths in the Delta table. The provided `JobConf` may consider pushed partition
   * filters to do the partition pruning.
   *
   * The return value has two parts: all of the files matching the pushed partition filter and the
   * mapping from file path to its partition information.
   */
  def listDeltaFiles(
      nonNormalizedPath: Path,
      job: JobConf): (Array[FileStatus], Map[URI, Array[PartitionColumnInfo]]) = {
    val fs = nonNormalizedPath.getFileSystem(job)
    // We need to normalize the table path so that all paths we return to Hive will be normalized
    // This is necessary because `HiveInputFormat.pushProjectionsAndFilters` will try to figure out
    // which table a split path belongs to by comparing the split path with the normalized (? I have
    // not yet confirmed this) table paths.
    // TODO The assumption about Path in Hive is too strong, we should try to see if we can fail if
    // `pushProjectionsAndFilters` doesn't find a table for a Delta split path.
    val rootPath = fs.makeQualified(nonNormalizedPath)
    val snapshotToUse = loadDeltaLatestSnapshot(rootPath)

    val hiveSchema = TypeInfoUtils.getTypeInfoFromTypeString(
      job.get(DeltaStorageHandler.DELTA_TABLE_SCHEMA)).asInstanceOf[StructTypeInfo]
    DeltaHelper.checkTableSchema(snapshotToUse.metadata.schema, hiveSchema)

    // get the partition prune exprs
    val filterExprSerialized = job.get(TableScanDesc.FILTER_EXPR_CONF_STR)

    val convertedFilterExpr = DeltaPushFilter.partitionFilterConverter(filterExprSerialized)
    if (convertedFilterExpr.forall { filter =>
      DeltaTableUtils.isPredicatePartitionColumnsOnly(
        filter,
        snapshotToUse.metadata.partitionColumns,
        spark)
    }) {
      // All of filters are based on partition columns. The partition columns may be changed because
      // we cannot guarantee that we were using the same Delta Snapshot to generate the filters. But
      // as long as the pushed filters are based on a subset of latest partition columns, it should
      // be *correct*, even if we may not push all of partition filters and the query may be a bit
      // slower.
    } else {
      throw new MetaException(s"The pushed filters $filterExprSerialized are not all based on" +
        "partition columns. This may happen when the partition columns of a Delta table have " +
        "been changed when running this query. Please try to re-run the query to pick up the " +
        "latest partition columns.")
    }

    // The default value 128M is the same as the default value of
    // "spark.sql.files.maxPartitionBytes" in Spark. It's also the default parquet row group size
    // which is usually the best split size for parquet files.
    val blockSize = job.getLong("parquet.block.size", 128L * 1024 * 1024)

    val localFileToPartition = mutable.Map[URI, Array[PartitionColumnInfo]]()

    val partitionColumns = snapshotToUse.metadata.partitionColumns.toSet
    val partitionColumnWithIndex = snapshotToUse.metadata.schema.zipWithIndex
      .filter { case (t, _) =>
        partitionColumns.contains(t.name)
      }.sortBy(_._2).toArray

    // selected files to Hive to be processed
    val files = DeltaLog.filterFileList(
      snapshotToUse.metadata.partitionColumns, snapshotToUse.allFiles.toDF(), convertedFilterExpr)
      .as[AddFile](SingleAction.addFileEncoder)
      .collect().map { f =>
        logInfo(s"selected delta file ${f.path} under $rootPath")
        val status = toFileStatus(fs, rootPath, f, blockSize)
        localFileToPartition +=
          status.getPath.toUri -> partitionColumnWithIndex.map { case (t, index) =>
            // TODO Is `catalogString` always correct? We may need to add our own conversion rather
            // than relying on Spark.
            new PartitionColumnInfo(index, t.dataType.catalogString, f.partitionValues(t.name))
          }
        status
      }
    (files, localFileToPartition.toMap)
  }

  /**
   * Convert an [[AddFile]] to Hadoop's [[FileStatus]].
   *
   * @param root the table path which will be used to create the real path from relative path.
   */
  private def toFileStatus(fs: FileSystem, root: Path, f: AddFile, blockSize: Long): FileStatus = {
    val status = new FileStatus(
      f.size, // length
      false, // isDir
      1, // blockReplication, FileInputFormat doesn't use this
      blockSize, // blockSize
      f.modificationTime, // modificationTime
      absolutePath(fs, root, f.path) // path
    )
    // We don't have `blockLocations` in `AddFile`. However, fetching them by calling
    // `getFileStatus` for each file is unacceptable because that's pretty inefficient and it will
    // make Delta look worse than a parquet table because of these FileSystem RPC calls.
    //
    // But if we don't set the block locations, [[FileInputFormat]] will try to fetch them. Hence,
    // we create a `LocatedFileStatus` with dummy block locations to save FileSystem RPC calls. We
    // lose the locality but this is fine today since most of storage systems are on Cloud and the
    // computation is running separately.
    //
    // An alternative solution is using "listStatus" recursively to get all `FileStatus`s and keep
    // those present in `AddFile`s. This is much cheaper and the performance should be the same as a
    // parquet table. However, it's pretty complicated as we need to be careful to avoid listing
    // unnecessary directories. So we decide to not do this right now.
    val dummyBlockLocations =
      Array(new BlockLocation(Array("localhost:50010"), Array("localhost"), 0, f.size))
    new LocatedFileStatus(status, dummyBlockLocations)
  }

  /**
   * Create an absolute [[Path]] from `child` using the `root` path if `child` is a relative path.
   * Return a [[Path]] version of child` if it is an absolute path.
   *
   * @param child an escaped string read from Delta's [[AddFile]] directly which requires to
   *              unescape before creating the [[Path]] object.
   */
  private def absolutePath(fs: FileSystem, root: Path, child: String): Path = {
    val p = new Path(new URI(child))
    if (p.isAbsolute) {
      fs.makeQualified(p)
    } else {
      new Path(root, p)
    }
  }

  def getPartitionCols(rootPath: Path): Seq[String] = {
    loadDeltaLatestSnapshot(rootPath).metadata.partitionColumns
  }

  /** Load the latest Delta [[Snapshot]] from the path. */
  def loadDeltaLatestSnapshot(rootPath: Path): Snapshot = {
    DeltaLog.forTable(spark, rootPath).update()
  }

  /**
   * Verify the underlying Delta table schema is the same as the Hive schema defined in metastore.
   */
  @throws(classOf[MetaException])
  def checkTableSchema(deltaSchema: StructType, hiveSchema: StructTypeInfo): Unit = {
    // TODO How to check column nullables?
    if (!isSameStructType(deltaSchema, hiveSchema)) {
      throw metaInconsistencyException(deltaSchema, hiveSchema)
    }
  }

  private def isSameStructType(sparkStruct: StructType, hiveStruct: StructTypeInfo): Boolean = {
    if (sparkStruct.size == hiveStruct.getAllStructFieldNames.size) {
      (0 until sparkStruct.size).forall { i =>
        val sparkField = sparkStruct(i)
        val hiveFieldName = hiveStruct.getAllStructFieldNames.get(i)
        val hiveFieldType = hiveStruct.getAllStructFieldTypeInfos.get(i)
        // TODO Do we need to respect case insensitive config?
        sparkField.name == hiveFieldName && isSameType(sparkField.dataType, hiveFieldType)
      }
    } else {
      false
    }
  }

  private def isSameType(sparkType: DataType, hiveType: TypeInfo): Boolean = {
    sparkType match {
      case ByteType => hiveType == TypeInfoFactory.byteTypeInfo
      case BinaryType => hiveType == TypeInfoFactory.binaryTypeInfo
      case BooleanType => hiveType == TypeInfoFactory.booleanTypeInfo
      case IntegerType => hiveType == TypeInfoFactory.intTypeInfo
      case LongType => hiveType == TypeInfoFactory.longTypeInfo
      case StringType => hiveType == TypeInfoFactory.stringTypeInfo
      case FloatType => hiveType == TypeInfoFactory.floatTypeInfo
      case DoubleType => hiveType == TypeInfoFactory.doubleTypeInfo
      case ShortType => hiveType == TypeInfoFactory.shortTypeInfo
      case DateType => hiveType == TypeInfoFactory.dateTypeInfo
      case TimestampType => hiveType == TypeInfoFactory.timestampTypeInfo
      case decimalType: DecimalType =>
        hiveType match {
          case hiveDecimalType: DecimalTypeInfo =>
            decimalType.precision == hiveDecimalType.precision() &&
              decimalType.scale == hiveDecimalType.scale()
          case _ => false
        }
      case arrayType: ArrayType =>
        hiveType match {
          case hiveListType: ListTypeInfo =>
            isSameType(arrayType.elementType, hiveListType.getListElementTypeInfo)
          case _ => false
        }
      case mapType: MapType =>
        hiveType match {
          case hiveMapType: MapTypeInfo =>
            isSameType(mapType.keyType, hiveMapType.getMapKeyTypeInfo) &&
              isSameType(mapType.valueType, hiveMapType.getMapValueTypeInfo)
          case _ => false
        }
      case structType: StructType =>
        hiveType match {
          case hiveStructType: StructTypeInfo => isSameStructType(structType, hiveStructType)
          case _ => false
        }
      case _ =>
        // TODO More Hive types:
        //  - void
        //  - char
        //  - varchar
        //  - intervalYearMonthType
        //  - intervalDayTimeType
        //  - UnionType
        //  - Others?
        throw new UnsupportedOperationException(s"Spark type $sparkType is not supported Hive")
    }
  }

  private def metaInconsistencyException(
      deltaSchema: StructType,
      hiveSchema: StructTypeInfo): MetaException = {
    new MetaException(
      s"""The Delta table schema is not the same as the Hive schema. Please update your Hive
         |table's schema to match the Delta table schema.
         |Delta table schema: $deltaSchema
         |Hive schema: $hiveSchema""".stripMargin)
  }

  // TODO Configure `spark` to pick up the right Hadoop configuration.
  def spark: SparkSession = SparkSession.builder()
    .master("local[*]")
    .appName("HiveOnDelta Get Files")
    .getOrCreate()
}
