import zio.{Console, Scope, ZIO, ZIOAppDefault, ZLayer}

object Main extends ZIOAppDefault:
  override def run: ZIO[Any, Any, Any] =
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
        .flatMap { api =>
          api.register("Toyota Corolla WE98765").flatMap(Console.printLine(_)) *>
            api.register("VW Golf WN12345").flatMap(Console.printLine(_)) *>
            api.register("Tesla").flatMap(Console.printLine(_))
        }
    }
