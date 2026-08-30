import simplesql as sq

import utest.*

object ConnectionClosedTest extends TestSuite:

  val tests = Tests {
    test("connection already closed") {
      val ds = sq.DataSource.pooled("jdbc:sqlite::memory:")
      val readError = intercept[Exception]:
        ds.run:
          val query = sql"select * from user"
          query
        .read[Int]
      readError.getMessage ==>
        "Connection is already closed, are you calling simplesql.Query.read() inside simplesql.DataSource.run() or simplesql.DataSource.transaction()?"
      val writeError = intercept[Exception]:
        ds.transaction:
          sql"insert into user values (1)"
        .write()
      writeError.getMessage ==>
        "Connection is already closed, are you calling simplesql.Query.write() inside simplesql.DataSource.run() or simplesql.DataSource.transaction()?"
    }
  }
