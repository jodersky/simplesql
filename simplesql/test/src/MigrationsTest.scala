import utest.*

import simplesql.migrations as sqm
import scala.annotation.migration

object MigrationsTest extends TestSuite:
  val tests = Tests{
    test("basic") {
      val mdb = simplesql.migrations.MigrationTool.fromClasspath(
        simplesql.DataSource.pooled("jdbc:sqlite::memory:")
      )
      mdb.applyUp("head")
      mdb.applyDown("base")
    }
    test("graph") {
      val m0 = sqm.Migration("0", "base", "", "")
      val m1 = sqm.Migration("1", "0", "", "")
      val m2 = sqm.Migration("2", "1", "", "")
      val m3 = sqm.Migration("3", "2", "", "")

      val migrations = List(m0, m1, m2, m3)

      test("ok") {
        val graph = sqm.MigrationGraph(sqm.InMemoryStorage(migrations))
        assert(graph.head == m3)
        assert(graph.listDown("head", "base").reverse == migrations)

        graph.listDown("2", "1") ==> m2 :: Nil
        graph.listDown("3", "1") ==> List(m3, m2)
        graph.listDown("3", "0") ==> List(m3, m2, m1)
        graph.listDown("head", "0") ==> List(m3, m2, m1)
        graph.listDown("3", "base") ==> List(m3, m2, m1, m0)

        graph.listDown("3", "3") ==> Nil
        graph.listDown("head", "head") ==> Nil
      }
      test("not found") {
        val graph = sqm.MigrationGraph(sqm.InMemoryStorage(migrations))
        val ex1 = intercept[sqm.MigrationGraphException]{
          graph.listDown("0", "1")
        }
        assert(ex1.getMessage() == "migration version '1' cannot be reached from '0'")

        val ex2 = intercept[sqm.MigrationGraphException]{
          graph.listDown("1", "3")
        }
        assert(ex2.getMessage() == "migration version '3' cannot be reached from '1'")

      }
      test("multiple heads") {
        val extraHeads = Seq(
          sqm.Migration("4", "base", "", ""), // head
          sqm.Migration("5", "base", "", ""),
          sqm.Migration("6", "5", "", ""), // head
          sqm.Migration("7", "2", "", ""), // head
          sqm.Migration("8", "1", "", "") // head
        )

        val graph = sqm.MigrationGraph(sqm.InMemoryStorage(migrations ++ extraHeads))

        test("one head required") {
          val ex = intercept[sqm.MigrationGraphException]{
            graph.listDown("head", "base")
          }
          assert(ex.getMessage() == "multiple migration heads: 8, 4, 6, 7, 3")
        }
        test("multiple heads ok") {
          graph.listDown("3", "1") ==> List(m3, m2)
          graph.listDown("3", "0") ==> List(m3, m2, m1)
        }
      }
    }
    test("updown") {
      val ds = simplesql.DataSource.pooled("jdbc:sqlite::memory:")
      val mdb = sqm.MigrationTool.fromClasspath(ds)

      // check that database is empty initially
      ds.run:
        sql"""select name from sqlite_master where type='table'""".read[String] ==> Nil

      mdb.applyUp("head")

      // check that database contains expected values after migrations
      ds.run:
        sql"""select user_id from user_account""".read[Int] == List(1)

      mdb.applyUp("head") // should be a noop
      ds.run:
        sql"""select user_id from user_account""".read[Int] == List(1)

      mdb.applyDown("base")

      // check that database is empty again
      ds.run:
        sql"""select name from sqlite_master where type='table'""".read[String] ==> List("simplesql_migration")

      mdb.applyDown("base") // should be a noop
      ds.run:
        sql"""select name from sqlite_master where type='table'""".read[String] ==> List("simplesql_migration")
    }
  }
