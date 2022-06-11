import zio.{Console, Scope, ZIO, ZIOAppDefault, ZLayer}

object Main extends ZIOAppDefault:
  val program: ZIO[CarApi, Nothing, Unit] =
    for {
      api <- ZIO.service[CarApi]
      _ <- api.register("Toyota Corolla WE98765").debug
      _ <- api.register("VW Golf WN12345").debug
      _ <- api.register("Tesla").debug
    } yield ()

  override def run: ZIO[Any, Any, Any] =
    program.provide(
      CarApi.live,
      CarService.live,
      CarRepository.live,
      DB.live,
      ConnectionPool.live
    )
