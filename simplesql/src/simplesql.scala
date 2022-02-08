/* This file is copied from the Simple SQL project,
 * https://github.com/jodersky/simplesql
 *
 * Copyright 2020 Jakob Odersky <jakob@odersky.com>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:

 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * Simple SQL
 * ==========
 *
 * A no-frills SQL library for Scala 3.
 *
 * SimpleSQL is a very thin wrapper around JDBC, which allows you to take full
 * advantage of *full SQL* and *any database* with a JDBC driver.
 */
package simplesql

import java.{sql => jsql}
import scala.deriving
import scala.compiletime
import scala.annotation

@annotation.implicitNotFound("No database connection found. Make sure to call this in a `run()` or `transaction()` block.")
case class Connection(underlying: jsql.Connection) {
  extension (inline sc: StringContext) {
    inline def sql(inline args: Any*): Query = ${Query.sqlImpl('{sc}, '{args})}
  }
}

/** A thin wrapper around an SQL statement */
case class Query(
  sql: String,
  fillStatement: jsql.PreparedStatement => Unit
)

object Query {

  // The caller must close the statement
  def newPreparedStatement(q: Query, c: jsql.Connection): jsql.PreparedStatement = {
    val stat = c.prepareStatement(q.sql, jsql.Statement.RETURN_GENERATED_KEYS)
    q.fillStatement(stat)
    stat
  }

  import scala.quoted.{Expr, Quotes, Varargs}
  def sqlImpl(sc0: Expr[StringContext], args0: Expr[Seq[Any]])(using qctx: Quotes): Expr[Query] = {
    import scala.quoted.quotes.reflect._
    val args: Seq[Expr[_]] = args0 match {
      case Varargs(exprs) => exprs
    }
    val writers: Seq[Expr[SimpleWriter[_]]] = for ('{ $arg: t } <- args) yield {
      val w = TypeRepr.of[SimpleWriter].appliedTo(
        TypeRepr.of[t].widen
      )

      Implicits.search(w) match {
        case iss: ImplicitSearchSuccess =>
          iss.tree.asExprOf[SimpleWriter[_]]
        case isf: ImplicitSearchFailure =>
          report.error(s"could not find implicit for ${w.show}", arg)
          '{???}
      }
    }


    val qstring = sc0.value match {
      case None =>
        report.error("string context must be known at compile time", sc0)
        ""
      case Some(sc) =>
        val strings = sc.parts.iterator
        val buf = new StringBuilder(strings.next())
        while(strings.hasNext) {
          buf.append(" ? ")
          buf.append(strings.next())
        }
        buf.result()
    }

    val r = '{
      Query(
        ${Expr(qstring)},
        (stat: jsql.PreparedStatement) => ${
          val exprs = for (((writer, arg), idx) <- writers.zip(args).zipWithIndex.toList) yield {
            writer match {
              case '{ $writer: SimpleWriter[t] } =>
                '{$writer.write(stat, ${Expr(idx + 1)}, ${arg.asExprOf[t]})}
            }
          }
          Expr.block(exprs, 'stat)
        }
      )
    }
    //System.err.println(r.show)
    r
  }
}


trait SimpleWriter[A] {
  def write(stat: jsql.PreparedStatement, idx: Int, value: A): Unit
}

object SimpleWriter {

  given SimpleWriter[Byte] = (stat, idx, value) => stat.setByte(idx, value)
  given SimpleWriter[Short] = (stat, idx, value) => stat.setShort(idx, value)
  given SimpleWriter[Int] = (stat, idx, value) => stat.setInt(idx, value)
  given SimpleWriter[Long] = (stat, idx, value) => stat.setLong(idx, value)
  given SimpleWriter[Float] = (stat, idx, value) => stat.setFloat(idx, value)
  given SimpleWriter[Double] = (stat, idx, value) => stat.setDouble(idx, value)
  given SimpleWriter[Boolean] = (stat, idx, value) => stat.setBoolean(idx, value)
  given SimpleWriter[String] = (stat, idx, value) => stat.setString(idx, value)
  given SimpleWriter[Array[Byte]] = (stat, idx, value) => stat.setBytes(idx, value)
  given SimpleWriter[BigDecimal] = (stat, idx, value) => stat.setBigDecimal(idx, value.bigDecimal)

  given optWriter[A](using writer: SimpleWriter[A]): SimpleWriter[Option[A]] with {
      def write(stat: jsql.PreparedStatement, idx: Int, value: Option[A]) = value match {
        case Some (v) => writer.write (stat, idx, v)
        case None => stat.setNull(idx, jsql.Types.NULL)
    }
  }
}

trait Reader[A] {
  def arity: Int
  def read(results: jsql.ResultSet, baseIdx: Int): A
}

object Reader {

  given Reader[Byte] with {
    val arity = 1
    def read(results: jsql.ResultSet, baseIdx: Int) = results.getByte(baseIdx)
  }
  given Reader[Short] with {
    val arity = 1
    def read(results: jsql.ResultSet, baseIdx: Int) = results.getShort(baseIdx)
  }
  given Reader[Int] with {
    val arity = 1
    def read(results: jsql.ResultSet, baseIdx: Int) = results.getInt(baseIdx)
  }
  given Reader[Long] with {
    val arity = 1
    def read(results: jsql.ResultSet, baseIdx: Int) = results.getLong(baseIdx)
  }
  given Reader[Float] with {
    val arity = 1
    def read(results: jsql.ResultSet, baseIdx: Int) = results.getFloat(baseIdx)
  }
  given Reader[Double] with {
    val arity = 1
    def read(results: jsql.ResultSet, baseIdx: Int) = results.getDouble(baseIdx)
  }
  given Reader[Boolean] with {
    val arity = 1
    def read(results: jsql.ResultSet, baseIdx: Int) = results.getBoolean(baseIdx)
  }
  given Reader[String] with {
    val arity = 1
    def read(results: jsql.ResultSet, baseIdx: Int) = results.getString(baseIdx)
  }
  given Reader[Array[Byte]] with {
    val arity = 1
    def read(results: jsql.ResultSet, baseIdx: Int) = results.getBytes(baseIdx)
  }

  given optReader[T](using reader: Reader[T]): Reader[Option[T]] with {
    val arity = 1
    def read(results: jsql.ResultSet, baseIdx: Int) = Option (reader.read (results, baseIdx) )
  }


  class ProductReader[A](
    m: deriving.Mirror.ProductOf[A],
    readers: Array[Reader[_]]
  ) extends Reader[A] {
    def arity = readers.map(_.arity).sum
    def read(results: jsql.ResultSet, baseIdx: Int): A = {
      val elems = new Array[Any](readers.length)
      var idx: Int = baseIdx
      for (i <- 0 until readers.length) {
        elems(i) = readers(i).read(results, idx)
        idx += readers(i).arity
      }
      val prod: Product = new scala.Product {
        def productElement(n: Int): Any = elems(n)
        def productArity: Int = elems.length
        def canEqual(that: Any) = true
        override def productIterator: Iterator[Any] = elems.iterator
      }
      m.fromProduct(prod)
    }
  }

  inline def summonReaders[T <: Tuple]: List[Reader[_]] = inline compiletime.erasedValue[T] match {
    case _: EmptyTuple => Nil
    case _: (t *: ts) => compiletime.summonInline[Reader[t]] :: summonReaders[ts]
  }

  inline given [A <: Tuple](using m: deriving.Mirror.ProductOf[A]): Reader[A] = ProductReader[A](
    m,
    summonReaders[m.MirroredElemTypes].toArray
  )

  inline def derived[A](using m: deriving.Mirror.ProductOf[A]): Reader[A] = ProductReader[A](
    m,
    summonReaders[m.MirroredElemTypes].toArray
  )

}

object read {

  def apply[A](query: Query)(using c: Connection, r: Reader[A]): List[A] = {
    val elems = collection.mutable.ListBuffer.empty[A]

    var stat: jsql.PreparedStatement = null
    var res: jsql.ResultSet = null
    try {
      stat = Query.newPreparedStatement(query, c.underlying)
      res = stat.executeQuery()

      while (res.next()) {
        elems += r.read(res, 1)
      }
    } finally {
      if (res != null) res.close()
      if (stat != null) stat.close()
    }
    elems.result()
  }

}

object write {
  def apply(query: Query)(using c: Connection): Int = {
    var stat: jsql.PreparedStatement = null
    try {
      stat = Query.newPreparedStatement(query, c.underlying)
      stat.executeUpdate()
    } finally {
      if (stat != null) stat.close()
    }
  }

  def generating[A](query: Query)(using c: Connection, r: Reader[A]): A = {
    var stat: jsql.PreparedStatement = null
    var res: jsql.ResultSet = null
    try {
      stat = Query.newPreparedStatement(query, c.underlying)
      stat.executeUpdate()
      res = stat.getGeneratedKeys()
      res.next()
      r.read(res, 1)
    } finally {
      if (res != null) res.close()
      if (stat != null) stat.close()
    }
  }
}

object transaction {

  def apply[A](ds: javax.sql.DataSource)(fn: Connection ?=> A): A = {
    val conn: jsql.Connection = ds.getConnection()
    try {
      conn.setAutoCommit(false)
      val r = fn(using Connection(conn))
      conn.commit()
      r
    } catch {
      case ex: Throwable =>
        conn.rollback()
        throw ex
    } finally {
      conn.close()
    }
  }

}


object run {

  def apply[A](ds: javax.sql.DataSource)(fn: Connection ?=> A): A = {
    val conn: jsql.Connection = ds.getConnection()
    try {
      conn.setAutoCommit(true)
      fn(using Connection(conn))
    } finally {
      conn.close()
    }
  }

}
