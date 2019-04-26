package coop.rchain.casper.helper
import cats.effect.Concurrent
import coop.rchain.models.{ListParWithRandom, Par}
import coop.rchain.rholang.interpreter.Runtime.SystemProcess
import coop.rchain.rholang.interpreter.{ContractCall, RhoType}

object DeployDataContract {
  import cats.implicits._

  def set[F[_]: Concurrent](
      ctx: SystemProcess.Context[F]
  )(message: (Seq[ListParWithRandom], Int)): F[Unit] = {

    val isContractCall = new ContractCall(ctx.space, ctx.dispatcher)
    message match {
      case isContractCall(
          produce,
          Seq(RhoType.String("userId"), (pk @ RhoType.ByteArray(_)), ackCh)
          ) =>
        for {
          _ <- ctx.deployParametersRef.update(_.copy(userId = pk))
          _ <- produce(Seq(Par()), ackCh)
        } yield ()
    }
  }
}
