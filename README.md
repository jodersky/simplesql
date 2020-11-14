# Simple SQL

A no-frills SQL library for Scala 3.

SimpleSQL is a very thin wrapper around JDBC, which allows you to take full
advantage of *full SQL* and *any database* with a JDBC driver.

SimpleSQL is not a functional DSL to build SQL queries (I recommend Quill for
that, which unfortunately is not yet available for Scala 3), but it does offer
safe string interpolation and utilities to work with product types, wich are the
natural representation of relational data sets.

## Example

```scala
import _root_.{simplesql => sq}

// all queries must be run within the context of a connection
sq.transaction("jdbc:sqlite::memory:"){ /* an implicit connection is injected here */

  sq.write(
    sql"""
      create table user (
        id integer primary key,
        name string not null,
        email string not null
      )
    """
  )

  sq.read[(Int, String, String)](sql"select * from user")
  sq.write(sql"""insert into user values (1, "admin", "admin@example.org")""")

  // also works with named products, i.e. case classes
  case class User(id: Int, name: String, email: String)
  sq.read[User](sql"select * from user")
  val u = User(2, "admin", "admin@example.org")
  sq.write(sql"""insert into user values ($u)""")
}

```

## Explanation

### Database connection

All queries must be run on a connection to a database. SimpleSQL models this
through a `Connection` class, which is just a simple wrapper around
`java.sql.Connection`.

A connection may be obtained as a [context
function](https://dotty.epfl.ch/docs/reference/contextual/context-functions.html)
through either `simplesql.run` or `simplesql.transaction`. Both functions
provide a connection, however the latter will automatically roll back any
changes, should an exception be thrown in its body.

An in-scope connection also gives access to the `sql` string intepolator. This
interpolator is a utility to build `java.sql.PreparedStatements`. In other
words, it can be used to build injection-safe queries with interpolated
parameters. Interpolated parameters may be primitve types (supported by JDBC),
or products of these. Products will be flattened into multiple interpolated '?'
parameters.

### Read Queries

Read queries (i.e. selects) must be run in a `read` call. A read must have its
result type specified. The result type may be any primitive type supported by
JDBC `ResultSet`s or a product thereof (including named products, i.e. `case
class`es). Note that products may be nested, in which case they will simply be
flattened when reading actual results from a query.

### Write Queries

Write queries (i.e. insertions, updates, deletes and table alterations) must be
run in a `write` call.
