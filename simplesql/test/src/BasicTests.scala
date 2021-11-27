import utest._

import simplesql as sq

object BasicTests extends TestSuite {
  val tests = Tests{
    test("basic"){
      val ds = com.zaxxer.hikari.HikariDataSource()
      ds.setJdbcUrl("jdbc:sqlite::memory:")
      sq.transaction(ds){
        sq.write(
          sql"""
            create table user (
              id integer primary key,
              name string not null,
              email string not null
            )
          """
        )
        sq.read[(Int, String, String)](sql"select * from user") ==> Nil
        sq.write(sql"""insert into user values (${1}, ${"admin"}, ${"admin@example.org"})""") ==> 1

        sq.read[(Int, String, String)](sql"select * from user") ==> (1, "admin", "admin@example.org") :: Nil

        // product flattening
        sq.read[(Int, (String, String))](sql"select * from user") ==> (1, ("admin", "admin@example.org")) :: Nil
        sq.read[((Int, String), String)](sql"select * from user") ==> ((1, "admin"), "admin@example.org") :: Nil

        // named products
        case class User(id: Int, name: String, email: String) derives sq.Reader
        sq.read[User](sql"select * from user") ==> User(1, "admin", "admin@example.org") :: Nil

        // nested named products
        case class Profile(name: String, email: String) derives sq.Reader
        case class Account(id: Int, p: Profile) derives sq.Reader
        sq.read[Account](sql"select * from user") ==> Account(1, Profile("admin", "admin@example.org")) :: Nil

        sq.write(sql"update user set name = 'joe' where name = 'admin'") ==> 1
        sq.write(sql"update user set name = 'joe' where name = 'admin'") ==> 0

        sq.read[Int](sql"select id from user where name='joe'") ==> 1 :: Nil

        // simple interpolation
        val id = 42
        sq.write(sql"""insert into user values ($id, "john", "john@smith.com")""") ==> 1
        sq.read[Int](sql"""select id from user where name='john'""") ==> 42 :: Nil

        // product interpolation
        val account = Account(43, Profile("john2", "john2@smith.com"))
        sq.write(sql"""insert into user values (${account.id}, ${account.p.name}, ${account.p.email})""") ==> 1
        sq.read[Int](sql"""select id from user where name='john2'""") ==> 43 :: Nil
      }
    }
    test("returning"){
      val ds = com.zaxxer.hikari.HikariDataSource()
      ds.setJdbcUrl("jdbc:sqlite::memory:")
      sq.transaction(ds){
        sq.write(
          sql"""
            create table user (
              id integer primary key autoincrement,
              name string not null,
              email string not null
            )
          """
        )
      }

      val res0 = sq.run(ds) {
        sq.write.generating[Int](sql"insert into user (name, email) values ('admin', 'foo')")
      }
      res0 ==> 1
      val res1 = sq.run(ds) {
        sq.write.generating[Int](sql"insert into user (name, email) values ('admin', 'foo')")
      }
      res1 ==> 2
    }
    test("null"){
      val ds = com.zaxxer.hikari.HikariDataSource()
      ds.setJdbcUrl("jdbc:sqlite::memory:")
      sq.transaction(ds){
        sq.write(
          sql"""
            create table user (
              id integer primary key,
              name string,
              email string not null
            )
          """
        )
        sq.write(sql"""insert into user values (${1}, ${"admin"}, ${"admin@example.org"})""") ==> 1

        case class User(id: Int, name: Option[String], email: String) derives sq.Reader
        sq.read[User](sql"select * from user") ==> User(1, Some("admin"), "admin@example.org") :: Nil

        case class Profile(name: Option[String], email: String) derives sq.Reader
        case class Account(id: Int, p: Profile) derives sq.Reader
        sq.read[Account](sql"select * from user") ==> Account(1, Profile(Some("admin"), "admin@example.org")) :: Nil

        val account = Account(43, Profile(Some("john2"), "john2@smith.com"))
        sq.write(sql"""insert into user values (${account.id}, ${account.p.name}, ${account.p.email})""") ==> 1
        sq.read[Int](sql"""select id from user where name='john2'""") ==> 43 :: Nil

        val accountWithoutName = Account(44, Profile(None, "unknown@test.com"))
        sq.write(sql"""insert into user values (${accountWithoutName.id}, ${accountWithoutName.p.name}, ${accountWithoutName.p.email})""") ==> 1
        sq.read[Int](sql"""select id from user where email='unknown@test.com'""") ==> 44 :: Nil
      }
    }
  }
}
