import utest.*

import simplesql as sq

object BasicTest extends TestSuite:

  val tests = Tests{
    test("basic") {
      val ds = simplesql.DataSource.pooled("jdbc:sqlite::memory:")
      ds.transaction:
        sql"""
          create table user (
            id integer primary key,
            name text not null,
            email text not null
          )
          """.write()

        // position-based products
        sql"select * from user".read[(Int, String, String)] ==> Nil
        sql"""insert into user values (${1}, ${"admin"}, ${"admin@example.org"})""".write() ==> 1
        sql"select * from user".read[(Int, String, String)] ==> (1, "admin", "admin@example.org") :: Nil

        // named products
        case class User(id: Int, name: String, email: String) derives sq.Reader
        sql"select * from user".read[User] ==> User(1, "admin", "admin@example.org") :: Nil
        sql"select id,name,email from user".read[User] ==> User(1, "admin", "admin@example.org") :: Nil

        // missing name
        intercept[java.sql.SQLException](
          sql"select id,name from user".read[User] ==> User(1, "admin", "admin@example.org") :: Nil
        )

        // missing index
        intercept[java.sql.SQLException](
          sql"select id,name from user".read[(Int, String, String)] ==> User(1, "admin", "admin@example.org") :: Nil
        )

        // simple interpolation
        val id = 42
        sql"""insert into user values ($id, "john", "john@smith.com")""".write() ==> 1
        sql"""select id from user where name='john'""".read[Int] ==> 42 :: Nil
    }
    test("named") {
      val ds = simplesql.DataSource.pooled("jdbc:sqlite::memory:")
      ds.transaction:
        sql"""
          create table user (
            id integer primary key,
            email text not null,
            snakified_name text not null
          )
          """.write()
      case class User(id: Int, snakifiedName: String, email: String) derives sq.Reader // field names are out of order

      val u1 = User(1, "john", "john@example.org")
      val u2 = User(2, "josh", "josh@example.org")

      ds.run:
        sql"""insert into user (id, snakified_name, email) values
          (${u1.id}, ${u1.snakifiedName}, ${u1.email}),
          (${u2.id}, ${u2.snakifiedName}, ${u2.email})""".write() // TODO: implement writer derivation


        sql"""select * from user""".read[User] ==> List(u1, u2)
    }
    test("named override") {
      val ds = simplesql.DataSource.pooled("jdbc:sqlite::memory:")
      ds.transaction:
        sql"""create table entry (nameoverride text primary key)""".write()
        sql"""insert into entry (nameoverride) values ('foo')""".write()

      case class Entry(@sq.col("nameoverride") columnName: String) derives sq.Reader

      ds.run:
        sql"select * from entry".read[Entry] ==> List(Entry("foo"))
    }
  }
