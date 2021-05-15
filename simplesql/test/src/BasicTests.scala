import utest._

import simplesql as sq

object BasicTests extends TestSuite {
  val tests = Tests{
    test("basic"){
      sq.transaction("jdbc:sqlite::memory:"){
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
        sq.write(sql"""insert into user values (1, "admin", "admin@example.org")""") ==> 1

        sq.read[(Int, String, String)](sql"select * from user") ==> (1, "admin", "admin@example.org") :: Nil

        // product flattening
        sq.read[(Int, (String, String))](sql"select * from user") ==> (1, ("admin", "admin@example.org")) :: Nil
        sq.read[((Int, String), String)](sql"select * from user") ==> ((1, "admin"), "admin@example.org") :: Nil

        // named products
        case class User(id: Int, name: String, email: String)
        sq.read[User](sql"select * from user") ==> User(1, "admin", "admin@example.org") :: Nil

        // nested named products
        case class Profile(name: String, email: String)
        case class Account(id: Int, p: Profile)
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
        sq.write(sql"""insert into user values ($account)""") ==> 1
        sq.read[Int](sql"""select id from user where name='john2'""") ==> 43 :: Nil
      }
    }
  }
}
