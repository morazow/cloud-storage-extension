package com.exasol.cloudetl.source

import com.exasol.cloudetl.data.Row
import com.exasol.cloudetl.storage.FileFormat
import com.exasol.cloudetl.storage.FileFormat._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path

/**
 * An abstract representation of a distributed Hadoop compatible file
 * system source.
 */
abstract class Source {

  /**
   * The file [[org.apache.hadoop.fs.Path]] paths in the file system.
   */
  val path: Path

  /** The Hadoop [[org.apache.hadoop.fs.FileSystem]] file system. */
  def fileSystem: FileSystem

  /**
   * The Hadoop [[org.apache.hadoop.conf.Configuration]] configuration.
   */
  def conf: Configuration

  /**
   * Returns sequence of internal [[com.exasol.cloudetl.data.Row]] row
   * iterators per each paths file.
   */
  def stream(): Iterator[Row]

  /**
   * Finally close the resource used for this source.
   */
  def close(): Unit

}

/**
 * A companion object to the [[Source]] class.
 *
 * Provides a helper methods to create specific implementations of
 * Source class.
 */
object Source {

  def apply(
    fileFormat: FileFormat,
    filePath: Path,
    conf: Configuration,
    fileSystem: FileSystem
  ): Source = fileFormat match {
    case AVRO    => AvroSource(filePath, conf, fileSystem)
    case ORC     => OrcSource(filePath, conf, fileSystem)
    case PARQUET => ParquetSource(filePath, conf, fileSystem)
    case _ =>
      throw new IllegalArgumentException(s"Unsupported storage format: '$fileFormat'")
  }

}
