import zio.{Cause, IO, Ref, Scope, Task, UIO, ULayer, URIO, ZIO, ZLayer}

class Test {}

case class Connection(id: String)

case class Car(make: String, model: String, licensePlate: String)

case class LicensePlateExistsError(licensePlate: String)

class CarService(carRepository: CarRepository, db: DB) {
  def register(car: Car): ZIO[Any, Throwable | LicensePlateExistsError, Unit] =
    db.transact {
      carRepository.exists(car.licensePlate).flatMap {
        case true  => ZIO.fail(LicensePlateExistsError(car.licensePlate))
        case false => carRepository.insert(car)
      }
    }
}

//

class CarRepository():
  def exists(licensePlate: String): ZIO[Connection, Nothing, Boolean] =
    // in reality we would use the connection to connect to the DB
    ZIO.service[Connection].map(_ => true)

  def insert(car: Car): ZIO[Connection, Nothing, Unit] = ???

object CarRepository:
  lazy val live: ZLayer[Any, Nothing, CarRepository] = ZLayer.succeed(CarRepository())

//

class DB(connectionPool: ConnectionPool):
  private def connection: ZIO[Scope, Throwable, Connection] =
    ZIO.acquireRelease(connectionPool.obtain)(c =>
      connectionPool
        .release(c)
        .catchAll(t => ZIO.logErrorCause("Exception when releasing a connection", Cause.fail(t)))
    )

  def transact[R, E, A](dbProgram: ZIO[Connection & R, E, A]): ZIO[R, E | Throwable, A] =
    ZIO.scoped {
      connection.flatMap { c =>
        dbProgram.provideSomeLayer(ZLayer.succeed(c))
      }
    }

object DB:
  lazy val live: ZLayer[ConnectionPool, Nothing, DB] = ZLayer.fromFunction(DB(_))

//

class ConnectionPool(r: Ref[Vector[Connection]]):
  def obtain: Task[Connection] = r.modify {
    case h +: t => (h, t)
    case _      => throw new IllegalStateException("No connection available!")
  }
  def release(c: Connection): Task[Unit] = r.modify(cs => ((), cs :+ c))

object ConnectionPool:
  lazy val live: ZLayer[Any, Nothing, ConnectionPool] =
    ZLayer(
      Ref
        .make(Vector(Connection("conn1"), Connection("conn2"), Connection("conn3")))
        .map(ConnectionPool(_))
    )
