import simplesql as sq

import utest.*
import sq.sql

object NoConnectionFoundTest extends TestSuite:

  val tests = Tests {
    test("connection already closed") {
      val ds = sq.DataSource.pooled("jdbc:sqlite::memory:")
      val readError = compileError(
        """ds.run:
  val query = sql"select * from user"
  query
.read[Int]"""
      ).check("", "No database connection found. Make sure to call this in a `run()` or `transaction()` block.")
      val writeError = compileError(
        """ds.transaction:
  sql"insert into user values (1)"
.write()"""
      ).check("", "No database connection found. Make sure to call this in a `run()` or `transaction()` block.")
    }
  }
