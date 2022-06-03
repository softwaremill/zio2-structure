import zio.{Cause, IO, Ref, Scope, Task, UIO, ULayer, URIO, ZIO, ZIOAppDefault, ZLayer}

case class Connection(id: String)
case class Car(make: String, model: String, licensePlate: String)
case class LicensePlateExistsError(licensePlate: String)

//

class CarApi(carService: CarService):
  def register(input: String): ZIO[Any, Nothing, String] =
    val result = input.split(" ", 3).toList match
      case List(f1, f2, f3) =>
        val car = Car(f1, f2, f3)
        carService.register(car).tapError {
          case _: LicensePlateExistsError =>
            ZIO.logError(
              s"Cannot register: $car, because a car with the same license plate already exists"
            )
          case t =>
            ZIO.logErrorCause(s"Cannot register: $car, unknown error", Cause.fail(t))
        }
      case _ => ZIO.logError(s"Bad request: $input") *> ZIO.fail(())

    result.map(_ => "Car registered").catchAll(_ => ZIO.succeed("Bad request"))

object CarApi:
  lazy val live: ZLayer[CarService, Any, CarApi] = ZLayer.fromFunction(CarApi(_))

//

class CarService(carRepository: CarRepository, db: DB):
  def register(car: Car): ZIO[Any, Throwable | LicensePlateExistsError, Unit] =
    db.transact {
      carRepository.exists(car.licensePlate).flatMap {
        case true  => ZIO.fail(LicensePlateExistsError(car.licensePlate))
        case false => carRepository.insert(car)
      }
    }

object CarService:
  lazy val live: ZLayer[CarRepository & DB, Nothing, CarService] =
    ZLayer.fromFunction(CarService(_, _))

//

class CarRepository():
  def exists(licensePlate: String): ZIO[Connection, Nothing, Boolean] =
    ZIO
      .service[Connection]
      .map(_ => /* perform the check */ licensePlate.startsWith("WN"))
      .tap(_ => ZIO.logInfo(s"Checking if license plate exists: $licensePlate"))

  def insert(car: Car): ZIO[Connection, Nothing, Unit] =
    ZIO
      .service[Connection]
      .map(_ => /* perform the insert */ ())
      .tap(_ => ZIO.logInfo(s"Inserting car: $car"))

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
  def obtain: Task[Connection] = r
    .modify {
      case h +: t => (h, t)
      case _      => throw new IllegalStateException("No connection available!")
    }
    .tap(c => ZIO.logInfo(s"Obtained connection: ${c.id}"))
  def release(c: Connection): Task[Unit] =
    r.modify(cs => ((), cs :+ c)).tap(_ => ZIO.logInfo(s"Released connection: ${c.id}"))

object ConnectionPool:
  lazy val live: ZLayer[Any, Nothing, ConnectionPool] =
    ZLayer(
      Ref
        .make(Vector(Connection("conn1"), Connection("conn2"), Connection("conn3")))
        .map(ConnectionPool(_))
    )
