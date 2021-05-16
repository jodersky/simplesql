package simplesql

import java.{sql => jsql}
import scala.deriving
import scala.compiletime
import scala.annotation


@annotation.implicitNotFound("No database connection found. Make sure to call this in a `run()` or `transaction()` block.")
case class Connection(underlying: jsql.Connection) {
  extension (sc: StringContext) {
    inline def sql(inline args: Any*): Query = ${Query.sqlImpl('{sc}, '{args})}
  }
}

/** A thing wrapper around an SQL statement */
case class Query(
  sql: String,
  writers: Array[Writer[_]],
  data: Array[_]
)

object Query {

  def newPreparedStatement(q: Query, c: jsql.Connection): jsql.PreparedStatement = {
    val stat = c.prepareStatement(q.sql)
    var baseIdx = 1
    for (i <- 0 until q.writers.length) {
      val w = q.writers(i)
      val d = q.data(i)
      w.asInstanceOf[Writer[Any]].write(stat, baseIdx, d)
      baseIdx += w.arity
    }
    stat
  }

  import scala.quoted.{Expr, Quotes, Varargs}
  def sqlImpl(sc: Expr[StringContext], args: Expr[Seq[Any]])(using qctx: Quotes): Expr[Query] = {
    import scala.quoted.quotes.reflect._

    val writers: Seq[Expr[Writer[_]]] = args match {
      case Varargs(exprs) =>
        for ('{ $arg: t } <- exprs) yield {
          val w = TypeRepr.of[Writer].appliedTo(
            TypeRepr.of[t].widen
          )

          Implicits.search(w) match {
            case iss: ImplicitSearchSuccess =>
              iss.tree.asExprOf[Writer[_]]
            case isf: ImplicitSearchFailure =>
              report.error(s"could not find implicit for ${w.show}", arg)
              '{???}
          }
        }
      case _ =>
        report.error("args must be explicit", args)
        Nil
    }


    '{
      val ws = ${Expr.ofSeq(writers)}
      val as = $args
      val arity = ws.map(_.arity).sum

      val strings = $sc.parts.iterator
      val buf = new StringBuilder(strings.next())

      while(strings.hasNext) {
        buf.append("?")
        for (i <- 1 until arity) {
          buf.append(",")
          buf.append("?")
        }
        buf.append(strings.next())
      }
      val str = buf.result()
      Query(
        str,
        ws.toArray,
        as.toArray
      )
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

  inline given [A](using m: deriving.Mirror.ProductOf[A]): Reader[A] = ProductReader[A](
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

trait Writer[A] {
  type Value = A
  def arity: Int
  def write(statement: jsql.PreparedStatement, baseIdx: Int, value: Value): Unit
}

object Writer {

  given Writer[Byte] with {
    val arity = 1
    def write(stat: jsql.PreparedStatement, idx: Int, value: Value): Unit = {
      stat.setByte(idx, value)
    }
  }
  given Writer[Short] with {
    val arity = 1
    def write(stat: jsql.PreparedStatement, idx: Int, value: Value): Unit = {
      stat.setShort(idx, value)
    }
  }
  given Writer[Int] with {
    val arity = 1
    def write(stat: jsql.PreparedStatement, idx: Int, value: Value): Unit = {
      stat.setInt(idx, value)
    }
  }
  given Writer[Long] with {
    val arity = 1
    def write(stat: jsql.PreparedStatement, idx: Int, value: Value): Unit = {
      stat.setLong(idx, value)
    }
  }
  given Writer[Float] with {
    val arity = 1
    def write(stat: jsql.PreparedStatement, idx: Int, value: Value): Unit = {
      stat.setFloat(idx, value)
    }
  }
  given Writer[Double] with {
    val arity = 1
    def write(stat: jsql.PreparedStatement, idx: Int, value: Value): Unit = {
      stat.setDouble(idx, value)
    }
  }
  given Writer[Boolean] with {
    val arity = 1
    def write(stat: jsql.PreparedStatement, idx: Int, value: Value): Unit = {
      stat.setBoolean(idx, value)
    }
  }
  given Writer[String] with {
    val arity = 1
    def write(stat: jsql.PreparedStatement, idx: Int, value: Value): Unit = {
      stat.setString(idx, value)
    }
  }
  given Writer[Array[Byte]] with {
    val arity = 1
    def write(stat: jsql.PreparedStatement, idx: Int, value: Value): Unit = {
      stat.setBytes(idx, value)
    }
  }
  class ProductWriter[A <: Product](
    writers: Array[Writer[_]]
  ) extends Writer[A] {
    val arity = writers.map(_.arity).sum

    def write(statement: jsql.PreparedStatement, baseIdx: Int, value: A): Unit = {
      for (i <- 0 until writers.length) {
        writers(i).asInstanceOf[Writer[Any]].write(statement, baseIdx + i, value.productElement(i))
      }
    }
  }

  inline def summonWriters[T <: Tuple]: List[Writer[_]] = inline compiletime.erasedValue[T] match {
    case _: EmptyTuple => Nil
    case _: (t *: ts) => compiletime.summonInline[Writer[t]] :: summonWriters[ts]
  }

  inline given [A <: Product](using m: deriving.Mirror.ProductOf[A]): ProductWriter[A] = ProductWriter[A](
    summonWriters[m.MirroredElemTypes].toArray
  )

}

object write {
  def apply(query: Query)(using c: Connection): Int = {
    var stat: jsql.PreparedStatement = null
    try {
      val stat = Query.newPreparedStatement(query, c.underlying)
      stat.executeUpdate()
    } finally {
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
