import zio.{Cause, durationInt, IO, Ref, Scope, Task, UIO, ULayer, URIO, ZIO, ZIOAppDefault, ZLayer}

case class Connection(id: String)
case class Car(make: String, model: String, licensePlate: String)
case class LicensePlateExistsError(licensePlate: String)

//

class CarApi(carService: CarService):
  def register(input: String): ZIO[Any, Nothing, String] =
    val rid = "REQ" + Math.abs(input.hashCode % 10000)
    ZIO.logSpan(rid) {
      input.split(" ", 3).toList match
        case List(f1, f2, f3) =>
          val car = Car(f1, f2, f3)
          carService.register(car).as("OK: Car registered").catchAll {
            case _: LicensePlateExistsError =>
              ZIO
                .logError(
                  s"Cannot register: $car, because a car with the same license plate already exists"
                )
                .as("Bad request: duplicate")
            case t =>
              ZIO
                .logErrorCause(s"Cannot register: $car, unknown error", Cause.fail(t))
                .as("Internal server error")
          }
        case _ => ZIO.logError(s"Bad request: $input").as("Bad Request")
    }

object CarApi:
  val live: ZLayer[CarService, Nothing, CarApi] = ZLayer.fromFunction(CarApi(_))
  def register(input: String): ZIO[CarApi, Nothing, String] =
    ZIO.serviceWithZIO[CarApi](_.register(input))

//

class CarService(db: DB):
  def register(car: Car): ZIO[Any, Throwable | LicensePlateExistsError, Unit] =
    db.transact {
      CarRepository.exists(car.licensePlate).flatMap {
        case true  => ZIO.fail(LicensePlateExistsError(car.licensePlate))
        case false => CarRepository.insert(car)
      }
    }

object CarService:
  val live: ZLayer[DB, Nothing, CarService] =
    ZLayer.fromFunction(CarService(_))

//

object CarRepository:
  def exists(licensePlate: String): ZIO[Connection, Nothing, Boolean] =
    ZIO
      .service[Connection]
      .map(_ => /* perform the check */ licensePlate.startsWith("WN"))
      .tap(_ => ZIO.logInfo(s"Checking if license plate exists: $licensePlate"))
      .delay(100.millis)

  def insert(car: Car): ZIO[Connection, Nothing, Unit] =
    ZIO
      .service[Connection]
      .map(_ => /* perform the insert */ ())
      .tap(_ => ZIO.logInfo(s"Inserting car: $car"))
      .delay(200.millis)

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
  val live: ZLayer[ConnectionPool, Nothing, DB] = ZLayer.fromFunction(DB(_))

//

class ConnectionPool(r: Ref[Vector[Connection]]):
  def obtain: Task[Connection] = r
    .modify {
      case h +: t => (h, t)
      case _      => throw new IllegalStateException("No connection available!")
    }
    .tap(c => ZIO.logInfo(s"Obtained connection: ${c.id}"))
  def release(c: Connection): Task[Unit] =
    r.modify(cs => ((), cs :+ c)).tap(_ => ZIO.logInfo(s"Released connection: ${c.id}"))
  def close: ZIO[Any, Nothing, Unit] =
    r.modify(c => (c, Vector.empty))
      .flatMap(conns => ZIO.foreachDiscard(conns)(conn => ZIO.logInfo(s"Closing: $conn")))

object ConnectionPool:

  def create(size: Int): ZIO[Scope, Nothing, ConnectionPool] =
    ZIO.acquireRelease(
      Ref
        .make(Vector.range(1, size).map(i => Connection(s"conn$i")))
        .map(ConnectionPool(_))
    )(_.close)

  def live(size: Int): ZLayer[Any, Nothing, ConnectionPool] =
    ZLayer.scoped(create(size))
