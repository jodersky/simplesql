# Simple SQL

A no-frills SQL library for Scala 3.

SimpleSQL is a very thin wrapper around JDBC, which allows you to take full
advantage of *full SQL* and *any database* with a JDBC driver.

SimpleSQL is not a functional DSL to build SQL queries (I recommend Quill for
that, which unfortunately is not yet available for Scala 3), but it does offer
safe string interpolation and utilities to work with product types, wich are the
natural representation of relational data sets.


SimpleSQL has no dependencies. It is published to maven central, under
`io.crashbox:simplesql_3`, but **it can also be embedded by copying the file
[simplesql/src/package.scala](https://raw.githubusercontent.com/jodersky/simplesql/master/simplesql/src/package.scala)
into your application**!

## Example

```scala
import simplesql as sq

// a plain DataSource is needed, this example uses a connection pool implemented
// by HicariCP, but you are free to use whatever DataSource you wish
val ds: javax.sql.DataSource = {
  val ds = com.zaxxer.hikari.HikariDataSource()
  ds.setJdbcUrl("jdbc:sqlite::memory:")
  ds
}

// all queries must be run within the context of a connection, use either
// `sq.run` or `sq.transaction` blocks
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
interpolator is a utility to build `simplesql.Query`s, which are builders for
`java.sql.PreparedStatements`. In other words, it can be used to build
injection-safe queries with interpolated parameters. Interpolated parameters may
be primitve types (supported by JDBC), or products of these. Products will be
flattened into multiple interpolated '?' parameters.

### Read Queries

Read queries (e.g. selects) must be run in a `read` call. A read must have its
result type specified. The result type may be any primitive type supported by
JDBC `ResultSet`s or a product thereof (including named products, i.e. `case
class`es). Note that products may be nested, in which case they will simply be
flattened when reading actual results from a query.

### Write Queries

Write queries (e.g. insertions, updates, deletes and table alterations) must be
run in a `write` call.
