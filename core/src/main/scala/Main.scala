import zio.{Console, Scope, ZIO, ZIOAppDefault, ZLayer, URIO, IO}

object Main extends ZIOAppDefault:

  private val program: URIO[CarApi, Unit] =
    for
      _ <- CarApi.register("Toyota Corolla WE98765").debug
      _ <- CarApi.register("VW Golf WN12345").debug
      _ <- CarApi.register("Tesla").debug
    yield ()

  private lazy val carApi: ZLayer[Any, Nothing, CarApi] =
    ZLayer.make[CarApi](
      CarApi.live,
      CarService.live,
      DB.live,
      ConnectionPool.live(3)
    )

  override def run: IO[Throwable, Unit] = program.provide(carApi)
