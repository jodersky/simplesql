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
import java.{util => ju}
import java.time.Instant

@annotation.implicitNotFound("No database connection found. Make sure to call this in a `run()` or `transaction()` block.")
case class Connection(underlying: jsql.Connection):
  self =>
  extension (inline sc: StringContext)
    inline def sql(inline args: Any*): Query = ${Query.sqlImpl('self, '{sc}, '{args})}

/** A thin wrapper around an SQL statement */
case class Query(conn: Connection, sql: String, fillStatement: jsql.PreparedStatement => Unit):

  def read[A](using r: Reader[A]): List[A] =
    val elems = collection.mutable.ListBuffer.empty[A]

    var stat: jsql.PreparedStatement = null
    var res: jsql.ResultSet = null
    try
      stat = conn.underlying.prepareStatement(sql)
      fillStatement(stat)
      res = stat.executeQuery()

      while res.next() do
        elems += r.read(res)
    finally
      if res != null then res.close()
      if stat != null then stat.close()
    elems.result()

  def readOne[A](using r: Reader[A]): A = read[A].head

  def readOpt[A](using r: Reader[A]): Option[A] = read[A].headOption

  def write(): Int =
    var stat: jsql.PreparedStatement = null
    try
      stat = conn.underlying.prepareStatement(sql, jsql.Statement.RETURN_GENERATED_KEYS)
      fillStatement(stat)
      stat.executeUpdate()
    finally
      if stat != null then stat.close()

object Query:

  import scala.quoted.{Expr, Quotes, Varargs}
  def sqlImpl(c: Expr[Connection], sc0: Expr[StringContext], args0: Expr[Seq[Any]])(using qctx: Quotes): Expr[Query] =
    import scala.quoted.quotes.reflect._
    val args: Seq[Expr[?]] = args0 match
      case Varargs(exprs) => exprs
    val writers: Seq[Expr[SimpleWriter[?]]] = for (case '{ $arg: t } <- args) yield
      val w = TypeRepr.of[SimpleWriter].appliedTo(
        TypeRepr.of[t].widen
      )

      Implicits.search(w) match
        case iss: ImplicitSearchSuccess =>
          iss.tree.asExprOf[SimpleWriter[?]]
        case isf: ImplicitSearchFailure =>
          report.error(s"could not find implicit for ${w.show}", arg)
          '{???}

    val qstring = sc0.value match
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

    val r = '{
      Query(
        $c,
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

end Query

trait SimpleWriter[-A] {
  def write(stat: jsql.PreparedStatement, idx: Int, value: A): Unit
}

object SimpleWriter:

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
  given SimpleWriter[ju.UUID] = (stat, idx, value) => stat.setObject(idx, value)
  given SimpleWriter[Instant] = (stat, idx, value) => stat.setLong(idx, value.getEpochSecond())

  given optWriter[A](using writer: SimpleWriter[A]): SimpleWriter[Option[A]] with {
      def write(stat: jsql.PreparedStatement, idx: Int, value: Option[A]) = value match {
        case Some (v) => writer.write (stat, idx, v)
        case None => stat.setNull(idx, jsql.Types.NULL)
    }
  }


trait SimpleReader[+A]:
  def readIdx(results: jsql.ResultSet, idx: Int): A
  def readName(result: jsql.ResultSet, name: String): A

object SimpleReader:

  given SimpleReader[Byte] with
    def readIdx(results: jsql.ResultSet, idx: Int) = results.getByte(idx)
    def readName(results: jsql.ResultSet, name: String) = results.getByte(name)

  given SimpleReader[Short] with
    def readIdx(results: jsql.ResultSet, idx: Int) = results.getShort(idx)
    def readName(results: jsql.ResultSet, name: String) = results.getShort(name)

  given SimpleReader[Int] with
    def readIdx(results: jsql.ResultSet, idx: Int) = results.getInt(idx)
    def readName(results: jsql.ResultSet, name: String) = results.getInt(name)

  given SimpleReader[Long] with
    def readIdx(results: jsql.ResultSet, idx: Int) = results.getLong(idx)
    def readName(results: jsql.ResultSet, name: String) = results.getLong(name)

  given SimpleReader[Float] with
    def readIdx(results: jsql.ResultSet, idx: Int) = results.getFloat(idx)
    def readName(results: jsql.ResultSet, name: String) = results.getFloat(name)

  given SimpleReader[Double] with
    def readIdx(results: jsql.ResultSet, idx: Int) = results.getDouble(idx)
    def readName(results: jsql.ResultSet, name: String) = results.getDouble(name)

  given SimpleReader[Boolean] with
    def readIdx(results: jsql.ResultSet, idx: Int) = results.getBoolean(idx)
    def readName(results: jsql.ResultSet, name: String) = results.getBoolean(name)

  given SimpleReader[String] with
    def readIdx(results: jsql.ResultSet, idx: Int) = results.getString(idx)
    def readName(results: jsql.ResultSet, name: String) = results.getString(name)

  given SimpleReader[Array[Byte]] with
    def readIdx(results: jsql.ResultSet, idx: Int) = results.getBytes(idx)
    def readName(results: jsql.ResultSet, name: String) = results.getBytes(name)

  given SimpleReader[ju.UUID] with
    def readIdx(results: jsql.ResultSet, idx: Int): ju.UUID =
      results.getObject(idx).asInstanceOf[ju.UUID]

    def readName(result: jsql.ResultSet, name: String): ju.UUID =
      result.getObject(name).asInstanceOf[ju.UUID]

  given SimpleReader[Instant] with
    def readIdx(results: jsql.ResultSet, idx: Int) = Instant.ofEpochSecond(results.getLong(idx))
    def readName(results: jsql.ResultSet, name: String) = Instant.ofEpochSecond(results.getLong(name))

  given optReader[T](using reader: SimpleReader[T]): SimpleReader[Option[T]] with
    def readIdx(results: jsql.ResultSet, idx: Int) = Option(reader.readIdx(results, idx))
    def readName(results: jsql.ResultSet, name: String) = Option(reader.readName(results, name))

trait Reader[A]:
  /** Read a row into the corresponding type. */
  def read(results: jsql.ResultSet): A

object Reader:

  class ProductReader[A](
    m: deriving.Mirror.ProductOf[A],
    readers: Array[SimpleReader[?]]
  ) extends Reader[A]:
    def read(results: jsql.ResultSet): A =
      val elems = new Array[Any](readers.length)
      for i <- 0 until readers.length do
        // results.getMetaData().getColumnName()
        elems(i) = readers(i).readIdx(results, i + 1)
      val prod: Product = new scala.Product:
        def productElement(n: Int): Any = elems(n)
        def productArity: Int = elems.length
        def canEqual(that: Any) = true
        override def productIterator: Iterator[Any] = elems.iterator
      m.fromProduct(prod)
  end ProductReader

  inline given simple[A](using s: SimpleReader[A]): Reader[A] =
    new Reader[A]:
      def read(results: jsql.ResultSet): A = s.readIdx(results, 1)


  inline def summonReaders[T <: Tuple]: List[SimpleReader[?]] = inline compiletime.erasedValue[T] match
    case _: EmptyTuple => Nil
    case _: (t *: ts) => compiletime.summonInline[SimpleReader[t]] :: summonReaders[ts]

  inline given [A <: Tuple](using m: deriving.Mirror.ProductOf[A]): Reader[A] = ProductReader[A](
    m,
    summonReaders[m.MirroredElemTypes].toArray
  )

  inline def derived[A]: Reader[A] = ${deriveImpl[A]}

  import scala.quoted.Expr
  import scala.quoted.Type
  import scala.quoted.Quotes
  def deriveImpl[A: Type](using qctx: Quotes): Expr[Reader[A]] =
    import qctx.reflect.*

    val tsym = TypeRepr.of[A].classSymbol

    // TODO: this is maybe too strict. We technically don't need a case class,
    // only an apply method
    if tsym.isEmpty || !tsym.get.flags.is(Flags.Case) then
      report.error("derivation of Readers is only supported for case classes")
      return '{???}

    val fields: List[Symbol] = tsym.get.primaryConstructor.paramSymss.flatten

    val AppliedType(tc, _) = TypeRepr.of[SimpleReader[A]]: @unchecked

    val childReaders: List[Expr[SimpleReader[?]]] = for field <- fields yield
      val childTpe = tc.appliedTo(field.termRef.widenTermRefByName.dealias)
      Implicits.search(childTpe) match
        case iss: ImplicitSearchSuccess =>
          iss.tree.asExprOf[SimpleReader[?]]
        case isf: ImplicitSearchFailure =>
          report.error(s"no ${childTpe.show} found for ${field.fullName}")
          report.error(isf.explanation)
          '{???}

    val childNames: List[String] = for field <- fields yield
      field.getAnnotation(TypeRepr.of[col].typeSymbol) match
        case None => snakify(field.name)
        case Some(annot) =>
          annot.asExprOf[col] match
            case '{col($x)} => x.valueOrAbort

    '{
      new Reader[A]:
        def read(results: jsql.ResultSet): A = ${
          val reads: List[Term] = for (reader, name) <- childReaders.zip(childNames) yield
            '{
              ${reader}.readName(results, ${Expr(name)})
            }.asTerm

          Apply(
            Select(New(TypeTree.of[A]), tsym.get.primaryConstructor),
            reads
          ).asExprOf[A]

        }
    }

  /** `thisIsSnakeCase => this_is_snake_case` */
  private def snakify(camelCase: String): String =
    val snake = new StringBuilder
    var prevIsLower = false
    for c <- camelCase do
      if prevIsLower && c.isUpper then
        snake += '_'
      snake += c.toLower
      prevIsLower = c.isLower
    snake.result()

end Reader

class col(val name: String) extends annotation.StaticAnnotation

class DataSource(getConnection: () => Connection):

  def transaction[A](fn: Connection ?=> A): A =
    val conn = getConnection()
    val underlying = conn.underlying
    try
      underlying.setAutoCommit(false)
      val r = fn(using conn)
      underlying.commit()
      r
    catch
      case ex: Throwable =>
        underlying.rollback()
        throw ex
    finally
      underlying.close()

  def run[A](fn: Connection ?=> A): A =
    val conn = getConnection()
    val underlying = conn.underlying
    try
      underlying.setAutoCommit(true)
      fn(using conn)
    finally
      underlying.close()

object DataSource:

  def pooled(jdbcUrl: String, username: String = null, password: String = null) =
    val ds = com.zaxxer.hikari.HikariDataSource()
    ds.setJdbcUrl(jdbcUrl)
    if username != null then ds.setUsername(username)
    if password != null then ds.setPassword(password)
    DataSource(() => Connection(ds.getConnection()))
