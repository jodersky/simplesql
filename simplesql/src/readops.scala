package simplesql

import java.{sql => jsql}
import scala.deriving
import scala.compiletime
import scala.annotation


@annotation.implicitNotFound("No database connection found. Make sure to call this in a `run()` or `transaction()` block.")
case class Connection(underlying: jsql.Connection) {

  // TODO: do we need to register statements and close them on connection close?
  // Although unlikely, a user could create an `sql` statement and never pass it
  // to a read/write.
  extension (sc: StringContext) {
    inline def sql(inline args: Any*): jsql.PreparedStatement = ${Writer.sqlImpl('{underlying}, '{sc}, '{args})}
  }

}

trait Reader[A]{
  def read(results: jsql.ResultSet, nextIdx: () => Int): A
}

case class SimpleReader[A](read0: (jsql.ResultSet, Int) => A) extends Reader[A] {
  def read(results: jsql.ResultSet, nextIdx: () => Int): A = read0(results, nextIdx())
}

object Reader {

  given SimpleReader[Byte](_.getByte(_))
  given SimpleReader[Short](_.getShort(_))
  given SimpleReader[Int](_.getInt(_))
  given SimpleReader[Long](_.getLong(_))
  given SimpleReader[Float](_.getFloat(_))
  given SimpleReader[Double](_.getDouble(_))
  given SimpleReader[Boolean](_.getBoolean(_))
  given SimpleReader[String](_.getString(_))
  given SimpleReader[Array[Byte]](_.getBytes(_))

  class ProductReader[A](
    m: deriving.Mirror.ProductOf[A],
    readers: Array[Reader[_]]
  ) extends Reader[A] {
    def read(results: jsql.ResultSet, nextIdx: () => Int): A = {
      val prod = deriving.ArrayProduct(new Array[AnyRef](readers.length))
      for (i <- 0 until readers.length) {
        prod(i) = readers(i).read(results, nextIdx)
      }
      m.fromProduct(prod)
    }
  }

  inline def summonReaders[T <: Tuple]: List[Reader[_]] = inline compiletime.erasedValue[T] match {
    case _: EmptyTuple => Nil
    case _: (t *: ts) => compiletime.summonInline[Reader[t]] :: summonReaders[ts]
  }

  inline given [A](using m: deriving.Mirror.ProductOf[A]) as Reader[A] = ProductReader[A](
    m,
    summonReaders[m.MirroredElemTypes].toArray
  )

}

object read {

  def apply[A](query: jsql.PreparedStatement)(using c: Connection, r: Reader[A]): List[A] = {
    val elems = collection.mutable.ListBuffer.empty[A]
    var idx = 0
    def nextIdx() = {
      idx += 1
      idx
    }
    var res: jsql.ResultSet = null
    try {
      res = query.executeQuery()

      while (res.next()) {
        elems += r.read(res, nextIdx _)
        idx = 0
      }
    } finally {
      if (res != null) res.close()
      query.close()
    }

    elems.result()
  }

}

trait Writer[A] {
  def arity: Int
  def write(statement: jsql.PreparedStatement, nextIdx: () => Int, value: A): Unit
}
case class SimpleWriter[A](write0: (jsql.PreparedStatement, Int, A) => Unit) extends Writer[A] {
  def arity = 1
  def write(statement: jsql.PreparedStatement, nextIdx: () => Int, value: A): Unit = write0(statement, nextIdx(), value)
}

object Writer {
  given SimpleWriter[Byte](_.setByte(_, _))
  given SimpleWriter[Short](_.setShort(_, _))
  given SimpleWriter[Int](_.setInt(_, _))
  given SimpleWriter[Long](_.setLong(_, _))
  given SimpleWriter[Float](_.setFloat(_, _))
  given SimpleWriter[Double](_.setDouble(_, _))
  given SimpleWriter[Boolean](_.setBoolean(_, _))
  given SimpleWriter[String](_.setString(_, _))
  given SimpleWriter[Array[Byte]](_.setBytes(_, _))

  class ProductWriter[A <: Product](
    writers: Array[Writer[_]]
  ) extends Writer[A] {
    val arity = writers.map(_.arity).sum

    def write(statement: jsql.PreparedStatement, nextIdx: () => Int, value: A): Unit = {
      for (i <- 0 until writers.length) {
        writers(i).asInstanceOf[Writer[Any]].write(statement, nextIdx, value.productElement(i))
      }
    }
  }

  inline def summonWriters[T <: Tuple]: List[Writer[_]] = inline compiletime.erasedValue[T] match {
    case _: EmptyTuple => Nil
    case _: (t *: ts) => compiletime.summonInline[Writer[t]] :: summonWriters[ts]
  }

  inline given [A <: Product](using m: deriving.Mirror.ProductOf[A]) as ProductWriter[A] = ProductWriter[A](
    summonWriters[m.MirroredElemTypes].toArray
  )

  import scala.quoted._
  def sqlImpl(c: Expr[jsql.Connection], sc: Expr[StringContext], args: Expr[Seq[Any]])(using qctx: QuoteContext): Expr[jsql.PreparedStatement] = {
    val writers = args match {
      case Varargs(exprs) =>
        import qctx.tasty._
        for ('{ $arg: $t } <- exprs) yield {
          val w = AppliedType(
            typeOf[Writer[_]].asInstanceOf[AppliedType].tycon,
            arg.unseal.tpe.widen :: Nil
          )

          searchImplicit(w) match {
            case iss: ImplicitSearchSuccess =>
              iss.tree.seal.asInstanceOf[Expr[Writer[_]]]
            case isf: ImplicitSearchFailure =>
              report.error(s"could not find implicit for ${w.show}", arg)
              '{???}
          }
        }
      case _ =>
        report.error("args must be explicit", args)
        Nil
    }


    // TODO: the arity of the writers can be determined statically. Hence, we
    // should also create the sql string at compile time, not at runtime.
    '{
      val ws = ${Expr.ofSeq(writers)}
      val as = $args

      val strings = $sc.parts.iterator
      val buf = new StringBuilder(strings.next())
      val wsi = ws.map(_.arity).iterator
      while(strings.hasNext) {
        val arity = wsi.next()

        buf.append("?")
        for (i <- 1 until arity) {
          buf.append(",")
          buf.append("?")
        }
        buf.append(strings.next())
      }
      val str = buf.result()

      var idx = 0
      def nextIdx() = {
        idx += 1
        idx
      }

      val stat = $c.prepareStatement(str)
      for (i <- 0 until as.length) {
        ws(i).asInstanceOf[Writer[Any]].write(stat, nextIdx, as(i))
      }
      stat
    }
  }
}

object write {
  def apply(query: jsql.PreparedStatement)(using c: Connection): Int = {
    try {
      query.executeUpdate()
    } finally {
      query.close()
    }
  }
}

object transaction {

  def apply[A](url: String)(fn: Connection ?=> A): A = {
    val conn: jsql.Connection = jsql.DriverManager.getConnection(url)

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

  def apply[A](url: String)(fn: Connection ?=> A): A = {
    val conn: jsql.Connection = jsql.DriverManager.getConnection(url)

    try {
      conn.setAutoCommit(true)
      fn(using Connection(conn))
    } finally {
      conn.close()
    }
  }

}
