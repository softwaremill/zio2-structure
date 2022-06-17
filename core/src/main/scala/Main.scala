import zio.{Console, Scope, ZIO, ZIOAppDefault, ZLayer}

import java.io.IOException

object Main extends ZIOAppDefault:
  override def run: ZIO[Any, Any, Any] =
    def program(api: CarApi): ZIO[Any, IOException, Unit] = for {
      _ <- api.register("Toyota Corolla WE98765").flatMap(Console.printLine(_))
      _ <- api.register("VW Golf WN12345").flatMap(Console.printLine(_))
      _ <- api.register("Tesla").flatMap(Console.printLine(_))
    } yield ()

    ZIO.scoped {
      ZLayer
        .makeSome[Scope, CarApi](
          CarApi.live,
          CarService.live,
          CarRepository.live,
          DB.live,
          ConnectionPool.live
        )
        .build
        .map(_.get[CarApi])
        .flatMap(program)
    }
