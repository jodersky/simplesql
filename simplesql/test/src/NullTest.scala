import utest.*
import simplesql as sq

object NullTest extends TestSuite:

  val tests = Tests{
    test("null"){
      val ds = simplesql.DataSource.pooled("jdbc:sqlite::memory:")
      ds.transaction:
        sql"""
          create table user (
            id integer primary key,
            name string,
            email string not null
          )
        """.write()

        sql"""insert into user values (${1}, ${Some("admin")}, ${"admin@example.org"})""".write() ==> 1
        sql"""insert into user values (${2}, ${None: Option[String]}, ${"admin@example.org"})""".write() ==> 1
        sql"""insert into user values (${3}, null, ${"admin@example.org"})""".write() ==> 1

        case class User(id: Int, name: Option[String], email: String) derives sq.Reader
        sql"select * from user".read[User] ==>
          User(1, Some("admin"), "admin@example.org") ::
          User(2, None, "admin@example.org") ::
          User(3, None, "admin@example.org") :: Nil
    }
  }
