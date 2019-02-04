package com.exasol.cloudetl.util

import com.exasol.ExaIterator
import com.exasol.cloudetl.data.ExaColumnInfo

import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.OriginalType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.schema.Type
import org.apache.parquet.schema.Type.Repetition
import org.apache.parquet.schema.Types

object SchemaUtil {

  val DECIMAL_MAX_PRECISION: Int = 38
  val DECIMAL_MAX_INT_DIGITS: Int = 9
  val DECIMAL_MAX_LONG_DIGITS: Int = 18

  // Maps the precision value into the number of bytes
  // Adapted from:
  //  - org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe.java
  val PRECISION_TO_BYTE_SIZE: Seq[Int] = {
    for {
      prec <- 1 to 38 // [1 .. 38]
      power = Math.pow(10, prec.toDouble) // scalastyle:ignore magic.number
      size = Math.ceil((Math.log(power - 1) / Math.log(2) + 1) / 8)
    } yield size.toInt
  }

  /**
   * Given the Exasol column information returns Parquet [[org.apache.parquet.schema.MessageType]]
   */
  def createParquetMessageType(columns: Seq[ExaColumnInfo], schemaName: String): MessageType = {
    val types = columns.map(exaColumnToParquetType(_))
    new MessageType(schemaName, types: _*)
  }

  // In below several lines, I try to pattern match on Class[X] of Java types.
  // Please also read:
  // https://stackoverflow.com/questions/7519140/pattern-matching-on-class-type
  object JTypes {
    val jInteger: Class[java.lang.Integer] = classOf[java.lang.Integer]
    val jLong: Class[java.lang.Long] = classOf[java.lang.Long]
    val jBigDecimal: Class[java.math.BigDecimal] = classOf[java.math.BigDecimal]
    val jDouble: Class[java.lang.Double] = classOf[java.lang.Double]
    val jBoolean: Class[java.lang.Boolean] = classOf[java.lang.Boolean]
    val jString: Class[java.lang.String] = classOf[java.lang.String]
    val jSqlDate: Class[java.sql.Date] = classOf[java.sql.Date]
    val jSqlTimestamp: Class[java.sql.Timestamp] = classOf[java.sql.Timestamp]
  }

  /**
   * Given Exasol column [[com.exasol.cloudetl.data.ExaColumnInfo]] information convert it into
   * Parquet [[org.apache.parquet.schema.Type$]]
   */
  def exaColumnToParquetType(colInfo: ExaColumnInfo): Type = {
    val colName = colInfo.name
    val colType = colInfo.`type`
    val repetition = if (colInfo.isNullable) Repetition.OPTIONAL else Repetition.REQUIRED

    // Given a numeric type (int, long, bigDecimal) with precision more than zero, encodes it as
    // Parquet INT32, INT64 or FIXED_LEN_BYTE_ARRAY type.
    //
    // - for 1 <= precision <= 9, use INT32
    // - for 1 <= precision <= 18, use INT64
    // - otherwise, use FIXED_LEN_BYTE_ARRAY
    def makeDecimalType(precision: Int): Type = {
      require(
        precision > 0,
        s"""|The precision should be larger than zero for type '$colType' in order to encode
            |it as numeric (int32, int64, fixed_len_array) type.
        """.stripMargin
      )
      if (precision <= DECIMAL_MAX_INT_DIGITS) {
        Types
          .primitive(PrimitiveTypeName.INT32, repetition)
          .precision(precision)
          .scale(colInfo.scale)
          .as(OriginalType.DECIMAL)
          .named(colName)
      } else if (precision <= DECIMAL_MAX_LONG_DIGITS) {
        Types
          .primitive(PrimitiveTypeName.INT64, repetition)
          .precision(precision)
          .scale(colInfo.scale)
          .as(OriginalType.DECIMAL)
          .named(colName)
      } else {
        Types
          .primitive(PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, repetition)
          .precision(precision)
          .scale(colInfo.scale)
          .length(PRECISION_TO_BYTE_SIZE(precision - 1))
          .as(OriginalType.DECIMAL)
          .named(colName)
      }
    }

    import JTypes._

    colType match {
      case `jInteger` =>
        if (colInfo.precision == 0) {
          Types
            .primitive(PrimitiveTypeName.INT32, repetition)
            .named(colName)
        } else if (colInfo.precision <= DECIMAL_MAX_INT_DIGITS) {
          Types
            .primitive(PrimitiveTypeName.INT32, repetition)
            .precision(colInfo.precision)
            .scale(colInfo.scale)
            .as(OriginalType.DECIMAL)
            .named(colName)
        } else {
          makeDecimalType(colInfo.precision)
        }

      case `jLong` =>
        if (colInfo.precision == 0) {
          Types
            .primitive(PrimitiveTypeName.INT64, repetition)
            .named(colName)
        } else if (colInfo.precision <= DECIMAL_MAX_LONG_DIGITS) {
          Types
            .primitive(PrimitiveTypeName.INT64, repetition)
            .precision(colInfo.precision)
            .scale(colInfo.scale)
            .as(OriginalType.DECIMAL)
            .named(colName)
        } else {
          makeDecimalType(colInfo.precision)
        }

      case `jBigDecimal` =>
        makeDecimalType(colInfo.precision)

      case `jDouble` =>
        Types
          .primitive(PrimitiveTypeName.DOUBLE, repetition)
          .named(colName)

      case `jString` =>
        if (colInfo.length > 0) {
          Types
            .primitive(PrimitiveTypeName.BINARY, repetition)
            .as(OriginalType.UTF8)
            .length(colInfo.length)
            .named(colName)
        } else {
          Types
            .primitive(PrimitiveTypeName.BINARY, repetition)
            .as(OriginalType.UTF8)
            .named(colName)
        }

      case `jBoolean` =>
        Types
          .primitive(PrimitiveTypeName.BOOLEAN, repetition)
          .named(colName)

      case `jSqlDate` =>
        Types
          .primitive(PrimitiveTypeName.INT32, repetition)
          .as(OriginalType.DATE)
          .named(colName)

      case `jSqlTimestamp` =>
        Types
          .primitive(PrimitiveTypeName.INT96, repetition)
          .named(colName)

      case _ =>
        throw new IllegalArgumentException(
          s"Cannot convert Exasol type '$colType' to Parquet type."
        )
    }
  }

  /**
   * Returns a value from Exasol [[ExaIterator]] iterator on given index which have
   * [[com.exasol.cloudetl.data.ExaColumnInfo]] column type
   */
  def exaColumnToValue(iter: ExaIterator, idx: Int, colInfo: ExaColumnInfo): Any = {
    val colType = colInfo.`type`
    import JTypes._

    colType match {
      case `jInteger`      => iter.getInteger(idx)
      case `jLong`         => iter.getLong(idx)
      case `jBigDecimal`   => iter.getBigDecimal(idx)
      case `jDouble`       => iter.getDouble(idx)
      case `jString`       => iter.getString(idx)
      case `jBoolean`      => iter.getBoolean(idx)
      case `jSqlDate`      => iter.getDate(idx)
      case `jSqlTimestamp` => iter.getTimestamp(idx)
      case _ =>
        throw new IllegalArgumentException(s"Cannot get Exasol value for column type '$colType'.")
    }
  }

}
